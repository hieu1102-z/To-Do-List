package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to subtasks in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "subtasks",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["task_id"], name = "idx_subtasks_task", unique = false)
    ]
)
data class SubtaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "is_done", defaultValue = "0") val isDone: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "client_updated_at") val clientUpdatedAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(name = "server_seq", defaultValue = "0") val serverSeq: Long = 0L
)

