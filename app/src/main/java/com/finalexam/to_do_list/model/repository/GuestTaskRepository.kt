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
import com.finalexam.to_do_list.notification.TaskReminderScheduler
import com.finalexam.to_do_list.notification.NotificationPreferences
import com.finalexam.to_do_list.util.TaskDefaults

/** Serial database operations off the UI thread. No network dependency. */
class GuestTaskRepository internal constructor(
    private val appContext: Context,
    private val database: AppDatabase,
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit
) {
    private val dao get() = database.tasks()

    fun load(callback: (Result<List<HomeTask>>) -> Unit) = execute({ dao.homeTasks(dao.guestUid()) }, callback)

    fun save(
        id: String?,
        title: String,
        description: String,
        category: String,
        priority: Int,
        dueAt: Long?,
        callback: (Result<Unit>) -> Unit
    ) = saveTask(id, title, description, category, priority, dueAt, 0L, true, callback)

    fun create(
        title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, callback: (Result<Unit>) -> Unit
    ) = saveTask(null, title, description, category, priority, dueAt, reminderMinutes, false, callback)

    fun updateWithReminder(
        id: String, title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, callback: (Result<Unit>) -> Unit
    ) = saveTask(id, title, description, category, priority, dueAt, reminderMinutes, false, callback)

    private fun saveTask(
        id: String?, title: String, description: String, category: String, priority: Int,
        dueAt: Long?, reminderMinutes: Long?, preserveReminder: Boolean, callback: (Result<Unit>) -> Unit
    ) = execute({
        require(reminderMinutes == null || reminderMinutes in listOf(0L, 5L, 15L, 30L, 60L, 1440L))
        require(title.trim().length in 1..200) {
            "Tiêu đề cần từ 1 đến 200 ký tự."
        }
        require(priority in 1..3)
        require(category in TaskDefaults.categories)

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
                clientUpdatedAt = maxOf(now, old.clientUpdatedAt + 1)
            ) ?: TaskEntity(
                id = UUID.randomUUID().toString(),
                categoryId = categoryId,
                createdByUid = uid,
                title = title.trim(),
                description = description.trim(),
                priority = priority,
                dueAt = dueAt,
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
                dao.deleteReminder(task.id, uid)
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

    }, callback)

    fun delete(id: String, callback: (Result<Unit>) -> Unit) = execute({
        val uid = dao.guestUid()

        val deletedTask = transaction {
            val task = dao.find(id)
                ?: error("Công việc không còn tồn tại.")

            requireWrite(task.categoryId, uid)

            dao.deleteReminder(id, uid)
            dao.delete(id)

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
                    dao.deleteReminder(reminder.taskId, uid)
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
