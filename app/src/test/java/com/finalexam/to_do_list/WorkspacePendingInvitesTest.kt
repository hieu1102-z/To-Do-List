package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.model.repository.WorkspacePendingInvitation
import com.finalexam.to_do_list.model.repository.businessCode
import com.finalexam.to_do_list.model.repository.cachedPendingInvitations
import com.finalexam.to_do_list.model.repository.removePendingInvitation
import com.finalexam.to_do_list.model.repository.replacePendingInvitations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspacePendingInvitesTest : DatabaseTestBase() {
    private fun sent(id: String, categoryId: String = category, status: String = "PENDING", expiresAt: Long = 200): Row =
        mapOf("id" to id, "category_id" to categoryId, "inviter_uid" to uid, "invitee_email_key" to "$id@example.test",
            "invitee_uid" to "not-cached", "offered_role" to "EDITOR", "status" to status,
            "created_at" to 1, "expires_at" to expiresAt, "revision" to 1)

    @Test fun pendingOnly() {
        val owner = AccountWorkspace(uid, database)
        val pending = WorkspacePendingInvitation("pending", "pending@example.test", "EDITOR", 200, 1)
        val invites = owner.replacePendingInvitations(category, listOf(sent("pending"), sent("stale", expiresAt = 100),
            sent("accepted", status = "ACCEPTED"), sent("cancelled", status = "CANCELLED"),
            sent("rejected", status = "REJECTED"), sent("expired", status = "EXPIRED")), now = 100)
        assertEquals(listOf(pending), invites)
        assertEquals(listOf(pending), owner.cachedPendingInvitations(category, now = 199))
        assertTrue(owner.cachedPendingInvitations(category, now = 200).isEmpty())

        owner.replacePendingInvitations(study, listOf(sent("other", categoryId = study)), now = 100)
        assertTrue(owner.replacePendingInvitations(category, listOf(sent("pending", status = "ACCEPTED")), now = 100).isEmpty())
        assertEquals(listOf("other"), owner.cachedPendingInvitations(study, now = 100).map { it.id })
    }

    @Test fun removeOne() {
        val owner = AccountWorkspace(uid, database)
        owner.replacePendingInvitations(category, listOf(sent("cancel") + ("revision" to 3), sent("keep")), now = 100)
        owner.replacePendingInvitations(study, listOf(sent("other", categoryId = study)), now = 100)
        val invite = owner.cachedPendingInvitations(category, now = 100).first()
        assertEquals(3L, invite.revision)

        user("editor")
        member("editor")
        AccountWorkspace("editor", database).removePendingInvitation(category, invite.id)
        owner.removePendingInvitation(study, invite.id)
        assertEquals(listOf("cancel", "keep"), owner.cachedPendingInvitations(category, now = 100).map { it.id })

        owner.removePendingInvitation(category, invite.id)
        assertEquals(listOf("keep"), owner.cachedPendingInvitations(category, now = 100).map { it.id })
        assertEquals(listOf("other"), owner.cachedPendingInvitations(study, now = 100).map { it.id })
    }

    @Test fun ownerOnly() {
        val owner = AccountWorkspace(uid, database)
        owner.replacePendingInvitations(category, listOf(sent("pending")), now = 100)
        user("editor")
        member("editor")
        val editor = AccountWorkspace("editor", database)
        assertEquals("PERMISSION_DENIED", runCatching { editor.cachedPendingInvitations(category, now = 100) }
            .exceptionOrNull()?.businessCode())

        assertThrows(IllegalArgumentException::class.java) {
            owner.replacePendingInvitations(category, listOf(sent("other", categoryId = study)), now = 100)
        }
        assertEquals(listOf("pending"), owner.cachedPendingInvitations(category, now = 100).map { it.id })
        execute("UPDATE categories SET deleted_at=100 WHERE id=?", category)
        assertEquals("PERMISSION_DENIED", runCatching { owner.cachedPendingInvitations(category, now = 100) }
            .exceptionOrNull()?.businessCode())
    }
}
