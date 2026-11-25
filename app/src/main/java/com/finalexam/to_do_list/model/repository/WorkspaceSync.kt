package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.notification.AccountReminders
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class WorkspaceRemote(app: FirebaseApp, uid: String, private val active: () -> Boolean) : WorkspaceSyncRemote {
    private val http = workspaceHttp(app, uid, active)
    override fun call(data: Row, forceRefresh: Boolean): Row {
        check(active()) { "Phiên đăng nhập đã thay đổi." }
        return http.call(data, forceRefresh)
    }

    override fun pages(kind: String, fields: Row): List<Row> = buildList {
        var cursor: String? = null
        do {
            val page = call(fields + mapOf("kind" to kind, "cursor" to cursor))
            addAll(page.rowList("items"))
            cursor = page["next"] as String?
        } while (cursor != null)
    }

    override fun snapshot(categoryId: String): WorkspaceSnapshot = snapshot(categoryId, snapshotGroups.keys)

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
                val pageSeq = page.optionalLong("seq") ?: throw WorkspaceRequestError("UNAVAILABLE")
                val pageRole = page.string("role")
                if (seq != null && (seq != pageSeq || role != pageRole)) {
                    throw WorkspaceRequestError("SNAPSHOT_CHANGED")
                }
                category = page["category"].asRow()
                role = pageRole
                seq = pageSeq
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val changes = MutableStateFlow(0L)
    internal fun changed() { synchronized(changes) { changes.value++ } }

    private var coordinator: Coordinator? = null
    private var observing = false
    private var firestore: FirebaseFirestore? = null

    internal fun app(context: Context) = FirebaseApp.getApps(context).find { it.name == FirebaseApp.DEFAULT_APP_NAME }
    internal fun current(context: Context) = AccountSession.current(context)?.uid
    private fun name(uid: String) = AppDatabase.accountFileName(uid)
    private fun syncName(databaseName: String) = "sync-v2-$databaseName"
    private fun periodicName(databaseName: String) = "periodic-v2-$databaseName"

    fun start(context: Context, uid: String) {
        val appContext = context.applicationContext
        val session = AccountSession.current(appContext)?.takeIf { it.uid == uid } ?: return
        scope.launch {
            getCoordinator(appContext, session)?.startWork()
        }
    }

    internal fun request(context: Context, session: AccountSession, discover: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch {
            val runner = getCoordinator(appContext, session) ?: return@launch
            runner.startWork()
            runner.submit(WorkspaceSyncDemand(discover = discover, upload = !discover))
            schedule(appContext, session, System.currentTimeMillis(), ExistingWorkPolicy.KEEP)
        }
    }

    internal fun profileChanged(context: Context, session: AccountSession) {
        val appContext = context.applicationContext
        scope.launch {
            getCoordinator(appContext, session)?.submit(WorkspaceSyncDemand(refreshProfile = true))
        }
    }

    private fun getCoordinator(context: Context, session: AccountSession): Coordinator? {
        if (!configured || AccountSession.current(context) !== session) return null
        coordinator?.takeIf { it.session === session }?.let { return it }
        val firebase = app(context) ?: return null
        coordinator?.close()
        val runner = Coordinator(context, session, firebase)
        coordinator = runner
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (!observing) {
            observing = true
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { coordinator?.enterForeground() }
                override fun onStop(owner: LifecycleOwner) { coordinator?.leaveForeground() }
            })
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) runner.enterForeground()
        return runner
    }

    // Only the running one-time worker appends a successor. Other callers request a single durable wake.
    internal fun schedule(context: Context, session: AccountSession, at: Long, policy: ExistingWorkPolicy) {
        if (AccountSession.current(context) !== session) return
        val request = OneTimeWorkRequestBuilder<WorkspaceSyncWorker>()
            .setInputData(workDataOf("uid" to session.uid, "generation" to 2))
            .setConstraints(network)
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(syncName(name(session.uid)), policy, request)
    }

    internal suspend fun runWorker(context: Context, session: AccountSession, periodic: Boolean): Long? =
        withContext(Dispatchers.Main.immediate) {
            val runner = getCoordinator(context, session) ?: return@withContext null
            val result = CompletableDeferred<Long?>()
            runner.submit(WorkspaceSyncDemand(repair = periodic, reconnected = true), result)
            try { result.await() } finally { runner.removeWaiter(result) }
        }

    internal fun cancelCache(context: Context, databaseName: String, purge: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        val cancellations = listOf("sync-$databaseName", "periodic-$databaseName",
            syncName(databaseName), periodicName(databaseName)).map(manager::cancelUniqueWork)
        if (purge) {
            cancellations.forEach { it.result.get(10, TimeUnit.SECONDS) }
            manager.pruneWork().result.get(10, TimeUnit.SECONDS)
        }
    }

    fun signOut(context: Context, uid: String) {
        cancelCache(context, name(uid))
        AccountReminders.cancel(context, uid)
        scope.launch {
            val old = coordinator
            if (old?.session?.uid == uid && AccountSession.current(context) !== old.session) {
                old.close()
                coordinator = null
                firestore?.terminate()
                firestore = null
            }
            changed()
        }
    }

    private fun firestore(app: FirebaseApp): FirebaseFirestore = firestore ?: FirebaseFirestore.getInstance(app).also {
        it.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        firestore = it
    }

    private class Coordinator(
        private val context: Context,
        val session: AccountSession,
        private val firebase: FirebaseApp
    ) {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private var foreground = false
        private var currentNetwork: Network? = null
        private var validated = false
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var runner: Job? = null
        private var timer: Job? = null
        private var watchTask: Job? = null
        private var watchDirty = false
        private var watchGeneration = 0
        private var signalTimer: Job? = null
        private var workStarted = false
        private var closed = false
        @Volatile private var uploadRequested = false
        private val inbox = WorkspaceSyncInbox()
        private var signals = WorkspaceSyncDemand()
        private val waiters = mutableListOf<CompletableDeferred<Long?>>()
        private val listeners = linkedMapOf<String, ListenerRegistration>()
        private var nextAt: Long? = null

        private val storage = object : WorkspaceSyncStorage {
            override fun <T> access(work: AccountWorkspace.() -> T) = AccountSession.access(context, session, work)
        }
        private val pass = WorkspaceSyncPass(storage, WorkspaceRemote(firebase, session.uid, ::active),
            ::active, ::internetAvailable, ::localChanged, { AccountReminders.reconcile(context, this) },
            { uploadRequested })

        private fun active() = AccountSession.current(context) === session
        private fun internetAvailable() = connectivity.getNetworkCapabilities(connectivity.activeNetwork).hasInternet()

        fun startWork() {
            if (workStarted) return
            workStarted = true
            val databaseName = name(session.uid)
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork("sync-$databaseName")
            manager.cancelUniqueWork("periodic-$databaseName")
            val periodic = PeriodicWorkRequestBuilder<WorkspaceSyncWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("uid" to session.uid, "generation" to 2, "periodic" to true))
                .setConstraints(network).setInitialDelay(15, TimeUnit.MINUTES).build()
            manager.enqueueUniquePeriodicWork(periodicName(databaseName), ExistingPeriodicWorkPolicy.KEEP, periodic)
            schedule(context, session, System.currentTimeMillis(), ExistingWorkPolicy.KEEP)
        }

        fun enterForeground() {
            if (foreground || closed || !active()) return
            foreground = true
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val callbackIdentity = this
                    scope.launch {
                        if (networkCallback !== callbackIdentity || !active()) return@launch
                        currentNetwork = network
                        setValidated(capabilities.hasInternet())
                    }
                }

                override fun onLost(network: Network) {
                    val callbackIdentity = this
                    scope.launch {
                        if (networkCallback !== callbackIdentity || network != currentNetwork) return@launch
                        currentNetwork = null
                        setValidated(false)
                    }
                }
            }
            networkCallback = callback
            connectivity.registerDefaultNetworkCallback(callback)
            currentNetwork = connectivity.activeNetwork
            if (connectivity.getNetworkCapabilities(currentNetwork).hasInternet()) setValidated(true)
            else submit(WorkspaceSyncDemand(discover = true))
            refreshWatches()
        }

        fun leaveForeground() {
            foreground = false
            networkCallback?.let(connectivity::unregisterNetworkCallback)
            networkCallback = null
            currentNetwork = null
            validated = false
            timer?.cancel()
            timer = null
            signalTimer?.cancel()
            signalTimer = null
            signals = WorkspaceSyncDemand()
            watchGeneration++
            watchDirty = false
            watchTask?.cancel()
            watchTask = null
            listeners.values.forEach(ListenerRegistration::remove)
            listeners.clear()
        }

        fun close() {
            closed = true
            leaveForeground()
            runner?.cancel()
            waiters.forEach { it.complete(null) }
            waiters.clear()
        }

        private fun setValidated(value: Boolean) {
            if (validated == value) return
            validated = value
            if (value) {
                WorkspaceTiming.record("network_validated")
                submit(WorkspaceSyncDemand(discover = true, reconnected = true))
            } else {
                timer?.cancel()
                timer = null
            }
        }

        fun submit(demand: WorkspaceSyncDemand, waiter: CompletableDeferred<Long?>? = null) {
            if (closed || !active()) {
                waiter?.complete(null)
                return
            }
            inbox.offer(demand)
            if (demand.upload || demand.reconnected && foreground) uploadRequested = true
            if (waiter != null) waiters += waiter
            runPending()
        }

        fun removeWaiter(waiter: CompletableDeferred<Long?>) { waiters.remove(waiter) }

        private fun runPending() {
            if (runner != null || closed || !active() || !inbox.hasPending) return
            if (waiters.isEmpty() && !(foreground && validated)) return
            val demand = checkNotNull(inbox.take())
            uploadRequested = false
            val completions = waiters.toList()
            waiters.clear()
            timer?.cancel()
            timer = null
            runner = scope.launch {
                try {
                    nextAt = withContext(Dispatchers.IO) { pass.run(demand) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (active()) WorkspaceTiming.record("sync_failed", code = "LOCAL_SYNC")
                    nextAt = if (active()) System.currentTimeMillis() + 15_000L else null
                } finally {
                    runner = null
                    completions.forEach { it.complete(nextAt.takeIf { _ -> active() }) }
                }
                if (closed || !active()) return@launch
                nextAt?.let { schedule(context, session, it, ExistingWorkPolicy.KEEP) }
                if (inbox.hasPending && ((foreground && validated) || waiters.isNotEmpty())) {
                    runPending()
                } else {
                    scheduleForeground()
                }
            }
        }

        private fun scheduleForeground() {
            val at = nextAt ?: return
            if (!foreground || !validated) return
            timer = scope.launch {
                delay((at - System.currentTimeMillis()).coerceAtLeast(1L))
                timer = null
                submit(WorkspaceSyncDemand())
            }
        }

        private fun localChanged() {
            if (!active()) return
            changed()
            scope.launch { refreshWatches() }
        }

        private fun refreshWatches() {
            if (closed || !foreground || !active()) return
            watchDirty = true
            if (watchTask != null) return
            val generation = watchGeneration
            watchTask = scope.launch {
                try {
                    while (watchDirty && generation == watchGeneration && foreground && active()) {
                        watchDirty = false
                        val ids = withContext(Dispatchers.IO) {
                            storage.access {
                                sql.rows("""
                                    SELECT id FROM categories WHERE revision > 0
                                    UNION SELECT category_id AS id FROM sync_queue WHERE actor_uid = ?
                                    AND entity_type = 'CATEGORY' AND action = 'DELETE' AND dispatched = 1
                                    AND status IN ('PENDING', 'SENDING', 'FAILED')
                                """.trimIndent(), uid).map { it.string("id") }
                            }
                        }
                        if (generation == watchGeneration && foreground && active()) watch(ids, generation)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (active()) WorkspaceTiming.record("watch_failed", code = "LOCAL_SYNC")
                } finally {
                    if (generation == watchGeneration) watchTask = null
                }
            }
        }

        private fun watch(ids: List<String>, generation: Int) {
            val paths = ids.map { "categories/$it" }.toSet() + "accountSignals/${session.uid}"
            (listeners.keys - paths).forEach { listeners.remove(it)?.remove() }
            for (path in paths - listeners.keys) {
                listeners[path] = firestore(firebase).document(path).addSnapshotListener { snapshot, error ->
                    if (generation != watchGeneration || !foreground || !active() ||
                        (error == null && snapshot?.metadata?.isFromCache != false)) {
                        return@addSnapshotListener
                    }
                    val cid = path.takeIf { it.startsWith("categories/") }?.substringAfter('/')
                    val demand = if (cid == null) {
                        WorkspaceSyncDemand(discover = true)
                    } else {
                        val seq = snapshot?.getLong("server_seq")
                        WorkspaceTiming.record("firestore_signal", categoryId = cid, serverSeq = seq)
                        val ready = snapshot != null && snapshot.get("cleanup_job") == null ||
                            error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        WorkspaceSyncDemand(categories = mapOf(cid to CategorySyncSignal(seq, ready)))
                    }
                    signals = signals.merge(demand)
                    if (signalTimer == null) signalTimer = scope.launch {
                        delay(250)
                        val batch = signals
                        signals = WorkspaceSyncDemand()
                        signalTimer = null
                        submit(batch)
                    }
                }
            }
        }
    }
}

private fun NetworkCapabilities?.hasInternet() =
    this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

/** All callers run on the process main thread. Signals arriving during a pass merge into one next pass. */
internal class WorkspaceSyncInbox {
    private var pending: WorkspaceSyncDemand? = null
    val hasPending get() = pending != null

    fun offer(demand: WorkspaceSyncDemand) { pending = pending?.merge(demand) ?: demand }
    fun take(): WorkspaceSyncDemand? = pending.also { pending = null }
}

class WorkspaceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (inputData.getInt("generation", 0) != 2) return Result.success()
        val uid = inputData.getString("uid") ?: return Result.failure()
        val session = AccountSession.restore(applicationContext)?.takeIf { it.uid == uid } ?: return Result.success()
        val periodic = inputData.getBoolean("periodic", false)
        val nextAt = WorkspaceSync.runWorker(applicationContext, session, periodic)
        if (nextAt != null && !isStopped) {
            val policy = if (periodic) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
            WorkspaceSync.schedule(applicationContext, session, nextAt, policy)
        }
        return Result.success()
    }
}
