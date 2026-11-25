package com.finalexam.to_do_list

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.CategorySyncSignal
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.model.repository.WorkspaceRequestError
import com.finalexam.to_do_list.model.repository.WorkspaceSnapshot
import com.finalexam.to_do_list.model.repository.WorkspaceSyncDemand
import com.finalexam.to_do_list.model.repository.WorkspaceSyncPass
import com.finalexam.to_do_list.model.repository.WorkspaceSyncRemote
import com.finalexam.to_do_list.model.repository.WorkspaceSyncStorage
import com.finalexam.to_do_list.model.repository.nameKey
import com.finalexam.to_do_list.model.repository.snapshotGroups
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceSyncPassTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun reconnectSendsBeforePulling() = withStore {
        val cid = createSyncedCategory("Original")
        updateCategory(categories().single(), "New title", null, null)
        val snapshot = serverSnapshot(cid, 2, "New title")
        val remote = FakeRemote(listOf(metadata(cid, 2))) { snapshot }
        var online = false
        val pass = syncPass(remote, online = { online }, now = { 10_000L })

        assertEquals(25_000L, pass.run(WorkspaceSyncDemand()))
        assertTrue(remote.events.isEmpty())
        assertEquals("PENDING", commands().single()["status"])

        online = true
        assertNull(pass.run(WorkspaceSyncDemand(reconnected = true, discover = true)))
        assertEquals("UPDATE", remote.events.first())
        assertTrue(remote.events.contains("SNAPSHOT"))
        assertTrue(commands().isEmpty())
        assertEquals("New title", categories().single().name)
    }

    @Test fun lostResponseReplaysCommand() = withStore {
        val cid = createSyncedCategory("Original")
        updateCategory(categories().single(), "Optimistic", null, null)
        var now = 10_000L
        val committedSnapshot = serverSnapshot(cid, 2, "Authoritative")
        val remote = FakeRemote(listOf(metadata(cid, 2))) { committedSnapshot }
        val committed = mutableSetOf<String>()
        remote.upload = { request ->
            if (committed.add(request.string("operation_id"))) {
                throw WorkspaceRequestError("DEADLINE_EXCEEDED", isTransportFailure = true)
            }
            mapOf("revision" to 2L, "seq" to 2L)
        }

        assertEquals(25_000L, syncPass(remote, now = { now }).run(WorkspaceSyncDemand()))
        assertEquals("FAILED", commands().single()["status"])
        val replay = syncPass(remote, now = { now })
        assertEquals(25_000L, replay.run(WorkspaceSyncDemand()))
        assertEquals("Optimistic", categories().single().name)

        now = 25_000L
        assertNull(replay.run(WorkspaceSyncDemand()))
        assertEquals(1, committed.size)
        assertEquals(2, remote.uploads.size)
        assertEquals(remote.uploads[0], remote.uploads[1])
        assertTrue(commands().isEmpty())
        assertEquals("Authoritative", categories().single().name)
    }

    @Test fun cleanupDoesNotBlockOtherCategories() = withStore {
        val cleaning = createSyncedCategory("Cleaning")
        val another = createSyncedCategory("Other")
        enqueue(cleaning, "TASK", uuid(), "PURGE", 2)
        enqueue(another, "TASK", uuid(), "TRASH", 1)
        val finished = serverSnapshot(cleaning, 3)
        val other = serverSnapshot(another, 2)
        var cleanupFinished = false
        var now = 10_000L
        val remote = FakeRemote(listOf(metadata(cleaning, 2), metadata(another, 2))) { cid ->
            when {
                cid == another -> other
                cleanupFinished -> finished
                else -> throw WorkspaceRequestError("CATEGORY_BUSY")
            }
        }
        remote.upload = { request ->
            if (request["kind"] == "PURGE" && !cleanupFinished) mapOf("pending" to true)
            else mapOf("revision" to 2L, "seq" to if (request["kind"] == "PURGE") 3L else 2L)
        }
        val pass = syncPass(remote, now = { now })
        assertEquals(12_000L, pass.run(WorkspaceSyncDemand()))
        assertEquals(listOf("PURGE", "TRASH"), remote.uploads.map { it["kind"] })
        assertEquals("SENDING", commands().single()["status"])
        assertEquals(false, needsSnapshot(another, 2, "OWNER"))

        now = 10_100L
        cleanupFinished = true
        val signal = CategorySyncSignal(3L, cleanupFinished = true)
        assertNull(pass.run(WorkspaceSyncDemand(categories = mapOf(cleaning to signal))))
        assertTrue(commands().isEmpty())
        assertEquals(remote.uploads[0], remote.uploads[2])
        assertEquals(false, needsSnapshot(cleaning, 3, "OWNER"))
    }

    @Test fun legacyListStillPulls() = withStore {
        val cid = createSyncedCategory("Original")
        val snapshot = serverSnapshot(cid, 1)
        val remote = FakeRemote(listOf(metadata(cid, 1))) { snapshot }
        val pass = syncPass(remote)
        assertNull(pass.run(WorkspaceSyncDemand()))
        assertEquals(listOf("PROFILE", "LIST"), remote.events)

        remote.listing = listOf(mapOf("id" to cid, "role" to "OWNER"))
        assertNull(pass.run(WorkspaceSyncDemand(discover = true)))
        assertEquals(listOf("PROFILE", "LIST", "LIST", "SNAPSHOT"), remote.events)
    }

    @Test fun revokedSessionKeepsCommand() = withStore {
        createSyncedCategory("Original")
        updateCategory(categories().single(), "Optimistic", null, null)
        val stored = commands().single()
        var active = true
        val remote = FakeRemote { error("A revoked session must not pull") }
        remote.upload = {
            active = false
            mapOf("revision" to 2L)
        }
        val pass = syncPass(remote, active = { active }, onLocalChange = {
            error("A revoked session must not publish changes")
        })

        assertNull(pass.run(WorkspaceSyncDemand()))
        val pending = commands().single()
        assertEquals("SENDING", pending["status"])
        assertEquals(envelope(stored), envelope(pending))
        assertEquals(listOf("UPDATE"), remote.events)
    }

    private fun withStore(work: AccountWorkspace.() -> Unit) {
        val uid = uuid()
        val fileName = AppDatabase.accountFileName(uid)
        try {
            AccountWorkspace(uid, AppDatabase.accountBuilder(context, uid).allowMainThreadQueries().build()).use { store ->
                store.database.users().upsert(UserEntity(uid, "a@example.test", "a@example.test", "A"))
                store.work()
            }
        } finally {
            context.deleteDatabase(fileName)
        }
    }

    private fun AccountWorkspace.createSyncedCategory(name: String): String {
        val cid = createCategory(name)
        acknowledge(commands().single(), mapOf("revision" to 1L))
        apply(serverSnapshot(cid, 1))
        return cid
    }

    private fun AccountWorkspace.serverSnapshot(cid: String, seq: Long, title: String? = null): WorkspaceSnapshot {
        val category = checkNotNull(category(cid))
        val fields = if (title == null) category else category + mapOf("name" to title, "name_key" to nameKey(title))
        val owner = mapOf("category_id" to cid, "user_uid" to uid, "role" to "OWNER", "joined_at" to 1L)
        val groups = snapshotGroups.mapValues { (section, _) -> if (section == "MEMBER") listOf(owner) else emptyList() }
        return WorkspaceSnapshot(fields, "OWNER", seq, groups, emptyList())
    }

    private fun AccountWorkspace.syncPass(
        remote: FakeRemote,
        active: () -> Boolean = { true },
        online: () -> Boolean = { true },
        now: () -> Long = { 10_000L },
        onLocalChange: () -> Unit = {}
    ) = WorkspaceSyncPass(object : WorkspaceSyncStorage {
        override fun <T> access(work: AccountWorkspace.() -> T): T = this@syncPass.work()
    }, remote, active, online, onLocalChange, now = now)

    private fun metadata(cid: String, seq: Long): Row = mapOf("id" to cid, "role" to "OWNER", "server_seq" to seq)

    private class FakeRemote(
        var listing: List<Row> = emptyList(),
        val load: (String) -> WorkspaceSnapshot
    ) : WorkspaceSyncRemote {
        val events = mutableListOf<String>()
        val uploads = mutableListOf<Row>()
        var upload: (Row) -> Row = { mapOf("revision" to 2L, "seq" to 2L) }

        override fun call(data: Row, forceRefresh: Boolean): Row {
            val kind = data.string("kind")
            events += kind
            if (kind == "PROFILE") return mapOf("ok" to true)
            uploads += data
            return upload(data)
        }

        override fun pages(kind: String, fields: Row): List<Row> {
            events += kind
            assertEquals(true, fields["include_sync_metadata"])
            return listing
        }

        override fun snapshot(categoryId: String): WorkspaceSnapshot {
            events += "SNAPSHOT"
            return load(categoryId)
        }
    }
}
