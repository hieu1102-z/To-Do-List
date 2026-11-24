package com.finalexam.to_do_list.model.repository

internal data class WorkspacePendingInvitation(
    val id: String, val email: String, val role: String, val expiresAt: Long, val revision: Long
)

internal fun AccountWorkspace.cachedPendingInvitations(
    categoryId: String, now: Long = System.currentTimeMillis()
): List<WorkspacePendingInvitation> {
    requirePendingInvitationAccess(categoryId)
    return sql.rows("""
        SELECT id, invitee_email_key, offered_role, expires_at, revision
        FROM category_invitations
        WHERE category_id=? AND inviter_uid=? AND status='PENDING' AND deleted_at IS NULL AND expires_at>?
        ORDER BY invitee_email_key, id
    """.trimIndent(), categoryId, uid, now).map { row ->
        WorkspacePendingInvitation(row.string("id"), row.string("invitee_email_key"),
            row.string("offered_role"), row.long("expires_at"), row.long("revision"))
    }
}

internal fun AccountWorkspace.replacePendingInvitations(
    categoryId: String, sent: List<Row>, now: Long = System.currentTimeMillis()
): List<WorkspacePendingInvitation> = transaction {
    requirePendingInvitationAccess(categoryId)
    require(sent.all { it["category_id"] == categoryId })
    val pending = sent.filter { it["status"] == "PENDING" && it["deleted_at"] == null && it.long("expires_at") > now }
    require(pending.all { it["inviter_uid"] == uid })
    clearPendingInvitations(categoryId)
    pending.forEach { row ->
        sql.execSQL("""
            INSERT INTO category_invitations(
                id, category_id, inviter_uid, invitee_email_key, offered_role, created_at, expires_at,
                server_updated_at, revision, server_seq
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), arrayOf<Any?>(row.string("id"), categoryId, uid, row.string("invitee_email_key"),
            row.string("offered_role"), row.long("created_at"), row.long("expires_at"),
            row.optionalLong("server_updated_at"), row.long("revision"), row.long("server_seq")))
    }
    cachedPendingInvitations(categoryId, now)
}

internal fun AccountWorkspace.clearPendingInvitations(categoryId: String) {
    sql.execSQL("DELETE FROM category_invitations WHERE category_id=? AND inviter_uid=? AND status='PENDING'",
        arrayOf(categoryId, uid))
}

internal fun AccountWorkspace.removePendingInvitation(categoryId: String, invitationId: String) {
    sql.execSQL("DELETE FROM category_invitations WHERE category_id=? AND id=? AND inviter_uid=? AND status='PENDING'",
        arrayOf(categoryId, invitationId, uid))
}

private fun AccountWorkspace.requirePendingInvitationAccess(categoryId: String) {
    val category = category(categoryId)
    if (categoryId == defaultCategoryId(uid) || category == null || category["owner_uid"] != uid ||
        category["deleted_at"] != null || role(categoryId) != "OWNER") {
        throw WorkspaceRequestError("PERMISSION_DENIED")
    }
}
