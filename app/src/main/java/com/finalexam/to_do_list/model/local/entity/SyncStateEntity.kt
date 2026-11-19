package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to sync_state in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "sync_state",
    primaryKeys = ["user_uid", "category_id"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["user_uid"], onDelete = ForeignKey.NO_ACTION)
    ]
)
data class SyncStateEntity(
    @ColumnInfo(name = "user_uid") val userUid: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "last_server_seq", defaultValue = "0") val lastServerSeq: Long = 0L
)

