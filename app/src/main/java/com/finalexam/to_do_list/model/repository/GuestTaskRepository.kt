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

/** Serial database operations off the UI thread. No network dependency. */
class GuestTaskRepository internal constructor(
    private val database: AppDatabase,
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit
) {
    private val dao get() = database.tasks()

    fun load(callback: (Result<List<HomeTask>>) -> Unit) = execute({ dao.homeTasks(dao.guestUid()) }, callback)

    fun save(id: String?, title: String, description: String, category: String, priority: Int, dueAt: Long?, callback: (Result<Unit>) -> Unit) = execute({
        require(title.trim().length in 1..200) { "Tiêu đề cần từ 1 đến 200 ký tự." }
        require(priority in 1..3)
        require(category in listOf("Cá nhân", "Học tập", "Công việc", "Mua sắm"))
        transaction {
            val uid = dao.guestUid()
            check(uid.startsWith("local:")) { "This repository is only for the local workspace." }
            val categoryId = requireNotNull(dao.categoryId(uid, GuestBootstrap.nameKey(category)))
            requireWrite(categoryId, uid)
            val old = id?.let { dao.find(it) ?: error("Công việc không còn tồn tại.") }
            old?.let { requireWrite(it.categoryId, uid) }
            val now = System.currentTimeMillis()
            val task = old?.copy(title = title.trim(), description = description.trim(), categoryId = categoryId,
                priority = priority, dueAt = dueAt, clientUpdatedAt = maxOf(now, old.clientUpdatedAt + 1))
                ?: TaskEntity(id = UUID.randomUUID().toString(), categoryId = categoryId, createdByUid = uid,
                    title = title.trim(), description = description.trim(), priority = priority, dueAt = dueAt,
                    createdAt = now, clientUpdatedAt = now)
            if (old == null) dao.insert(task) else dao.update(task)
        }
    }, callback)

    fun toggle(id: String, callback: (Result<Unit>) -> Unit) = execute({
        transaction {
            val task = dao.find(id) ?: error("Công việc không còn tồn tại.")
            requireWrite(task.categoryId, dao.guestUid())
            val now = System.currentTimeMillis()
            dao.update(task.copy(isDone = !task.isDone, completedAt = if (!task.isDone) now else null,
                clientUpdatedAt = maxOf(now, task.clientUpdatedAt + 1)))
        }
    }, callback)

    fun delete(id: String, callback: (Result<Unit>) -> Unit) = execute({
        transaction {
            val task = dao.find(id) ?: error("Công việc không còn tồn tại.")
            requireWrite(task.categoryId, dao.guestUid())
            dao.delete(id)
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
            instance ?: GuestTaskRepository(AppDatabase.guestBuilder(context).build(),
                Executors.newSingleThreadExecutor(), { action -> Handler(Looper.getMainLooper()).post { action() }; Unit })
                .also { instance = it }
        }
    }
}
