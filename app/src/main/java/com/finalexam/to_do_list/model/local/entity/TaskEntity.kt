package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to tasks in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["created_by_uid"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["category_id", "is_done", "due_at"], name = "idx_tasks_category_due", unique = false),
        Index(value = ["category_id", "id"], name = "index_tasks_category_id_id", unique = true)
    ]
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "created_by_uid") val createdByUid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "due_at") val dueAt: Long? = null,
    @ColumnInfo(name = "priority", defaultValue = "2") val priority: Int = 2,
    @ColumnInfo(name = "is_done", defaultValue = "0") val isDone: Boolean = false,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "repeat_type", defaultValue = "'NONE'") val repeatType: String = "NONE",
    @ColumnInfo(name = "timezone", defaultValue = "'Asia/Ho_Chi_Minh'") val timezone: String = "Asia/Ho_Chi_Minh",
    @ColumnInfo(name = "repeat_anchor_day") val repeatAnchorDay: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "client_updated_at") val clientUpdatedAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(name = "server_seq", defaultValue = "0") val serverSeq: Long = 0L
)

