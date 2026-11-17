package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to task_reminders in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "task_reminders",
    primaryKeys = ["task_id", "user_uid"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["user_uid"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE)
    ]
)
data class TaskReminderEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "user_uid") val userUid: String,
    @ColumnInfo(name = "remind_at") val remindAt: Long,
    @ColumnInfo(name = "is_enabled", defaultValue = "1") val isEnabled: Boolean = true,
    @ColumnInfo(name = "client_updated_at") val clientUpdatedAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L
)

