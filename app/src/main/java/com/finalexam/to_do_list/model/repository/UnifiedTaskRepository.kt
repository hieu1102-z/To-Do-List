package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.finalexam.to_do_list.model.local.entity.SubtaskEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.local.relation.TrashTask
import com.finalexam.to_do_list.notification.AccountReminders
import java.util.concurrent.Executors

internal data class HomeContent(val tasks: List<HomeTask>, val categories: List<WorkspaceCategory>)

class UnifiedTaskRepository private constructor(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val remoteExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    val currentUid get() = AccountSession.current(context)?.uid
    private val now get() = System.currentTimeMillis()

    private fun <T> account(uid: String?, mutate: Boolean = false, callback: (Result<T>) -> Unit,
                            work: AccountWorkspace.() -> T) {
        val session = AccountSession.current(context)?.takeIf { it.uid == uid }
            ?: return callback(Result.failure(AuthFailure.SESSION_REQUIRED))
        executor.execute {
            val result = runCatching {
                AccountSession.access(context, session) {
                    if (!mutate) work() else {
                        val value = transaction { work() }
                        if (AccountSession.current(context) === session) {
                            AccountReminders.reconcile(context, this)
                            WorkspaceSync.request(context, session)
                            WorkspaceSync.changed()
                        }
                        value
                    }
                }
            }
            main.post {
                callback(if (AccountSession.current(context) === session) result else Result.failure(AuthFailure.CANCELLED))
            }
        }
    }

    fun load(callback: (Result<List<HomeTask>>) -> Unit) = account(currentUid, callback = callback) { home() }

    internal fun loadHome(callback: (Result<HomeContent>) -> Unit) =
        account(currentUid, callback = callback) { HomeContent(home(), categories()) }

    fun categories(callback: (Result<List<WorkspaceCategory>>) -> Unit) = account(currentUid, callback = callback) { categories() }

    fun start() {
        currentUid?.let { uid ->
            WorkspaceSync.start(context, uid)
            account(uid, callback = {}) { AccountReminders.reconcile(context, this) }
        }
    }

    fun createCategory(name: String, workspace: WorkspaceRef, color: String, icon: String?, callback: (Result<String>) -> Unit) {
        val uid = workspace.uid
        account(uid, true, callback) { createCategory(name, color = color, icon = icon) }
    }

    fun updateCategory(category: WorkspaceCategory, title: String, color: String?, icon: String?,
                       callback: (Result<WorkspaceCategory>) -> Unit) =
        account(category.ref.workspace.uid, true, callback) { updateCategory(category, title, color, icon) }

    fun deleteCategory(category: WorkspaceCategory, callback: (Result<Unit>) -> Unit) {
        val uid = category.ref.workspace.uid
        account(uid, true, callback) {
            val cid = category.ref.id
            requireRole(cid, "OWNER")
            val row = checkNotNull(category(cid))
            check(row.long("revision") == category.revision && row["deleted_at"] == null)
            upsert("categories", row + mapOf("deleted_at" to now, "client_updated_at" to now))
            enqueue(cid, "CATEGORY", cid, "TRASH", row.long("revision"))
        }
    }

    fun create(title: String, description: String, destination: WorkspaceCategory, priority: Int,
               dueAt: Long?, reminderMinutes: Long?, repeatType: String, callback: (Result<Unit>) -> Unit) {
        val uid = destination.ref.workspace.uid
        account(uid, true, callback) {
            val cid = destination.ref.id.ifEmpty { ensureDefaultCategory() }
            requireActive(cid)
            val task = taskFields(title, description, priority, dueAt, repeatType)
            val id = uuid()
            upsert("tasks", task + mapOf("id" to id, "category_id" to cid, "created_by_uid" to uid,
                "created_at" to now, "client_updated_at" to now))
            enqueue(cid, "TASK", id, "CREATE", 0, task)
            editReminder(cid, id, dueAt?.let { due -> reminderMinutes?.let { due - it * 60000 } })
        }
    }

    fun updateTask(task: HomeTask, title: String, description: String, priority: Int,
                   dueAt: Long?, reminderMinutes: Long?, reminderChanged: Boolean, repeatType: String,
                   callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        account(uid, true, callback) {
            val old = editable(task, true)
            val fields = taskFields(title, description, priority, dueAt, repeatType)
            upsert("tasks", old + fields + ("client_updated_at" to maxOf(now, old.long("client_updated_at") + 1)))
            enqueue(task.categoryId, "TASK", task.id, "UPDATE", old.long("revision"), fields)
            if (reminderChanged || dueAt != task.dueAt) {
                val offset = if (reminderChanged) reminderMinutes?.times(60000)
                    else task.remindAt?.let { at -> task.dueAt?.minus(at) }
                editReminder(task.categoryId, task.id, dueAt?.let { due -> offset?.let { due - it } })
            }
        }
    }

    private fun AccountWorkspace.editable(task: HomeTask, requireUnchanged: Boolean): Row {
        requireActive(task.categoryId)
        val row = checkNotNull(sql.rows("SELECT * FROM tasks WHERE id=? AND category_id=? AND deleted_at IS NULL",
            task.id, task.categoryId).firstOrNull()) { "Công việc không còn tồn tại." }
        if (requireUnchanged) check(row.long("revision") == task.revision && row.long("client_updated_at") == task.updatedAt) {
            "Công việc vừa thay đổi. Hãy sao lưu nội dung đang nhập rồi mở lại công việc."
        }
        return row
    }

    fun toggle(task: HomeTask, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        account(uid, true, callback) {
            val row = editable(task, true)
            val done = row.long("is_done") == 0L
            upsert("tasks", row + mapOf("is_done" to done, "completed_at" to if (done) now else null, "client_updated_at" to now))
            enqueue(task.categoryId, "TASK", task.id, "COMPLETE", row.long("revision"), mapOf("is_done" to if (done) 1 else 0))
        }
    }

    fun delete(task: HomeTask, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        account(uid, true, callback) {
            val row = editable(task, true)
            upsert("tasks", row + mapOf("deleted_at" to now, "client_updated_at" to now))
            enqueue(task.categoryId, "TASK", task.id, "TRASH", row.long("revision"))
        }
    }

    private fun AccountWorkspace.editReminder(cid: String, id: String, at: Long?) {
        val prior = sql.rows("SELECT * FROM task_reminders WHERE task_id=? AND user_uid=?", id, uid).firstOrNull()
        if (at == null || at <= now) {
            if (prior != null) {
                sql.execSQL("DELETE FROM task_reminders WHERE task_id=? AND user_uid=?", arrayOf(id, uid))
                enqueue(cid, "REMINDER", "$id/$uid", "REMOVE_REMINDER", prior.long("revision"))
            }
            return
        }
        val fields = mapOf("remind_at" to at, "is_enabled" to 1)
        upsert("task_reminders", fields + mapOf("task_id" to id, "user_uid" to uid,
            "client_updated_at" to now, "deleted_at" to null))
        enqueue(cid, "REMINDER", "$id/$uid", if (prior == null) "CREATE" else "UPDATE", prior?.long("revision") ?: 0, fields)
    }

    fun removeReminders(tasks: List<HomeTask>, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid
        account(uid, true, callback) {
            tasks.filter { it.workspaceUid == uid }.forEach {
                check(role(it.categoryId) != null)
                editReminder(it.categoryId, it.id, null)
            }
        }
    }

    fun refreshNotifications(callback: (Result<Unit>) -> Unit) =
        account(currentUid, callback = callback) { AccountReminders.reconcile(context, this) }

    fun subtasks(task: HomeTask, callback: (Result<List<SubtaskEntity>>) -> Unit) {
        val uid = task.workspaceUid
        account(uid, callback = callback) {
            check(role(task.categoryId) != null && category(task.categoryId)?.get("deleted_at") == null)
            database.tasks().subtasks(task.id)
        }
    }

    fun addSubtask(task: HomeTask, title: String, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        account(uid, true, callback) {
            editable(task, false)
            val id = uuid()
            val fields = mapOf("title" to title.trim(), "sort_order" to database.tasks().nextSubtaskOrder(task.id), "is_done" to 0)
            upsert("subtasks", fields + mapOf("id" to id, "task_id" to task.id, "client_updated_at" to now))
            enqueue(task.categoryId, "SUBTASK", task.id + "/" + id, "CREATE", 0, fields)
        }
    }

    fun changeSubtask(task: HomeTask, sub: SubtaskEntity, title: String?, done: Boolean, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        account(uid, true, callback) {
            editable(task, false)
            val old = checkNotNull(database.tasks().findSubtask(sub.id))
            check(old.taskId == task.id && old.revision == sub.revision && old.clientUpdatedAt == sub.clientUpdatedAt)
            val fields = title?.let { mapOf("title" to it.trim(), "is_done" to if (done) 1 else 0) }.orEmpty()
            upsert("subtasks", fields + mapOf("id" to sub.id, "deleted_at" to if (title == null) now else null,
                "client_updated_at" to now))
            enqueue(task.categoryId, "SUBTASK", task.id + "/" + sub.id, if (title == null) "TRASH" else "UPDATE", old.revision, fields)
        }
    }

    fun participant(task: HomeTask, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid ?: return
        account(uid, true, callback) {
            editable(task, false)
            val row = sql.rows("SELECT * FROM task_participants WHERE task_id=? AND user_uid=?", task.id, uid).firstOrNull()
            if (row == null) {
                upsert("task_participants", mapOf("task_id" to task.id, "category_id" to task.categoryId, "user_uid" to uid, "joined_at" to now))
                enqueue(task.categoryId, "PARTICIPANT", task.id + "/" + uid, "CREATE", 0)
            } else {
                sql.execSQL("DELETE FROM task_participants WHERE task_id=? AND user_uid=?", arrayOf(task.id, uid))
                enqueue(task.categoryId, "PARTICIPANT", task.id + "/" + uid, "WITHDRAW", row.long("revision"))
            }
        }
    }

    fun trash(callback: (Result<List<TrashTask>>) -> Unit) = account(currentUid, callback = callback) { trash() }

    fun trashAction(item: TrashTask, purge: Boolean, name: String? = null, callback: (Result<Unit>) -> Unit) {
        account(item.workspaceUid, true, callback) { changeTrash(item, purge, name) }
    }

    fun permanentlyDeleteAll(callback: (Result<Unit>) -> Unit) = account(currentUid, true, callback) {
        trash().filter { it.role == "OWNER" }.forEach { changeTrash(it, true, null) }
    }

    internal fun cachedMembers(category: WorkspaceCategory, callback: (Result<WorkspaceMembers>) -> Unit) =
        account(category.ref.workspace.uid, callback = callback) { cachedMembers(category.ref.id) }

    internal fun refreshMembers(category: WorkspaceCategory, callback: (Result<WorkspaceMembers>) -> Unit) {
        if (currentUid != category.ref.workspace.uid) return callback(Result.failure(AuthFailure.CANCELLED))
        online(callback, scheduleSync = false) { it.members(category.ref.id) }
    }

    internal fun cachedPendingInvitations(category: WorkspaceCategory, callback: (Result<List<WorkspacePendingInvitation>>) -> Unit) =
        account(category.ref.workspace.uid, callback = callback) { cachedPendingInvitations(category.ref.id) }

    internal fun refreshPendingInvitations(category: WorkspaceCategory, callback: (Result<List<WorkspacePendingInvitation>>) -> Unit) {
        val uid = category.ref.workspace.uid
        if (currentUid != uid) return callback(Result.failure(AuthFailure.CANCELLED))
        val categoryId = category.ref.id
        online({ result ->
            result.fold({ rows ->
                account(uid, callback = callback) { replacePendingInvitations(categoryId, rows) }
            }, { error ->
                if (error.businessCode() == "PERMISSION_DENIED") {
                    account(uid, callback = { callback(Result.failure(it.exceptionOrNull() ?: error)) }) {
                        clearPendingInvitations(categoryId)
                    }
                } else callback(Result.failure(error))
            })
        }, scheduleSync = false) { it.pages("SENT", mapOf("category_id" to categoryId)) }
    }

    internal fun cancelPendingInvitation(
        category: WorkspaceCategory, invite: WorkspacePendingInvitation, callback: (Result<Unit>) -> Unit
    ) {
        val uid = category.ref.workspace.uid
        if (uid == null || currentUid != uid) return callback(Result.failure(AuthFailure.CANCELLED))
        if (category.role != "OWNER") return callback(Result.failure(WorkspaceRequestError("PERMISSION_DENIED")))
        val categoryId = category.ref.id
        val request = mapOf("kind" to "CANCEL", "category_id" to categoryId, "invitation_id" to invite.id,
            "base_revision" to invite.revision, "operation_id" to uuid(), "client_occurred_at" to now)
        online<Unit>({ result ->
            result.fold({
                account(uid, callback = callback) { removePendingInvitation(categoryId, invite.id) }
            }, { callback(Result.failure(it)) })
        }) { remote ->
            if (remote.call(request)["status"] != "CANCELLED") throw WorkspaceRequestError("UNAVAILABLE")
        }
    }

    internal fun sharing(categoryId: String, callback: (Result<Boolean>) -> Unit) {
        val uid = currentUid ?: return callback(Result.success(false))
        account(uid, callback = callback) {
            categoryId != defaultCategoryId(uid) && category(categoryId)?.long("revision")?.let { it > 0 } == true &&
                commands().none { it["category_id"] == categoryId }
        }
    }

    internal fun <T> online(callback: (Result<T>) -> Unit, scheduleSync: Boolean = true, work: (WorkspaceRemote) -> T) {
        val session = AccountSession.current(context) ?: return callback(Result.failure(AuthFailure.SESSION_REQUIRED))
        val uid = session.uid
        if (!WorkspaceSync.configured) return callback(Result.failure(WorkspaceRequestError("BACKEND_NOT_CONFIGURED")))
        val app = WorkspaceSync.app(context) ?: return callback(Result.failure(IllegalStateException("Firebase chưa được cấu hình.")))
        remoteExecutor.execute {
            val result = runCatching { work(WorkspaceRemote(app, uid) { AccountSession.current(context) === session }) }
            main.post {
                if (AccountSession.current(context) === session) {
                    if (scheduleSync) WorkspaceSync.request(context, session, discover = true)
                    callback(result)
                } else callback(Result.failure(IllegalStateException("Phiên đăng nhập đã thay đổi.")))
            }
        }
    }

    internal fun conflicts(callback: (Result<List<Row>>) -> Unit) =
        account(currentUid, callback = callback) { syncConflictRows() }

    internal fun reapplyConflict(operationId: String, expectedRevision: Long, fields: Set<String>, callback: (Result<Unit>) -> Unit) =
        account(currentUid, true, callback) { reapplyRejectedChange(operationId, expectedRevision, fields) }

    internal fun discard(operationId: String, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid ?: return
        account(uid, true, callback) {
            val command = commands().firstOrNull { it["operation_id"] == operationId && it["status"] == "REJECTED" }
            check(command == null || envelope(command)["kind"] != "ENSURE_DEFAULT_CATEGORY") { "Danh mục mặc định còn công việc chờ đồng bộ." }
            sql.execSQL("DELETE FROM sync_queue WHERE actor_uid=? AND operation_id=? AND status='REJECTED'", arrayOf(uid, operationId))
        }
    }

    companion object {
        @Volatile private var instance: UnifiedTaskRepository? = null
        fun get(context: Context): UnifiedTaskRepository = instance ?: synchronized(this) {
            instance ?: UnifiedTaskRepository(context.applicationContext).also { instance = it }
        }
    }
}
