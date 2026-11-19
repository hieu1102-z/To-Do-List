package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to users in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "users"
)
data class UserEntity(
    @PrimaryKey @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "email") val email: String,
    @ColumnInfo(name = "email_key") val emailKey: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "email_verified", defaultValue = "0") val emailVerified: Boolean = false,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String? = null,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null
)

