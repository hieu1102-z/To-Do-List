package com.finalexam.to_do_list.model.repository

internal data class WorkspaceMember(
    val uid: String,
    val name: String,
    val role: String,
    val revision: Long
)

internal data class WorkspaceMembers(val role: String, val members: List<WorkspaceMember>, val synced: Boolean)

internal fun AccountWorkspace.cachedMembers(categoryId: String): WorkspaceMembers {
    val category = category(categoryId)
    val currentRole = role(categoryId)
    if (category == null || category["deleted_at"] != null || currentRole == null) {
        throw WorkspaceRequestError("PERMISSION_DENIED")
    }
    val members = sql.rows("""
        SELECT m.user_uid, m.role, m.revision, COALESCE(NULLIF(u.full_name,''), u.email) AS name
        FROM category_members m JOIN users u ON u.uid=m.user_uid
        WHERE m.category_id=? AND m.status='ACTIVE'
    """.trimIndent(), categoryId).map { row ->
        WorkspaceMember(row.string("user_uid"), row.string("name"), row.string("role"), row.long("revision"))
    }
    return WorkspaceMembers(currentRole, members, category.long("revision") > 0)
}
