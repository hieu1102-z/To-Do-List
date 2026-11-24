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
import java.util.concurrent.locks.ReentrantLock

internal class WorkspaceRemote(app: FirebaseApp, uid: String, private val active: () -> Boolean) {
    private val http = workspaceHttp(app, uid, active)
    fun call(data: Row): Row {
        check(active()) { "Phiên đăng nhập đã thay đổi." }
        return http.call(data)
    }

    fun pages(kind: String, fields: Row = emptyMap()): List<Row> = buildList {
        var cursor: String? = null
        do {
            val page = call(fields + mapOf("kind" to kind, "cursor" to cursor))
            addAll(page.rowList("items"))
            cursor = page["next"] as String?
        } while (cursor != null)
    }

    fun snapshot(categoryId: String): WorkspaceSnapshot = snapshot(categoryId, snapshotGroups.keys)

    fun members(categoryId: String): WorkspaceMembers {
        val snapshot = snapshot(categoryId, listOf("MEMBER"))
        val profiles = snapshot.profiles.associate { it.string("uid") to it.string("full_name") }
        val members = snapshot.groups.getValue("MEMBER").map { row ->
            val uid = row.string("user_uid")
            WorkspaceMember(uid, profiles[uid].orEmpty(), row.string("role"), row.long("revision"))
        }
        return WorkspaceMembers(snapshot.role, members, synced = true)
    }

    private fun snapshot(categoryId: String, sections: Collection<String>): WorkspaceSnapshot {
        var category: Row = emptyMap()
        var seq: Long? = null
        var role = ""
        val groups = linkedMapOf<String, List<Row>>()
        val profiles = linkedMapOf<String, Row>()
        for (section in sections) {
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
    private var watchedSession: AccountSession? = null
    private val listeners = linkedMapOf<String, ListenerRegistration>()
    private var firestore: FirebaseFirestore? = null
    private val listenerHandler = Handler(Looper.getMainLooper())
    private var listenerKick: Runnable? = null

    internal fun app(context: Context) = FirebaseApp.getApps(context).find { it.name == FirebaseApp.DEFAULT_APP_NAME }
    internal fun current(context: Context) = AccountSession.current(context)?.uid
    private fun name(uid: String) = AppDatabase.accountFileName(uid)

    fun start(context: Context, uid: String) {
        if (!configured || current(context) != uid) return
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
        if (!configured) return
        val session = AccountSession.current(context)?.takeIf { it.uid == uid } ?: return
        if (watchedSession !== session) {
            unwatch()
            watchedSession = session
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
                if (AccountSession.current(context) === session && (error != null || snapshot?.metadata?.isFromCache == false)) {
                    listenerKick?.let(listenerHandler::removeCallbacks)
                    listenerKick = Runnable { if (AccountSession.current(context) === session) request(context, uid) }.also { listenerHandler.postDelayed(it, 250) }
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
        watchedSession = null
    }

    internal fun cancelCache(context: Context, databaseName: String, purge: Boolean = false) {
        val work = WorkManager.getInstance(context)
        val sync = work.cancelUniqueWork("sync-" + databaseName)
        val periodic = work.cancelUniqueWork("periodic-" + databaseName)
        if (purge) {
            sync.result.get(10, TimeUnit.SECONDS)
            periodic.result.get(10, TimeUnit.SECONDS)
            work.pruneWork().result.get(10, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun signOut(context: Context, uid: String) {
        unwatch()
        firestore?.terminate()
        firestore = null
        cancelCache(context, name(uid))
        AccountReminders.cancel(context, uid)
        changed()
    }
}


class WorkspaceSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val session = AccountSession.restore(applicationContext)?.takeIf { it.uid == uid } ?: return Result.success()
        if (!lock.tryLock()) return Result.retry()
        return try { sync(session) } finally { lock.unlock() }
    }

    private fun sync(session: AccountSession): Result {
        val context = applicationContext
        val uid = session.uid
        fun active() = !isStopped && AccountSession.current(context) === session
        if (!WorkspaceSync.configured || !active()) return Result.success()
        val app = WorkspaceSync.app(context) ?: return Result.success()
        val remote = WorkspaceRemote(app, uid, ::active)
        var retry = false
        try {
            val profile = remote.call(mapOf("kind" to "PROFILE", "operation_id" to uuid()))
            check(profile["ok"] == true)
            val blocked = mutableSetOf<String>()
            for (index in 0 until 100) {
                if (!active()) return Result.success()
                val command = AccountSession.access(context, session) {
                    transaction {
                        val queue = commands()
                        val failedDefaults = queue.filter { it["status"] == "REJECTED" &&
                            envelope(it)["kind"] == "ENSURE_DEFAULT_CATEGORY" }.map { it.string("category_id") }
                        blocked.addAll(failedDefaults)
                        queue.firstOrNull { it["status"] != "REJECTED" && it["category_id"] !in blocked }?.also {
                            if (it.long("next_retry_at") <= System.currentTimeMillis()) sending(it.string("operation_id"))
                        }
                    }
                } ?: break
                val cid = command.string("category_id")
                if (command.long("next_retry_at") > System.currentTimeMillis()) {
                    blocked += cid
                    retry = true
                    continue
                }
                try {
                    val envelope = AccountSession.access(context, session) { envelope(command) }
                    val response = remote.call(envelope)
                    if (!active()) return Result.success()
                    if (response["pending"] == true) {
                        blocked += cid
                        retry = true
                    } else AccountSession.access(context, session) { acknowledge(command, response) }
                } catch (error: Exception) {
                    if (!active()) return Result.success()
                    val code = error.businessCode()
                    val ensureDefault = AccountSession.access(context, session) { envelope(command)["kind"] == "ENSURE_DEFAULT_CATEGORY" }
                    // Keep drafts on an older backend while it is being upgraded.
                    val transient = error.isTransientWorkspaceFailure || ensureDefault && code == "INVALID_ARGUMENT"
                    AccountSession.access(context, session) { failed(command, code, transient) }
                    if (transient || ensureDefault) blocked += cid
                    retry = retry || transient
                }
            }
            val remoteIds = remote.pages("LIST", mapOf("include_default" to true)).map { it.string("id") }.toSet()
            val (localIds, creating) = AccountSession.access(context, session) {
                sql.rows("SELECT id FROM categories").map { it.string("id") }.toSet() to
                    commands().filter { it["entity_type"] == "CATEGORY" && it["action"] == "CREATE" }
                        .map { it.string("category_id") }.toSet()
            }
            // Confirm missing access with a scoped point read.
            for (cid in (localIds + remoteIds) - creating) {
                if (!active()) return Result.success()
                try {
                    val snapshot = remote.snapshot(cid)
                    if (!active()) return Result.success()
                    AccountSession.access(context, session) { apply(snapshot) }
                } catch (error: Exception) {
                    if (!active()) return Result.success()
                    if (error.businessCode() == "PERMISSION_DENIED" && cid != defaultCategoryId(uid)) {
                        AccountSession.access(context, session) { dropCategory(cid) }
                    } else retry = true
                }
            }
            retry = retry || AccountSession.access(context, session) {
                val queue = commands()
                val rejectedDefault = queue.any { it["status"] == "REJECTED" && envelope(it)["kind"] == "ENSURE_DEFAULT_CATEGORY" }
                queue.any { it["status"] != "REJECTED" && !(rejectedDefault && it["category_id"] == defaultCategoryId(uid)) }
            }
            AccountSession.access(context, session) { AccountReminders.reconcile(context, this) }
        } catch (error: Exception) {
            retry = active()
            android.util.Log.w("WorkspaceSync", "Sync incomplete: " + error.businessCode())
        } finally {
            WorkspaceSync.changed()
        }
        return if (retry && active()) Result.retry() else Result.success()
    }

    private companion object {
        val lock = ReentrantLock()
    }
}
