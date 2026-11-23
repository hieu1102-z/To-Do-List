package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.app.NotificationManager
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.notification.NotificationPreferences
import com.finalexam.to_do_list.notification.TaskReminderScheduler
import java.util.concurrent.Callable

internal val categoryTransferLock = Any()

internal data class CategoryTransfer(
    val id: String,
    val owner: String,
    val sourceNameKey: String,
    val name: String
) {
    val bindings get() = arrayOf(id, owner, sourceNameKey, name)
}

internal data class LocalCategoryTransfer(
    val request: CategoryTransfer,
    val category: Row,
    val tasks: List<Row>,
    val subtasks: Map<String, List<Row>>,
    val reminders: Map<String, Row>,
    val participants: Set<String>
)

/** A durable guest journal keeps the source on disk until every upload is confirmed. */
internal class GuestCategoryTransfers(private val context: Context, private val database: AppDatabase) : AutoCloseable {
    private val sql get() = database.openHelper.writableDatabase
    override fun close() = database.close()

    fun pending(owner: String) = sql.rows(
        "SELECT * FROM local_category_transfers WHERE owner_uid=? AND completed=0", owner
    ).map { CategoryTransfer(it.string("category_id"), owner, it.string("source_name_key"), it.string("name")) }

    fun begin(categoryId: String, owner: String, name: String): LocalCategoryTransfer {
        val transfer = database.runInTransaction(Callable {
            val uid = database.tasks().guestUid()
            check(database.tasks().role(categoryId, uid) == "OWNER") { "Danh mục đang được chuyển hoặc không còn tồn tại." }
            val category = checkNotNull(sql.rows(
                "SELECT * FROM categories WHERE id=? AND owner_uid=? AND deleted_at IS NULL", categoryId, uid
            ).singleOrNull()) { "Danh mục không còn tồn tại." }
            val request = CategoryTransfer(categoryId, owner, category.string("name_key"), name)
            val source = read(request)
            source.tasks.forEach { task ->
                val title = task.string("title")
                require(((task["description"] as? String)?.length ?: 0) <= 10_000) {
                    "Mô tả của “$title” vượt quá 10.000 ký tự."
                }
                val dates = listOfNotNull(task.optionalLong("created_at"), task.optionalLong("due_at"),
                    task.optionalLong("completed_at"), source.reminders[task.string("id")]?.optionalLong("remind_at"))
                require(dates.all { it in 0..8_640_000_000_000_000L }) {
                    "Công việc “$title” có thời gian nằm ngoài phạm vi máy chủ hỗ trợ."
                }
            }
            sql.execSQL("""INSERT INTO local_category_transfers(category_id,owner_uid,source_name_key,name)
                VALUES(?,?,?,?)""", request.bindings)
            sql.execSQL("UPDATE category_members SET status='REMOVED' WHERE category_id=? AND user_uid=?",
                arrayOf(categoryId, uid))
            source
        })
        cancelLocalReminders(transfer)
        return transfer
    }

    fun read(request: CategoryTransfer): LocalCategoryTransfer {
        val uid = database.tasks().guestUid()
        val id = request.id
        val category = sql.rows("SELECT * FROM categories WHERE id=? AND owner_uid=?", id, uid).single()
        val tasks = sql.rows("SELECT * FROM tasks WHERE category_id=? ORDER BY id", id)
        val subtasks = sql.rows("""SELECT s.* FROM subtasks s JOIN tasks t ON t.id=s.task_id
            WHERE t.category_id=? ORDER BY s.id""", id)
        val reminders = sql.rows("""SELECT r.* FROM task_reminders r JOIN tasks t ON t.id=r.task_id
            WHERE t.category_id=? AND r.user_uid=? AND (r.deleted_at IS NULL OR r.deleted_at=t.deleted_at)""", id, uid)
        val participants = sql.rows("SELECT task_id FROM task_participants WHERE category_id=? AND user_uid=?", id, uid)
        return LocalCategoryTransfer(request, category, tasks, subtasks.groupBy { it.string("task_id") },
            reminders.associateBy { it.string("task_id") }, participants.map { it.string("task_id") }.toSet())
    }

    fun cancelLocalReminders(source: LocalCategoryTransfer) {
        val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        source.tasks.forEach {
            val id = it.string("id")
            TaskReminderScheduler.cancel(context, id, it.string("title"))
            manager.cancel(id.hashCode())
        }
    }

    fun release(request: CategoryTransfer) {
        val released = database.runInTransaction(Callable {
            if (pending(request.owner).none { it == request }) return@Callable null
            val source = read(request)
            sql.execSQL("UPDATE category_members SET status='ACTIVE' WHERE category_id=? AND user_uid=?",
                arrayOf(request.id, database.tasks().guestUid()))
            sql.execSQL("DELETE FROM local_category_transfers WHERE category_id=?", arrayOf(request.id))
            source
        }) ?: return
        if (NotificationPreferences.isEnabled(context)) {
            released.tasks.filter { it["deleted_at"] == null && it.long("is_done") == 0L }.forEach { task ->
                val id = task.string("id")
                released.reminders[id]?.takeIf { it.long("is_enabled") == 1L && it.long("remind_at") > System.currentTimeMillis() }
                    ?.let { TaskReminderScheduler.schedule(context, id, task.string("title"), it.long("remind_at")) }
            }
        }
    }

    fun complete(request: CategoryTransfer) = database.runInTransaction(Callable {
        if (pending(request.owner).none { it == request }) return@Callable
        val args = arrayOf(request.id)
        sql.execSQL("DELETE FROM sync_queue WHERE category_id=?", args)
        sql.execSQL("DELETE FROM sync_state WHERE category_id=?", args)
        sql.execSQL("DELETE FROM categories WHERE id=?", args)
        sql.execSQL("UPDATE local_category_transfers SET completed=1 WHERE category_id=?", args)
    })
}
