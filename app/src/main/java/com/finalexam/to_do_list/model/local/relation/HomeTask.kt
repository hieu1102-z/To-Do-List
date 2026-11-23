package com.finalexam.to_do_list.model.local.relation

/** Read projection for the home screen; intentionally independent of Room Entity classes. */
data class HomeTask(
    val id: String,
    val title: String,
    val description: String,
    val categoryId: String,
    val category: String,
    val dueAt: Long?,
    val priority: Int,
    val done: Boolean,
    val completedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val repeatType: String = "NONE",
    val remindAt: Long? = null,
    val reminderEnabled: Boolean? = null,
    val participantNames: String? = null,
    val revision: Long = 0,
    val memberRole: String = "OWNER",
    val workspaceUid: String? = null,
    val syncLabel: String = "Local"
) {
    val canEdit get() = memberRole == "OWNER" || memberRole == "EDITOR"
    val sourceIcon get() = when (syncLabel) {
        "Local" -> "▣"
        "Đã đồng bộ" -> "☁"
        "Chờ đồng bộ" -> "↻"
        else -> "⚠"
    }
}
