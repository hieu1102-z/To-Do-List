package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.entity.SubtaskEntity
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.local.relation.TrashTask
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.notification.AccountReminders
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

internal fun AuthUser.toWorkspaceProfile() =
    UserEntity(uid, email, nameKey(email), displayName.orEmpty(), emailVerified, avatarUrl)

class UnifiedTaskRepository private constructor(private val context: Context) {
    private val local = GuestTaskRepository.get(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val remoteExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val auth get() = AuthRepository.get(context)
    val currentUid get() = auth.user?.uid
    private val now get() = System.currentTimeMillis()

    private fun <T> open(uid: String, work: AccountWorkspace.() -> T): T {
        val user = auth.user?.takeIf { it.uid == uid } ?: error("Phiên đăng nhập đã thay đổi.")
        return AccountWorkspace(uid, AppDatabase.accountBuilder(context, uid).build()).use { store ->
            store.database.users().upsert(user.toWorkspaceProfile())
            store.work()
        }
    }

    private fun <T> account(uid: String, mutate: Boolean = false, callback: (Result<T>) -> Unit,
                            work: AccountWorkspace.() -> T) {
        executor.execute {
            val result = runCatching {
                open(uid) {
                    if (!mutate) work() else {
                        val value = transaction { work() }
                        if (currentUid == uid) AccountReminders.reconcile(context, this)
                        WorkspaceSync.request(context, uid)
                        WorkspaceSync.changed()
                        value
                    }
                }
            }
            main.post { callback(if (currentUid == uid) result else Result.failure(IllegalStateException("Phiên đăng nhập đã thay đổi."))) }
        }
    }

    fun load(callback: (Result<List<HomeTask>>) -> Unit) {
        local.load { guest ->
            val uid = currentUid
            if (uid == null || guest.isFailure) callback(guest)
            else account(uid, callback = { cloud ->
                val accountTasks = cloud.getOrDefault(emptyList())
                val accountCategories = accountTasks.map { it.categoryId }.toSet()
                val rows = guest.getOrThrow().filterNot { it.categoryId in accountCategories }
                callback(Result.success((rows + accountTasks)
                    .sortedWith(compareBy<HomeTask> { it.done }.thenBy { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })))
            }) { home() }
        }
    }

    fun categories(callback: (Result<List<WorkspaceCategory>>) -> Unit) {
        local.workspaceCategories { guest ->
            val uid = currentUid
            if (uid == null || guest.isFailure) callback(guest)
            else account(uid, callback = { cloud ->
                val accountCategories = cloud.getOrDefault(emptyList())
                val accountIds = accountCategories.map { it.ref.id }.toSet()
                callback(Result.success(guest.getOrThrow().filterNot { it.ref.id in accountIds } + accountCategories))
            }) { categories() }
        }
    }

    fun watch() {
        val uid = currentUid ?: return WorkspaceSync.unwatch()
        account(uid, callback = { result ->
            result.onSuccess { if (currentUid == uid) WorkspaceSync.watch(context, uid, it) }
        }) { sql.rows("SELECT id FROM categories WHERE revision>0").map { it.string("id") }.toSet() }
    }

    fun start() {
        currentUid?.let { uid ->
            WorkspaceSync.start(context, uid)
            account(uid, callback = {}) { AccountReminders.reconcile(context, this) }
            watch()
        }
    }

    fun createCategory(name: String, workspace: WorkspaceRef, callback: (Result<String>) -> Unit) {
        val uid = workspace.uid
        if (uid == null) local.createCategory(name, callback)
        else account(uid, true, callback) { createCategory(name) }
    }

    fun renameCategory(category: WorkspaceCategory, title: String, callback: (Result<Unit>) -> Unit) {
        val uid = category.ref.workspace.uid
        if (uid == null) return local.renameCategory(category.name, title, category.ref.id, callback)
        account(uid, true, callback) {
            val cid = category.ref.id
            requireRole(cid, "OWNER")
            val old = checkNotNull(category(cid))
            check(old["deleted_at"] == null)
            check(old.long("revision") == category.revision) { "Danh mục vừa thay đổi. Hãy mở lại." }
            val name = normalizedName(title)
            require(name.length in 1..100)
            upsert("categories", old + mapOf("name" to name, "name_key" to nameKey(name), "client_updated_at" to now))
            enqueue(cid, "CATEGORY", cid, "UPDATE", old.long("revision"), mapOf("name" to name))
        }
    }

    fun deleteCategory(category: WorkspaceCategory, callback: (Result<Unit>) -> Unit) {
        val uid = category.ref.workspace.uid
        if (uid == null) return local.deleteCategory(category.name, category.ref.id, callback)
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
        if (uid == null) return local.create(title, description, destination.name, priority, dueAt, reminderMinutes, repeatType, callback)
        account(uid, true, callback) {
            val cid = destination.ref.id.ifEmpty { todoCategory() }
            requireActive(cid)
            val task = taskFields(title, description, priority, dueAt, repeatType)
            val id = uuid()
            upsert("tasks", task + mapOf("id" to id, "category_id" to cid, "created_by_uid" to uid,
                "created_at" to now, "client_updated_at" to now))
            enqueue(cid, "TASK", id, "CREATE", 0, task)
            editReminder(cid, id, dueAt?.let { due -> reminderMinutes?.let { due - it * 60000 } })
        }
    }

    fun updateTask(task: HomeTask, title: String, description: String, category: String, priority: Int,
                   dueAt: Long?, reminderMinutes: Long?, reminderChanged: Boolean, repeatType: String,
                   callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        if (uid == null) return local.updateTask(task.id, title, description, category, priority, dueAt,
            reminderMinutes, reminderChanged, repeatType, callback)
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

    private fun taskFields(title: String, description: String, priority: Int, dueAt: Long?, repeat: String): Row {
        val trimmed = title.trim()
        require(trimmed.length in 1..200 && description.length <= 10000 && priority in 1..3)
        require(repeat in setOf("NONE", "DAILY", "WEEKLY", "MONTHLY") && (repeat == "NONE" || dueAt != null))
        val zone = ZoneId.systemDefault()
        return mapOf("title" to trimmed, "description" to description.trim(), "priority" to priority,
            "due_at" to dueAt, "repeat_type" to repeat, "timezone" to zone.id,
            "repeat_anchor_day" to dueAt?.takeIf { repeat == "MONTHLY" }?.let { Instant.ofEpochMilli(it).atZone(zone).dayOfMonth })
    }

    private fun AccountWorkspace.requireActive(cid: String) {
        requireRole(cid, "OWNER", "EDITOR")
        check(checkNotNull(category(cid))["deleted_at"] == null) { "Danh mục nằm trong thùng rác." }
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
        if (uid == null) return local.toggle(task.id, callback)
        account(uid, true, callback) {
            val row = editable(task, true)
            val done = row.long("is_done") == 0L
            upsert("tasks", row + mapOf("is_done" to done, "completed_at" to if (done) now else null, "client_updated_at" to now))
            enqueue(task.categoryId, "TASK", task.id, "COMPLETE", row.long("revision"), mapOf("is_done" to if (done) 1 else 0))
        }
    }

    fun delete(task: HomeTask, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        if (uid == null) return local.delete(task.id, callback)
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
        local.removeReminders(tasks.filter { it.workspaceUid == null }.map { it.id }.toSet()) { result ->
            val uid = currentUid
            if (uid == null || result.isFailure || tasks.none { it.workspaceUid == uid }) callback(result)
            else account(uid, true, callback) {
                tasks.filter { it.workspaceUid == uid }.forEach {
                    check(role(it.categoryId) != null && it.categoryId !in transferIds())
                    editReminder(it.categoryId, it.id, null)
                }
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        local.setNotificationsEnabled(enabled) { result ->
            val uid = currentUid
            if (uid == null || result.isFailure) callback(result)
            else account(uid, callback = callback) { AccountReminders.reconcile(context, this) }
        }
    }

    fun subtasks(task: HomeTask, callback: (Result<List<SubtaskEntity>>) -> Unit) {
        val uid = task.workspaceUid
        if (uid == null) return local.subtasks(task.id, callback)
        account(uid, callback = callback) {
            check(role(task.categoryId) != null && category(task.categoryId)?.get("deleted_at") == null)
            database.tasks().subtasks(task.id)
        }
    }

    fun addSubtask(task: HomeTask, title: String, callback: (Result<Unit>) -> Unit) {
        val uid = task.workspaceUid
        if (uid == null) return local.addSubtask(task.id, title, callback)
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
        if (uid == null) {
            if (title == null) local.deleteSubtask(sub.id, callback) else local.updateSubtask(sub.id, title, done, callback)
            return
        }
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

    fun trash(callback: (Result<List<TrashTask>>) -> Unit) {
        local.trash { localResult ->
            val uid = currentUid
            if (uid == null || localResult.isFailure) callback(localResult)
            else account(uid, callback = { cloud -> callback(Result.success(
                (localResult.getOrThrow() + cloud.getOrDefault(emptyList())).sortedByDescending { it.deletedAt }
            )) }) { trash() }
        }
    }

    fun trashAction(item: TrashTask, purge: Boolean, name: String? = null, callback: (Result<Unit>) -> Unit) {
        val uid = item.workspaceUid
        if (uid == null) {
            when (item.entityType) {
                "CATEGORY" -> local.changeCategoryTrash(item, purge, name, callback)
                "SUBTASK" -> local.changeSubtaskTrash(item, purge, callback)
                else -> if (purge) local.permanentlyDeleteTask(item.id, callback) else local.restoreTask(item.id, callback)
            }
        } else account(uid, true, callback) { changeTrash(item, purge, name) }
    }

    private fun AccountWorkspace.changeTrash(item: TrashTask, purge: Boolean, name: String?) {
        val cid = item.categoryId
        if (purge || item.entityType == "CATEGORY") requireRole(cid, "OWNER") else requireActive(cid)
        val table = if (item.entityType == "CATEGORY") "categories" else snapshotGroups.getValue(item.entityType)
        val old = checkNotNull(sql.rows("SELECT * FROM $table WHERE id=?", item.id).firstOrNull())
        check(old["deleted_at"] != null && old.long("revision") == item.revision)
        if (!purge && item.parentId != null) check(database.tasks().find(item.parentId) != null)
        val newName = if (!purge && item.entityType == "CATEGORY") normalizedName(name ?: old.string("name")) else null
        if (newName != null) require(newName.length in 1..100)
        val fields = newName?.let { mapOf("name" to it, "name_key" to nameKey(it)) }.orEmpty()
        if (purge) sql.execSQL("DELETE FROM $table WHERE id=?", arrayOf(item.id))
        else upsert(table, old + fields + mapOf("deleted_at" to null, "client_updated_at" to now))
        enqueue(cid, item.entityType, item.parentId?.let { "$it/" + item.id } ?: item.id,
            if (purge) "PURGE" else "RESTORE", item.revision, newName?.let { mapOf("name" to it) }.orEmpty())
    }

    fun permanentlyDeleteAll(callback: (Result<Unit>) -> Unit) {
        val uid = currentUid
        local.permanentlyDeleteAll { result ->
            if (uid == null || result.isFailure) callback(result)
            else account(uid, true, callback) { trash().filter { it.role == "OWNER" }.forEach { changeTrash(it, true, null) } }
        }
    }

    fun transferLocal(categoryId: String, owner: String, name: String, callback: (Result<Unit>) -> Unit) {
        if (!WorkspaceSync.configured) return callback(Result.failure(WorkspaceRequestError("BACKEND_NOT_CONFIGURED")))
        executor.execute {
            val result = runCatching {
                val title = normalizedName(name)
                require(title.length in 1..100) { "Tên danh mục cần từ 1 đến 100 ký tự." }
                synchronized(categoryTransferLock) {
                    open(owner) {
                        check(database.tasks().categoryId(owner, nameKey(title)) == null) {
                            "Tài khoản đã có danh mục cùng tên. Hãy chọn tên khác."
                        }
                        GuestCategoryTransfers(context, AppDatabase.guestBuilder(context).build()).use { guest ->
                            val source = guest.begin(categoryId, owner, title)
                            try {
                                transferIn(source)
                            } catch (error: Exception) {
                                guest.release(source.request)
                                throw error
                            }
                        }
                        AccountReminders.reconcile(context, this)
                    }
                }
                WorkspaceSync.request(context, owner)
                WorkspaceSync.changed()
            }
            main.post {
                if (currentUid == owner) callback(result)
                else callback(Result.failure(IllegalStateException("Phiên đăng nhập đã thay đổi.")))
            }
        }
    }

    internal fun sharing(categoryId: String, callback: (Result<Boolean>) -> Unit) {
        val uid = currentUid ?: return callback(Result.success(false))
        account(uid, callback = callback) {
            categoryId !in transferIds() && category(categoryId)?.long("revision")?.let { it > 0 } == true &&
                commands().none { it["category_id"] == categoryId }
        }
    }

    internal fun <T> online(callback: (Result<T>) -> Unit, work: (WorkspaceRemote) -> T) {
        val uid = currentUid ?: return callback(Result.failure(IllegalStateException("Hãy đăng nhập.")))
        if (!WorkspaceSync.configured) return callback(Result.failure(WorkspaceRequestError("BACKEND_NOT_CONFIGURED")))
        val app = WorkspaceSync.app(context) ?: return callback(Result.failure(IllegalStateException("Firebase chưa được cấu hình.")))
        remoteExecutor.execute {
            val result = runCatching { check(currentUid == uid); work(WorkspaceRemote(app, uid)) }
            main.post {
                if (currentUid == uid) {
                    WorkspaceSync.request(context, uid)
                    callback(result)
                } else callback(Result.failure(IllegalStateException("Phiên đăng nhập đã thay đổi.")))
            }
        }
    }

    internal fun conflicts(callback: (Result<List<Row>>) -> Unit) {
        val uid = currentUid ?: return callback(Result.success(emptyList()))
        account(uid, callback = callback) {
            commands().filter { it["status"] == "REJECTED" }.map { command ->
                val id = command.string("entity_id").substringAfterLast('/')
                val kind = command.string("entity_type")
                val table = if (kind == "CATEGORY") "categories" else snapshotGroups[kind]
                val live = if (kind in setOf("CATEGORY", "TASK", "SUBTASK")) {
                    sql.rows("SELECT * FROM $table WHERE id=?", id).firstOrNull()
                } else null
                command + mapOf("current" to live, "transfer_pending" to (command.string("category_id") in transferIds()))
            }
        }
    }

    internal fun discard(operationId: String, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid ?: return
        account(uid, true, callback) {
            val command = commands().firstOrNull { it["operation_id"] == operationId && it["status"] == "REJECTED" }
            check(command?.get("category_id") !in transferIds()) { "Không thể bỏ một phần danh mục đang chuyển." }
            if (command?.get("entity_type") == "CATEGORY" && command["action"] == "CREATE") {
                sql.execSQL("DELETE FROM local_category_copies WHERE account_category_id=?", arrayOf(command["category_id"]))
            }
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
