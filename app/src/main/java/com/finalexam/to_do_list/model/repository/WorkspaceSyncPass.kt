package com.finalexam.to_do_list.model.repository

internal data class CategorySyncSignal(val seq: Long?, val cleanupFinished: Boolean)

internal data class WorkspaceSyncDemand(
    val discover: Boolean = false,
    val repair: Boolean = false,
    val refreshProfile: Boolean = false,
    val reconnected: Boolean = false,
    val upload: Boolean = false,
    val categories: Map<String, CategorySyncSignal> = emptyMap()
) {
    fun merge(other: WorkspaceSyncDemand) = WorkspaceSyncDemand(
        discover || other.discover,
        repair || other.repair,
        refreshProfile || other.refreshProfile,
        reconnected || other.reconnected,
        upload || other.upload,
        categories + other.categories
    )
}

internal interface WorkspaceSyncStorage {
    fun <T> access(work: AccountWorkspace.() -> T): T
}

internal interface WorkspaceSyncRemote {
    fun call(data: Row, forceRefresh: Boolean = false): Row
    fun pages(kind: String, fields: Row = emptyMap()): List<Row>
    fun snapshot(categoryId: String): WorkspaceSnapshot
}

/** State for one authenticated session. The coordinator runs passes serially. */
internal class WorkspaceSyncPass(
    private val storage: WorkspaceSyncStorage,
    private val remote: WorkspaceSyncRemote,
    private val active: () -> Boolean,
    private val online: () -> Boolean,
    private val onLocalChange: () -> Unit,
    private val reconcileReminders: AccountWorkspace.() -> Unit = {},
    private val uploadRequested: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Retry(val code: String, val count: Int, val at: Long)

    private val targets = linkedSetOf<String>()
    private val pullRetries = mutableMapOf<String, Retry>()
    private var repairAll = false
    private var discover = true
    private var profile = true
    private var refreshProfile = false
    private var profileRetry: Retry? = null
    private var serviceRetry: Retry? = null

    fun run(demand: WorkspaceSyncDemand): Long? {
        if (!active()) return null
        discover = discover || demand.discover || demand.repair
        repairAll = repairAll || demand.repair
        profile = profile || demand.repair || demand.refreshProfile
        refreshProfile = refreshProfile || demand.refreshProfile
        if (demand.reconnected) {
            storage.access { wakeOfflineCommands() }
            if (serviceRetry?.code == "OFFLINE_TRANSPORT") serviceRetry = null
            if (profileRetry?.code == "OFFLINE_TRANSPORT") profileRetry = null
            pullRetries.values.removeAll { it.code == "OFFLINE_TRANSPORT" }
        }
        for ((cid, signal) in demand.categories) {
            storage.access {
                if (signal.cleanupFinished) wakeCategoryCleanup(cid)
                if (needsSnapshot(cid, signal.seq)) targets += cid
            }
            if (signal.cleanupFinished && pullRetries[cid]?.code == "CATEGORY_BUSY") {
                pullRetries.remove(cid)
            }
        }
        if (!online()) return now() + 15_000L
        serviceRetry?.takeIf { it.at > now() }?.let { return it.at }

        if (!sendCommands() || !active() || !online()) return deadline()
        if (uploadRequested()) return deadline()
        syncProfile()
        readTargets()
        if (!uploadRequested() && active() && online() && serviceRetry?.let { it.at > now() } != true) {
            discoverCategories()
            readTargets()
        }
        return deadline()
    }

    private fun sendCommands(): Boolean {
        val blocked = mutableSetOf<String>()
        repeat(100) {
            if (!active() || !online()) return false
            val next = storage.access {
                transaction {
                    nextRunnableCommand(blocked, now())?.let { command ->
                        sending(command.string("operation_id"))
                        command to envelope(command)
                    }
                }
            } ?: return true
            val (command, envelope) = next
            val cid = command.string("category_id")
            val response = try {
                remote.call(envelope)
            } catch (error: WorkspaceRequestError) {
                if (!active()) return false
                val code = failureCode(error)
                val transient = error.isTransientWorkspaceFailure || code == "OFFLINE_TRANSPORT" ||
                    envelope["kind"] == "ENSURE_DEFAULT_CATEGORY" && code == "INVALID_ARGUMENT"
                storage.access { failed(command, code, transient, error.retryAfterMillis, now()) }
                if (transient) {
                    blocked += cid
                    if (code != "CATEGORY_BUSY") {
                        serviceRetry = retry(code, serviceRetry, error.retryAfterMillis)
                        return false
                    }
                } else {
                    targets += cid
                    onLocalChange()
                }
                return@repeat
            }
            if (!active()) return false
            serviceRetry = null
            if (response["pending"] == true) {
                storage.access { pendingCleanup(command, now()) }
                blocked += cid
            } else {
                storage.access { acknowledge(command, response) }
                targets += cid
                pullRetries.remove(cid)
                onLocalChange()
            }
        }
        return true
    }

    private fun syncProfile() {
        if (!active() || !online() || !profile || profileRetry?.let { it.at > now() } == true) return
        try {
            remote.call(mapOf("kind" to "PROFILE"), forceRefresh = refreshProfile)
            profile = false
            refreshProfile = false
            profileRetry = null
        } catch (error: WorkspaceRequestError) {
            profileRetry = retry(failureCode(error), profileRetry, error.retryAfterMillis)
            if (error.code == "RATE_LIMITED") serviceRetry = profileRetry
        }
    }

    private fun discoverCategories() {
        if (!discover) return
        val items = try {
            remote.pages("LIST", mapOf("include_default" to true, "include_sync_metadata" to true))
        } catch (error: WorkspaceRequestError) {
            serviceRetry = retry(failureCode(error), serviceRetry, error.retryAfterMillis)
            return
        }
        if (!active()) return
        serviceRetry = null
        val remoteIds = items.map { it.string("id") }.toSet()
        storage.access {
            val localIds = sql.rows("SELECT id FROM categories").map { it.string("id") }
            val creating = creatingCategories()
            // A scoped snapshot will confirm access to categories missing from LIST.
            targets += (localIds - remoteIds) - creating
            items.forEach { row ->
                val cid = row.string("id")
                if (cid !in creating && (repairAll || needsSnapshot(cid, row.optionalLong("server_seq"), row.string("role")))) {
                    targets += cid
                }
            }
        }
        discover = false
        repairAll = false
    }

    private fun readTargets() {
        for (cid in targets.toList()) {
            if (uploadRequested() || !active() || !online() || serviceRetry?.let { it.at > now() } == true) return
            if (pullRetries[cid]?.let { it.at > now() } == true) continue
            val skip = storage.access { cid in creatingCategories() || hasUnconfirmedCategoryPurge(cid) }
            if (skip) {
                completeTarget(cid)
                continue
            }
            try {
                val snapshot = remote.snapshot(cid)
                if (!active()) return
                serviceRetry = null
                if (storage.access { apply(snapshot).also { if (it) reconcileReminders(this) } }) {
                    WorkspaceTiming.record("room_applied", categoryId = cid, serverSeq = snapshot.seq)
                    onLocalChange()
                }
                completeTarget(cid)
            } catch (error: WorkspaceRequestError) {
                if (!active()) return
                val code = failureCode(error)
                if (code == "PERMISSION_DENIED" && storage.access { cid != defaultCategoryId(uid) }) {
                    storage.access { if (dropCategory(cid)) reconcileReminders(this) }
                    completeTarget(cid)
                    onLocalChange()
                } else if (code in setOf("CATEGORY_BUSY", "SNAPSHOT_CHANGED", "PERMISSION_DENIED")) {
                    pullRetries[cid] = retry(code, pullRetries[cid], error.retryAfterMillis)
                } else {
                    serviceRetry = retry(code, serviceRetry, error.retryAfterMillis)
                    return
                }
            }
        }
    }

    private fun AccountWorkspace.creatingCategories() = commands()
        .filter { it["entity_type"] == "CATEGORY" && it["action"] == "CREATE" && it["status"] != "REJECTED" }
        .map { it.string("category_id") }.toSet()

    private fun completeTarget(cid: String) {
        targets.remove(cid)
        pullRetries.remove(cid)
    }

    private fun deadline(): Long? {
        if (!active()) return null
        if (!online()) return now() + 15_000L
        serviceRetry?.takeIf { it.at > now() }?.let { return it.at }
        val pushAt = storage.access { nextCommandAt(now()) }
        val pullAt = targets.minOfOrNull { pullRetries[it]?.at ?: now() }
        val profileAt = if (profile) profileRetry?.at ?: now() else null
        return listOfNotNull(pushAt, pullAt, profileAt, if (discover) now() else null).minOrNull()
    }

    private fun failureCode(error: WorkspaceRequestError): String =
        if (error.isTransportFailure && !online()) "OFFLINE_TRANSPORT" else error.code

    private fun retry(code: String, previous: Retry?, retryAfterMs: Long? = null): Retry {
        val count = if (previous?.code == code) previous.count + 1 else 0
        val delays = when (code) {
            "CATEGORY_BUSY" -> listOf(2_000L, 5_000L, 10_000L, 30_000L)
            "SNAPSHOT_CHANGED" -> listOf(1_000L, 2_000L, 5_000L)
            else -> listOf(15_000L, 30_000L, 60_000L)
        }
        val wait = maxOf(delays[count.coerceAtMost(delays.lastIndex)], retryAfterMs ?: 0L)
        val at = now()
        return Retry(code, count, at + wait.coerceAtMost(Long.MAX_VALUE - at))
    }
}
