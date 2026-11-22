package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to categories in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["owner_uid"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = []
)
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "color") val color: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "client_updated_at") val clientUpdatedAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(name = "server_seq", defaultValue = "0") val serverSeq: Long = 0L,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null
)

