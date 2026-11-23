package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.notification.AccountReminders
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal class WorkspaceRemote(app: FirebaseApp, uid: String) {
    private val http = workspaceHttp(app, uid)
    fun call(data: Row): Row = http.call(data)

    fun pages(kind: String, fields: Row = emptyMap()): List<Row> = buildList {
        var cursor: String? = null
        do {
            val page = call(fields + mapOf("kind" to kind, "cursor" to cursor))
            addAll(page.rowList("items"))
            cursor = page["next"] as String?
        } while (cursor != null)
    }

    fun snapshot(categoryId: String): WorkspaceSnapshot {
        var category: Row = emptyMap()
        var seq: Long? = null
        var role = ""
        val groups = linkedMapOf<String, List<Row>>()
        val profiles = linkedMapOf<String, Row>()
        for (section in snapshotGroups.keys) {
            val rows = mutableListOf<Row>()
            var cursor: String? = null
            do {
                val page = call(mapOf("kind" to "SNAPSHOT", "category_id" to categoryId,
                    "section" to section, "seq" to seq, "cursor" to cursor))
                category = page["category"].asRow()
                role = page.string("role")
                seq = page.long("seq")
                rows += page.rowList("rows")
                page.rowList("profiles").forEach { profiles[it.string("uid")] = it }
                cursor = page["next"] as String?
            } while (cursor != null)
            groups[section] = rows
        }
        return WorkspaceSnapshot(category, role, checkNotNull(seq), groups, profiles.values.toList())
    }
}

internal fun Any?.asRow(): Row {
    val map = this as? Map<*, *> ?: error("Invalid workspace response")
    return map.entries.associate { (key, value) -> (key as String) to value }
}
internal fun Row.rowList(key: String): List<Row> = (get(key) as? List<*>)?.map { it.asRow() }.orEmpty()
object WorkspaceSync {
    internal val configured get() = WorkspaceApiConfig.configured
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    internal val changes = MutableStateFlow(0L)
    internal fun changed() { synchronized(changes) { changes.value++ } }
    private var watchedUid: String? = null
    private val listeners = linkedMapOf<String, ListenerRegistration>()
    private var firestore: FirebaseFirestore? = null
    private val listenerHandler = Handler(Looper.getMainLooper())
    private var listenerKick: Runnable? = null

    internal fun app(context: Context) = FirebaseApp.getApps(context).find { it.name == FirebaseApp.DEFAULT_APP_NAME }
    internal fun current(context: Context) = AuthRepository.get(context).user?.uid
    private fun name(uid: String) = AppDatabase.accountFileName(uid)

    fun start(context: Context, uid: String) {
        if (!configured) return
        val request = PeriodicWorkRequestBuilder<WorkspaceSyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("uid" to uid)).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic-" + name(uid),
            ExistingPeriodicWorkPolicy.KEEP, request)
        request(context, uid, enqueueBehindActive = false)
    }

    fun request(context: Context, uid: String, enqueueBehindActive: Boolean = true) {
        if (!configured || current(context) != uid) return
        val request = OneTimeWorkRequestBuilder<WorkspaceSyncWorker>().setInputData(workDataOf("uid" to uid))
            .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-" + name(uid),
            if (enqueueBehindActive) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    @Synchronized
    fun watch(context: Context, uid: String, categoryIds: Set<String>) {
        if (!configured || current(context) != uid) return
        if (watchedUid != uid) {
            unwatch()
            watchedUid = uid
        }
        val app = app(context) ?: return
        val db = firestore ?: FirebaseFirestore.getInstance(app).also {
            it.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            firestore = it
        }
        val paths = categoryIds.map { "categories/$it" }.toSet() + "accountSignals/$uid"
        (listeners.keys - paths).forEach { listeners.remove(it)?.remove() }
        for (path in paths - listeners.keys) {
            listeners[path] = db.document(path).addSnapshotListener { snapshot, error ->
                if (current(context) == uid && (error != null || snapshot?.metadata?.isFromCache == false)) {
                    listenerKick?.let(listenerHandler::removeCallbacks)
                    listenerKick = Runnable { request(context, uid) }.also { listenerHandler.postDelayed(it, 250) }
                }
            }
        }
    }

    @Synchronized
    fun unwatch() {
        listenerKick?.let(listenerHandler::removeCallbacks)
        listenerKick = null
        listeners.values.forEach { it.remove() }
        listeners.clear()
        watchedUid = null
    }

    fun signOut(context: Context, uid: String) {
        unwatch()
        WorkManager.getInstance(context).run {
            cancelUniqueWork("sync-" + name(uid))
            cancelUniqueWork("periodic-" + name(uid))
        }
        AccountReminders.cancel(context, uid)
        changed()
    }
}

class WorkspaceSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val lock = locks.computeIfAbsent(uid) { ReentrantLock() }
        if (!lock.tryLock()) return Result.retry()
        return try { sync(uid) } finally { lock.unlock() }
    }

    private fun sync(uid: String): Result {
        val context = applicationContext
        fun active() = !isStopped && WorkspaceSync.current(context) == uid
        if (!WorkspaceSync.configured || !active()) return Result.success()
        val user = AuthRepository.get(context).user?.takeIf { it.uid == uid } ?: return Result.success()
        val app = WorkspaceSync.app(context) ?: return Result.success()
        val remote = WorkspaceRemote(app, uid)
        var retry = false
        try {
            AccountWorkspace(uid, AppDatabase.accountBuilder(context, uid).build()).use { store ->
                store.database.users().upsert(user.toWorkspaceProfile())
                val profile = remote.call(mapOf("kind" to "PROFILE", "operation_id" to uuid()))
                check(profile["ok"] == true)
                val blocked = mutableSetOf<String>()
                for (index in 0 until 100) {
                    if (!active()) return Result.success()
                    val queue = store.commands()
                    val command = queue.firstOrNull {
                        it["status"] != "REJECTED" && it["category_id"] !in blocked
                    } ?: break
                    val cid = command.string("category_id")
                    if (command.long("next_retry_at") > System.currentTimeMillis()) {
                        blocked += cid
                        retry = true
                        continue
                    }
                    // Freeze the payload in the same transaction that claims it.
                    val id = command.string("operation_id")
                    val claimed = store.transaction {
                        store.commands().firstOrNull { it["operation_id"] == id && it["status"] != "REJECTED" }
                            ?.also { store.sending(id) }
                    } ?: continue
                    try {
                        val response = remote.call(store.envelope(claimed))
                        if (!active()) return Result.success()
                        if (response["pending"] == true) {
                            blocked += cid
                            retry = true
                        } else store.acknowledge(claimed, response)
                    } catch (error: Exception) {
                        if (!active()) return Result.success()
                        val code = error.businessCode()
                        val transient = error.isTransientWorkspaceFailure
                        store.failed(claimed, code, transient)
                        if (transient) { blocked += cid; retry = true }
                    }
                }
                if (!active()) return Result.success()
                val remoteIds = remote.pages("LIST").map { it.string("id") }.toSet()
                if (!active()) return Result.success()
                val localIds = store.sql.rows("SELECT id FROM categories").map { it.string("id") }.toSet()
                val creating = store.commands().filter { it["entity_type"] == "CATEGORY" && it["action"] == "CREATE" }
                    .map { it.string("category_id") }.toSet()
                // Confirm missing access with a scoped point read. A changing paginated
                // discovery list alone is not evidence that a category was removed.
                for (cid in (localIds - creating) + remoteIds) {
                    if (!active()) return Result.success()
                    try {
                        val snapshot = remote.snapshot(cid)
                        if (!active()) return Result.success()
                        store.apply(snapshot)
                    } catch (error: Exception) {
                        if (!active()) return Result.success()
                        if (error.businessCode() == "PERMISSION_DENIED") store.dropCategory(cid)
                        else retry = true
                    }
                }
                if (active()) AccountReminders.reconcile(context, store)
                retry = retry || store.commands().any { it["status"] != "REJECTED" }
            }
        } catch (error: Exception) {
            retry = active()
            android.util.Log.w("WorkspaceSync", "Sync incomplete: " + error.businessCode())
        } finally {
            WorkspaceSync.changed()
        }
        return if (retry && active()) Result.retry() else Result.success()
    }

    private companion object {
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
