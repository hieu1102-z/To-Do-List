package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.GuestBootstrap
import com.finalexam.to_do_list.model.local.entity.TaskEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import com.finalexam.to_do_list.model.local.entity.TaskReminderEntity
import com.finalexam.to_do_list.model.local.entity.SubtaskEntity
import com.finalexam.to_do_list.model.local.entity.SyncQueueEntity
import com.finalexam.to_do_list.model.local.relation.TrashTask
import com.finalexam.to_do_list.notification.TaskReminderScheduler
import com.finalexam.to_do_list.notification.NotificationPreferences
import com.finalexam.to_do_list.util.TaskDefaults
import java.time.Instant
import java.time.ZoneId

/** Serial database operations off the UI thread. No network dependency. */
class GuestTaskRepository internal constructor(
    private val appContext: Context,
    private val database: AppDatabase,
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit
) {
    private val dao get() = database.tasks()

    fun load(callback: (Result<List<HomeTask>>) -> Unit) = execute({ dao.homeTasks(dao.guestUid()) }, callback)
    fun categories(callback: (Result<List<String>>) -> Unit) = execute({ dao.categoryNames(dao.guestUid()) }, callback)

    fun renameCategory(oldName: String, newName: String, callback: (Result<Unit>) -> Unit) = execute({
        val name = newName.trim()
        require(name.length in 1..100) { "Tên danh mục cần từ 1 đến 100 ký tự." }
        val uid = dao.guestUid()
        val old = requireNotNull(dao.findCategory(uid, GuestBootstrap.nameKey(oldName))) { "Danh mục không còn tồn tại." }
        val key = GuestBootstrap.nameKey(name)
        check(dao.findCategory(uid, key)?.id == null || dao.findCategory(uid, key)?.id == old.id) { "Danh mục này đã tồn tại." }
        transaction { dao.renameCategory(old.id, name, key, System.currentTimeMillis()) }
    }, callback)

    fun deleteCategory(name: String, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()
        val category = requireNotNull(dao.findCategory(uid, GuestBootstrap.nameKey(name))) { "Danh mục không còn tồn tại." }
        check(dao.activeCategoryCount(uid) > 1) { "Cần giữ lại ít nhất một danh mục." }
        val tasks = dao.activeTasksInCategory(category.id)
        val now = System.currentTimeMillis()
        transaction {
            tasks.forEach { task ->
                dao.softDeleteTask(task.id, now, now)
                dao.softDeleteSubtasks(task.id, now, now)
                dao.softDeleteRemindersForTask(task.id, now, now)
            }
            dao.softDeleteCategory(category.id, now, now)
        }
        tasks.forEach { TaskReminderScheduler.cancel(appContext, it.id, it.title) }
    }, callback)

    fun subtasks(taskId: String, callback: (Result<List<SubtaskEntity>>) -> Unit) = execute({
        requireNotNull(dao.find(taskId)) { "Công việc không còn tồn tại." }
        dao.subtasks(taskId)
    }, callback)

    fun trash(callback: (Result<List<TrashTask>>) -> Unit) = execute({ dao.trashTasks() }, callback)

    fun restoreTask(id: String, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()
        val now = System.currentTimeMillis()
        val restored = transaction {
            val task = requireNotNull(dao.findIncludingDeleted(id)) { "Công việc không còn trong thùng rác." }
            check(task.deletedAt != null) { "Công việc này chưa bị xóa." }
            requireWrite(task.categoryId, uid)
            dao.restoreCategory(task.categoryId, now)
            dao.restoreTask(id, now)
            dao.restoreSubtasks(id, now)
            dao.restoreReminders(id, !task.isDone && NotificationPreferences.isEnabled(appContext), now, now)
            requireNotNull(dao.find(id))
        }
        dao.findReminder(id, uid)?.takeIf { it.isEnabled }?.let {
            TaskReminderScheduler.schedule(appContext, restored.id, restored.title, it.remindAt)
        }
    }, callback)

    fun permanentlyDeleteTask(id: String, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()
        val task = transaction { permanentlyDeleteInTransaction(id, uid) }
        TaskReminderScheduler.cancel(appContext, task.id, task.title)
    }, callback)

    fun permanentlyDeleteAll(callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()
        val removed = transaction { dao.trashTasks().map { permanentlyDeleteInTransaction(it.id, uid) } }
        removed.forEach { TaskReminderScheduler.cancel(appContext, it.id, it.title) }
    }, callback)

    private fun permanentlyDeleteInTransaction(id: String, uid: String): TaskEntity {
        val task = requireNotNull(dao.findIncludingDeleted(id)) { "Công việc không còn trong thùng rác." }
        check(task.deletedAt != null) { "Chỉ có thể xóa vĩnh viễn công việc trong thùng rác." }
        requireWrite(task.categoryId, uid)
        val now = System.currentTimeMillis()
        dao.removeQueuedTaskOperations(task.id, task.categoryId)
        dao.enqueueSyncOperation(SyncQueueEntity(
            operationId = UUID.randomUUID().toString(), actorUid = uid, categoryId = task.categoryId,
            entityType = "TASK", entityId = task.id, action = "DELETE",
            baseRevision = task.revision, clientOccurredAt = now
        ))
        dao.permanentlyDeleteTask(task.id)
        return task
    }

    fun addSubtask(taskId: String, title: String, callback: (Result<Unit>) -> Unit) = execute({
        val normalized = title.trim()
        require(normalized.length in 1..200) { "Tên công việc con cần từ 1 đến 200 ký tự." }
        val uid = dao.guestUid()
        transaction {
            val task = requireNotNull(dao.find(taskId)) { "Công việc không còn tồn tại." }
            requireWrite(task.categoryId, uid)
            val now = System.currentTimeMillis()
            dao.insertSubtask(SubtaskEntity(UUID.randomUUID().toString(), taskId, normalized,
                sortOrder = dao.nextSubtaskOrder(taskId), clientUpdatedAt = now))
        }
    }, callback)

    fun updateSubtask(id: String, title: String, isDone: Boolean, callback: (Result<Unit>) -> Unit) = execute({
        val normalized = title.trim()
        require(normalized.length in 1..200) { "Tên công việc con cần từ 1 đến 200 ký tự." }
        val uid = dao.guestUid()
        transaction {
            val old = requireNotNull(dao.findSubtask(id)) { "Công việc con không còn tồn tại." }
            val task = requireNotNull(dao.find(old.taskId)) { "Công việc không còn tồn tại." }
            requireWrite(task.categoryId, uid)
            dao.updateSubtask(old.copy(title = normalized, isDone = isDone,
                clientUpdatedAt = maxOf(System.currentTimeMillis(), old.clientUpdatedAt + 1)))
        }
    }, callback)

    fun deleteSubtask(id: String, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()
        transaction {
            val old = requireNotNull(dao.findSubtask(id)) { "Công việc con không còn tồn tại." }
            val task = requireNotNull(dao.find(old.taskId)) { "Công việc không còn tồn tại." }
            requireWrite(task.categoryId, uid)
            val now = System.currentTimeMillis()
            dao.softDeleteSubtask(id, now, now)
        }
    }, callback)

    fun createCategory(name: String, callback: (Result<String>) -> Unit) = execute({
        val normalized = name.trim()
        require(normalized.length in 1..100) { "Tên danh mục cần từ 1 đến 100 ký tự." }
        val uid = dao.guestUid()
        val key = GuestBootstrap.nameKey(normalized)
        transaction {
            check(dao.categoryId(uid, key) == null) { "Danh mục này đã tồn tại." }
            GuestBootstrap.ensureCategory(database.openHelper.writableDatabase, uid, normalized, System.currentTimeMillis())
        }
        normalized
    }, callback)

    fun removeReminders(taskIds: Set<String>, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()
        taskIds.forEach { taskId ->
            val task = dao.find(taskId)
            val now = System.currentTimeMillis()
            dao.softDeleteReminder(taskId, uid, now, now)
            task?.let { TaskReminderScheduler.cancel(appContext, it.id, it.title) }
        }
    }, callback)

    fun save(
        id: String?,
        title: String,
        description: String,
        category: String,
        priority: Int,
        dueAt: Long?,
        callback: (Result<Unit>) -> Unit
    ) = saveTask(id, title, description, category, priority, dueAt, 0L, true, null, callback)

    fun create(
        title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, callback: (Result<Unit>) -> Unit
    ) = create(title, description, category, priority, dueAt, reminderMinutes, "NONE", callback)

    fun create(
        title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, repeatType: String, callback: (Result<Unit>) -> Unit
    ) = saveTask(null, title, description, category, priority, dueAt, reminderMinutes, false, repeatType, callback)

    fun updateWithReminder(
        id: String, title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, callback: (Result<Unit>) -> Unit
    ) = saveTask(id, title, description, category, priority, dueAt, reminderMinutes, false, null, callback)

    fun updateTask(
        id: String, title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, reminderChanged: Boolean, repeatType: String,
        callback: (Result<Unit>) -> Unit
    ) = saveTask(id, title, description, category, priority, dueAt, reminderMinutes,
        !reminderChanged, repeatType, callback)

    private fun saveTask(
        id: String?, title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, preserveReminder: Boolean, repeatType: String?, callback: (Result<Unit>) -> Unit
    ) = execute({
        require(reminderMinutes == null || reminderMinutes in listOf(0L, 5L, 15L, 30L, 60L, 1440L))
        require(title.trim().length in 1..200) {
            "Tiêu đề cần từ 1 đến 200 ký tự."
        }
        require(priority in 1..3)
        require(category.isNotBlank())
        require(repeatType == null || repeatType in setOf("NONE", "DAILY", "WEEKLY", "MONTHLY"))
        require(repeatType == null || repeatType == "NONE" || dueAt != null) { "Cần chọn ngày hạn để lặp công việc." }

        val uid = dao.guestUid()
        val previous = id?.let { dao.find(it) }
        val previousReminder = id?.let { dao.findReminder(it, uid) }
        val offsetMillis = if (preserveReminder && previous != null) {
            previousReminder?.let { (previous.dueAt ?: it.remindAt) - it.remindAt }
        } else reminderMinutes?.times(60_000L)

        check(uid.startsWith("local:")) {
            "This repository is only for the local workspace."
        }

        val savedTask = transaction {
            // DBML requires category_id. Lazily create the unclassified workspace bucket.
            if (category == TaskDefaults.UNCATEGORIZED) {
                GuestBootstrap.ensureCategory(database.openHelper.writableDatabase, uid, category, System.currentTimeMillis())
            }
            val categoryId = requireNotNull(
                dao.categoryId(uid, GuestBootstrap.nameKey(category))
            )

            requireWrite(categoryId, uid)

            val old = id?.let {
                dao.find(it) ?: error("Công việc không còn tồn tại.")
            }

            old?.let {
                requireWrite(it.categoryId, uid)
            }

            val now = System.currentTimeMillis()

            val task = old?.copy(
                title = title.trim(),
                description = description.trim(),
                categoryId = categoryId,
                priority = priority,
                dueAt = dueAt,
                repeatType = repeatType ?: old.repeatType,
                repeatAnchorDay = if (repeatType == "MONTHLY") dueAt?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).dayOfMonth
                } else if (repeatType == null) old.repeatAnchorDay else null,
                clientUpdatedAt = maxOf(now, old.clientUpdatedAt + 1)
            ) ?: TaskEntity(
                id = UUID.randomUUID().toString(),
                categoryId = categoryId,
                createdByUid = uid,
                title = title.trim(),
                description = description.trim(),
                priority = priority,
                dueAt = dueAt,
                repeatType = repeatType ?: "NONE",
                repeatAnchorDay = if (repeatType == "MONTHLY") dueAt?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).dayOfMonth
                } else null,
                createdAt = now,
                clientUpdatedAt = now
            )

            if (old == null) {
                dao.insert(task)
            } else {
                dao.update(task)
            }

            val remindAt = task.dueAt?.let { due -> offsetMillis?.let { due - it } }
            if (remindAt != null && remindAt > now) {
                dao.saveReminder(TaskReminderEntity(
                    taskId = task.id, userUid = uid, remindAt = remindAt,
                    isEnabled = !task.isDone && NotificationPreferences.isEnabled(appContext),
                    clientUpdatedAt = now
                ))
            } else {
                dao.softDeleteReminder(task.id, uid, now, now)
            }

            task
        }

        // The task and reminder commit together; scheduling follows the stored preference.
        val reminder = dao.findReminder(savedTask.id, uid)
        if (reminder?.isEnabled == true) {
            TaskReminderScheduler.cancel(appContext, savedTask.id, savedTask.title)
            TaskReminderScheduler.schedule(appContext, savedTask.id, savedTask.title, reminder.remindAt)
        } else {
            TaskReminderScheduler.cancel(appContext, savedTask.id, savedTask.title)
        }

    }, callback)

    fun toggle(id: String, callback: (Result<Unit>) -> Unit) = execute({

        val uid = dao.guestUid()
        val now = System.currentTimeMillis()

        val notificationsEnabled =
            NotificationPreferences.isEnabled(appContext)

        var nextRepeat: Pair<TaskEntity, TaskReminderEntity?>? = null
        val updatedTask = transaction {
            val task = dao.find(id)
                ?: error("Công việc không còn tồn tại.")

            requireWrite(task.categoryId, uid)

            val newIsDone = !task.isDone

            val updated = task.copy(
                isDone = newIsDone,
                completedAt = if (newIsDone) now else null,
                clientUpdatedAt = maxOf(now, task.clientUpdatedAt + 1)
            )

            dao.update(updated)

            dao.findReminder(id, uid)?.let { reminder ->
                dao.saveReminder(reminder.copy(
                    isEnabled = !newIsDone && notificationsEnabled && reminder.remindAt > now,
                    clientUpdatedAt = now
                ))
            }

            if (newIsDone && task.repeatType != "NONE" && task.dueAt != null) {
                val nextDue = nextDueAt(task, now)
                val next = task.copy(
                    id = UUID.randomUUID().toString(), dueAt = nextDue, isDone = false,
                    completedAt = null, createdAt = now, clientUpdatedAt = now,
                    repeatAnchorDay = task.repeatAnchorDay ?: Instant.ofEpochMilli(task.dueAt)
                        .atZone(ZoneId.of(task.timezone)).dayOfMonth
                )
                dao.insert(next)
                dao.subtasks(task.id).forEachIndexed { index, subtask ->
                    dao.insertSubtask(SubtaskEntity(
                        id = UUID.randomUUID().toString(), taskId = next.id, title = subtask.title,
                        sortOrder = index, clientUpdatedAt = now
                    ))
                }
                val oldReminder = dao.findReminder(task.id, uid)
                val nextReminder = oldReminder?.let { reminder ->
                    val offset = (task.dueAt - reminder.remindAt).coerceAtLeast(0L)
                    val remindAt = nextDue - offset
                    if (remindAt > now) TaskReminderEntity(
                        taskId = next.id, userUid = uid, remindAt = remindAt,
                        isEnabled = notificationsEnabled, clientUpdatedAt = now
                    ).also(dao::saveReminder) else null
                }
                nextRepeat = next to nextReminder
            }

            updated
        }

        val reminder = dao.findReminder(id, uid)
        if (!updatedTask.isDone && reminder != null && reminder.isEnabled && reminder.remindAt > now) {
            TaskReminderScheduler.schedule(
                appContext,
                updatedTask.id,
                updatedTask.title,
                reminder.remindAt
            )
        } else {
            TaskReminderScheduler.cancel(
                appContext,
                updatedTask.id,
                updatedTask.title
            )
        }
        nextRepeat?.let { (next, reminderForNext) ->
            reminderForNext?.takeIf { it.isEnabled }?.let {
                TaskReminderScheduler.schedule(appContext, next.id, next.title, it.remindAt)
            }
        }

    }, callback)

    private fun nextDueAt(task: TaskEntity, after: Long): Long {
        val zone = runCatching { ZoneId.of(task.timezone) }.getOrDefault(ZoneId.of("Asia/Ho_Chi_Minh"))
        val due = Instant.ofEpochMilli(requireNotNull(task.dueAt)).atZone(zone)
        val anchorDay = task.repeatAnchorDay ?: due.dayOfMonth
        var next = due
        do {
            next = when (task.repeatType) {
                "DAILY" -> next.plusDays(1)
                "WEEKLY" -> next.plusWeeks(1)
                "MONTHLY" -> {
                    val month = next.plusMonths(1)
                    month.withDayOfMonth(minOf(anchorDay, month.toLocalDate().lengthOfMonth()))
                }
                else -> error("Lịch lặp không hợp lệ.")
            }
        } while (next.toInstant().toEpochMilli() <= after)
        return next.toInstant().toEpochMilli()
    }

    fun delete(id: String, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()

        val deletedTask = transaction {
            val task = dao.find(id)
                ?: error("Công việc không còn tồn tại.")

            requireWrite(task.categoryId, uid)

            val now = System.currentTimeMillis()
            dao.softDeleteTask(id, now, now)
            dao.softDeleteSubtasks(id, now, now)
            dao.softDeleteRemindersForTask(id, now, now)

            task
        }

        TaskReminderScheduler.cancel(
            appContext,
            deletedTask.id,
            deletedTask.title
        )

    }, callback)

    fun setNotificationsEnabled(
        enabled: Boolean,
        callback: (Result<Unit>) -> Unit
    ) = execute({

        val uid = dao.guestUid()
        val now = System.currentTimeMillis()

        val tasksToProcess = transaction {
            dao.getAllReminders(uid).mapNotNull { reminder ->

                val task = dao.find(reminder.taskId)

                if (task == null) {
                    val now = System.currentTimeMillis()
                    dao.softDeleteReminder(reminder.taskId, uid, now, now)
                    null
                } else {
                    val shouldEnable = enabled &&
                            !task.isDone &&
                            task.dueAt != null &&
                            reminder.remindAt > now

                    dao.updateReminderStatus(
                        taskId = task.id,
                        userUid = uid,
                        enabled = shouldEnable,
                        updatedAt = now
                    )

                    Triple(task, reminder.remindAt, shouldEnable)
                }
            }
        }

        tasksToProcess.forEach { (task, remindAt, shouldEnable) ->

            if (shouldEnable) {
                val scheduled = TaskReminderScheduler.schedule(
                    appContext,
                    task.id,
                    task.title,
                    remindAt
                )

                if (!scheduled) {
                    transaction {
                        dao.updateReminderStatus(
                            taskId = task.id,
                            userUid = uid,
                            enabled = false,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
            } else {
                TaskReminderScheduler.cancel(
                    appContext,
                    task.id,
                    task.title
                )
            }
        }

    }, callback)

    private fun requireWrite(categoryId: String, uid: String) {
        check(uid.startsWith("local:") && dao.role(categoryId, uid) in listOf("OWNER", "EDITOR")) { "Không có quyền sửa danh mục." }
    }

    private fun <T> transaction(work: () -> T): T = database.runInTransaction(Callable(work))

    private fun <T> execute(work: () -> T, callback: (Result<T>) -> Unit) {
        executor.execute { val result = runCatching(work); dispatch { callback(result) } }
    }

    companion object {
        @Volatile private var instance: GuestTaskRepository? = null
        fun get(context: Context): GuestTaskRepository = instance ?: synchronized(this) {
            instance ?: GuestTaskRepository(
                context.applicationContext,
                AppDatabase.guestBuilder(context).build(),
                Executors.newSingleThreadExecutor(), { action -> Handler(Looper.getMainLooper()).post { action() }; Unit })
                .also { instance = it }
        }
    }
}
