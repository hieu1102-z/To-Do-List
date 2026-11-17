package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to task_participants in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "task_participants",
    primaryKeys = ["task_id", "user_uid"],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["category_id", "id"], childColumns = ["category_id", "task_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryMemberEntity::class, parentColumns = ["category_id", "user_uid"], childColumns = ["category_id", "user_uid"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["category_id", "task_id"], name = "idx_participants_task", unique = false),
        Index(value = ["category_id", "user_uid"], name = "idx_participants_member", unique = false)
    ]
)
data class TaskParticipantEntity(
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "user_uid") val userUid: String,
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(name = "server_seq", defaultValue = "0") val serverSeq: Long = 0L
)

