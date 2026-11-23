package com.finalexam.to_do_list.model.local.relation

data class TrashTask(
    val id: String,
    val title: String,
    val category: String,
    val deletedAt: Long,
    val categoryId: String,
    val workspaceUid: String? = null,
    val role: String = "OWNER",
    val entityType: String = "TASK",
    val parentId: String? = null,
    val revision: Long = 0
)
