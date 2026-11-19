package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to category_invitations in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "category_invitations",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["invitee_uid"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["inviter_uid"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["invitee_email_key", "status"], name = "idx_invites_email", unique = false)
    ]
)
data class CategoryInvitationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "inviter_uid") val inviterUid: String,
    @ColumnInfo(name = "invitee_email_key") val inviteeEmailKey: String,
    @ColumnInfo(name = "invitee_uid") val inviteeUid: String? = null,
    @ColumnInfo(name = "offered_role") val offeredRole: String,
    @ColumnInfo(name = "status", defaultValue = "'PENDING'") val status: String = "PENDING",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: Long? = null,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(name = "server_seq", defaultValue = "0") val serverSeq: Long = 0L
)

