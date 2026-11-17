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
    val updatedAt: Long
)
