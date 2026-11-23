package com.finalexam.to_do_list.model.local.entity

import androidx.room.*

/** Maps directly to sync_queue in TodoList_DB_v2.dbml. */
@Entity(
    tableName = "sync_queue",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["uid"], childColumns = ["actor_uid"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [
        Index(value = ["actor_uid", "status", "next_retry_at"], name = "idx_outbox_pending", unique = false)
    ]
)
data class SyncQueueEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "actor_uid") val actorUid: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
    @ColumnInfo(name = "base_revision", defaultValue = "0") val baseRevision: Long = 0L,
    @ColumnInfo(name = "client_occurred_at") val clientOccurredAt: Long,
    @ColumnInfo(name = "status", defaultValue = "'PENDING'") val status: String = "PENDING",
    @ColumnInfo(name = "retry_count", defaultValue = "0") val retryCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "predecessor_id") val predecessorId: String? = null,
    @ColumnInfo(name = "dispatched", defaultValue = "0") val dispatched: Boolean = false,
    @ColumnInfo(name = "membership_revision", defaultValue = "0") val membershipRevision: Long = 0
)

