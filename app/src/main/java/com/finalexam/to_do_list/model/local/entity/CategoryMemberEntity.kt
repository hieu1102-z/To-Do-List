package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to category_members in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "category_members",
    primaryKeys = ["category_id", "user_uid"],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["user_uid"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["user_uid", "status", "category_id"], name = "idx_members_user", unique = false)
    ]
)
data class CategoryMemberEntity(
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "user_uid") val userUid: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "status", defaultValue = "'ACTIVE'") val status: String = "ACTIVE",
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(name = "server_seq", defaultValue = "0") val serverSeq: Long = 0L
)

