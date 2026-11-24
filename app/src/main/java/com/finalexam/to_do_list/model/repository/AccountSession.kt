
package com.finalexam.to_do_list.model.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.remote.AuthUser
import com.finalexam.to_do_list.notification.AccountReminders
import com.finalexam.to_do_list.notification.TaskReminderScheduler

internal class AccountSession(val uid: String) {
    companion object {
        private val coordinator = AccountSessions({ AuthRepository.get(it).user })

        fun current(context: Context) = coordinator.current(context)
        fun restore(context: Context) = coordinator.restore(context)
        fun revoke(context: Context, uid: String) = coordinator.revoke(context, uid)
        fun <T> access(context: Context, expected: AccountSession, work: AccountWorkspace.() -> T): T =
            coordinator.access(context, expected, work)
        fun prepare(context: Context, uid: String, confirmed: Boolean) = coordinator.prepare(context, uid, confirmed)
        fun cleanupLegacy(context: Context) = coordinator.cleanupLegacy(context)
    }
}

internal class AccountSessions(
    private val authenticated: (Context) -> AuthUser?,
    private val cacheFor: (Context) -> AccountCache = { AccountCache(it) }
) {
    private val lock = Any()
    @Volatile private var session: AccountSession? = null

    fun current(context: Context): AccountSession? =
        session?.takeIf { authenticated(context)?.uid == it.uid }

    private fun ready(context: Context) = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)

    fun restore(context: Context): AccountSession? = synchronized(lock) {
        current(context)?.let { return@synchronized it }
        val uid = authenticated(context)?.uid ?: return@synchronized null
        if (ready(context).getString("database", null) != AppDatabase.accountFileName(uid)) return@synchronized null
        AccountSession(uid).also { session = it }
    }

    fun revoke(context: Context, uid: String) = synchronized(lock) {
        if (session?.uid == uid) session = null
        ready(context).edit().remove("database").commit()
    }

    fun <T> access(context: Context, expected: AccountSession, work: AccountWorkspace.() -> T): T =
        synchronized(lock) {
            check(current(context) === expected) { "Phiên đăng nhập đã thay đổi." }
            AccountWorkspace(expected.uid, AppDatabase.accountBuilder(context, expected.uid).build()).use(work)
        }

    fun prepare(context: Context, uid: String, confirmed: Boolean): AccountCache.Pending? = synchronized(lock) {
        val user = checkNotNull(authenticated(context)?.takeIf { it.uid == uid })
        if (current(context)?.uid == uid) return@synchronized null
        session = null
        val cache = cacheFor(context)
        val pending = cache.pendingBeforeSwitch(uid)
        if (!confirmed && (pending.count > 0 || pending.unknown)) return@synchronized pending
        cache.deleteOtherAccounts(uid)
        AccountWorkspace(uid, AppDatabase.accountBuilder(context, uid).build()).use { store ->
            store.transaction {
                store.database.cacheProfile(user)
                store.ensureDefaultCategory()
            }
        }
        check(authenticated(context)?.uid == uid) { "Phiên đăng nhập đã thay đổi." }
        check(ready(context).edit().putString("database", AppDatabase.accountFileName(uid)).commit())
        session = AccountSession(uid)
        null
    }

    fun cleanupLegacy(context: Context) = synchronized(lock) { cacheFor(context).deleteGuestData() }
}

internal class AccountCache(
    private val context: Context,
    private val cancelWork: (String) -> Unit = { WorkspaceSync.cancelCache(context, it, purge = true) }
) {
    data class Pending(val count: Long, val unknown: Boolean)

    private fun accountFiles() = databaseNames().filter { it.matches(Regex("account-[0-9a-f]{64}\\.db")) }
    private fun databaseNames(): Set<String> =
        context.databaseList().map { it.substringBefore(".db-").let { name ->
            if (name != it) "$name.db" else name
        } }.toSet()

    private fun otherFiles(uid: String) = accountFiles() - AppDatabase.accountFileName(uid)
    private val avatarDirectory get() = java.io.File(context.filesDir, "account-avatars")

    fun pendingBeforeSwitch(uid: String): Pending {
        var count = 0L
        var unknown = false
        for (file in otherFiles(uid)) {
            val path = context.getDatabasePath(file)
            if (!path.exists()) continue
            runCatching {
                read(file) { database ->
                    database.rawQuery("SELECT entity_type, action, payload_json FROM sync_queue WHERE status<>'ACKED'", null).use { rows ->
                        while (rows.moveToNext()) {
                            val isBootstrap = rows.getString(0) == "CATEGORY" && rows.getString(1) == "CREATE" &&
                                runCatching { org.json.JSONObject(rows.getString(2)).optString("command_type") == "ENSURE_DEFAULT_CATEGORY" }
                                    .getOrDefault(false)
                            if (!isBootstrap) count++
                        }
                    }
                }
            }.onFailure { unknown = true }
        }
        return Pending(count, unknown)
    }

    fun deleteOtherAccounts(uid: String) {
        for (file in otherFiles(uid)) {
            cancelWork(file)
            deleteDatabase(file)
        }
        AccountReminders.clearOtherAccounts(context, uid)
        avatarDirectory.listFiles()?.filter { it.name != AppDatabase.accountFileName(uid) + ".jpg" }?.forEach {
            check(!it.exists() || it.delete())
        }
    }

    fun deleteGuestData() {
        val file = "guest-workspace.db"
        if (context.getDatabasePath(file).exists()) {
            runCatching {
                read(file) { database ->
                    val table = database.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('tasks','guest_tasks') ORDER BY name DESC LIMIT 1", null
                    ).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (table != null) database.rawQuery("SELECT id FROM $table", null).use {
                        while (it.moveToNext()) TaskReminderScheduler.cancelLegacy(context, it.getString(0))
                    }
                }
            }
        }
        val prefs = context.getSharedPreferences("guest_profile", Context.MODE_PRIVATE)
        prefs.getString("avatar_uri", null)?.let { saved ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(saved),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        check(prefs.edit().clear().commit())
        context.deleteSharedPreferences("guest_profile")
        val avatar = java.io.File(context.filesDir, "profile-avatar.jpg")
        check(!avatar.exists() || avatar.delete())
        deleteDatabase(file)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == null }.forEach { manager.cancel(it.id) }
    }

    fun avatar(uid: String) = java.io.File(avatarDirectory, AppDatabase.accountFileName(uid) + ".jpg")

    private fun deleteDatabase(file: String) {
        context.deleteDatabase(file)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val path = context.getDatabasePath(file + suffix)
            check(!path.exists() || path.delete()) { "Không thể xóa dữ liệu của phiên trước." }
        }
    }

    private fun <T> read(file: String, work: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READONLY).use(work)
}
