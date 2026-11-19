package com.finalexam.to_do_list.model.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Supplement to Room's exported JSON: SQLite CHECKs and partial indexes from DBML. */
internal object SchemaSql {
    val tables = listOf("users", "categories", "category_members", "category_invitations", "tasks", "task_participants", "subtasks", "task_reminders", "sync_queue", "sync_state")
    val createStatements = listOf(
        """CREATE TABLE IF NOT EXISTS `users` (`uid` TEXT NOT NULL, `email` TEXT NOT NULL, `email_key` TEXT NOT NULL, `full_name` TEXT NOT NULL, `email_verified` INTEGER NOT NULL DEFAULT 0, `avatar_url` TEXT, `server_updated_at` INTEGER, PRIMARY KEY (`uid`), CHECK (email_verified IN (0,1)))""",
        """CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `name` TEXT NOT NULL, `name_key` TEXT NOT NULL, `color` TEXT, `created_at` INTEGER NOT NULL, `client_updated_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, `server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`id`), FOREIGN KEY (owner_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `uq_category_owner_name` ON `categories` (owner_uid, name_key)""",
        """CREATE TABLE IF NOT EXISTS `category_members` (`category_id` TEXT NOT NULL, `user_uid` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'ACTIVE', `joined_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, `server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`category_id`, `user_uid`), FOREIGN KEY (user_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY (category_id) REFERENCES categories (id) ON UPDATE NO ACTION ON DELETE CASCADE, CHECK (role IN ('OWNER','EDITOR','VIEWER')), CHECK (status IN ('ACTIVE','REMOVED')))""",
        """CREATE INDEX IF NOT EXISTS `idx_members_user` ON `category_members` (user_uid, status, category_id)""",
        """CREATE TABLE IF NOT EXISTS `category_invitations` (`id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `inviter_uid` TEXT NOT NULL, `invitee_email_key` TEXT NOT NULL, `invitee_uid` TEXT, `offered_role` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, `server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`id`), FOREIGN KEY (invitee_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY (inviter_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY (category_id) REFERENCES categories (id) ON UPDATE NO ACTION ON DELETE CASCADE, CHECK (offered_role IN ('EDITOR','VIEWER')), CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED','EXPIRED')))""",
        """CREATE INDEX IF NOT EXISTS `idx_invites_email` ON `category_invitations` (invitee_email_key, status)""",
        """CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `category_id` TEXT NOT NULL, `created_by_uid` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `due_at` INTEGER, `priority` INTEGER NOT NULL DEFAULT 2, `is_done` INTEGER NOT NULL DEFAULT 0, `completed_at` INTEGER, `repeat_type` TEXT NOT NULL DEFAULT 'NONE', `timezone` TEXT NOT NULL DEFAULT 'Asia/Ho_Chi_Minh', `repeat_anchor_day` INTEGER, `created_at` INTEGER NOT NULL, `client_updated_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, `server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`id`), FOREIGN KEY (created_by_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY (category_id) REFERENCES categories (id) ON UPDATE NO ACTION ON DELETE CASCADE, CHECK (length(trim(title)) BETWEEN 1 AND 200), CHECK (priority IN (1,2,3)), CHECK (is_done IN (0,1)), CHECK (repeat_type IN ('NONE','DAILY','WEEKLY','MONTHLY')), CHECK (repeat_anchor_day BETWEEN 1 AND 31), CHECK ((is_done=0 AND completed_at IS NULL) OR (is_done=1 AND completed_at IS NOT NULL)))""",
        """CREATE INDEX IF NOT EXISTS `idx_tasks_category_due` ON `tasks` (category_id, is_done, due_at)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_tasks_category_id_id` ON `tasks` (category_id, id)""",
        """CREATE TABLE IF NOT EXISTS `task_participants` (`category_id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `user_uid` TEXT NOT NULL, `joined_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, `server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`task_id`, `user_uid`), FOREIGN KEY (category_id, task_id) REFERENCES tasks (category_id, id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY (category_id, user_uid) REFERENCES category_members (category_id, user_uid) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        """CREATE INDEX IF NOT EXISTS `idx_participants_task` ON `task_participants` (category_id, task_id)""",
        """CREATE INDEX IF NOT EXISTS `idx_participants_member` ON `task_participants` (category_id, user_uid)""",
        """CREATE TABLE IF NOT EXISTS `subtasks` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `title` TEXT NOT NULL, `is_done` INTEGER NOT NULL DEFAULT 0, `sort_order` INTEGER NOT NULL DEFAULT 0, `client_updated_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, `server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`id`), FOREIGN KEY (task_id) REFERENCES tasks (id) ON UPDATE NO ACTION ON DELETE CASCADE, CHECK (length(trim(title)) BETWEEN 1 AND 200), CHECK (is_done IN (0,1)))""",
        """CREATE INDEX IF NOT EXISTS `idx_subtasks_task` ON `subtasks` (task_id)""",
        """CREATE TABLE IF NOT EXISTS `task_reminders` (`task_id` TEXT NOT NULL, `user_uid` TEXT NOT NULL, `remind_at` INTEGER NOT NULL, `is_enabled` INTEGER NOT NULL DEFAULT 1, `client_updated_at` INTEGER NOT NULL, `server_updated_at` INTEGER, `revision` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`task_id`, `user_uid`), FOREIGN KEY (user_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY (task_id) REFERENCES tasks (id) ON UPDATE NO ACTION ON DELETE CASCADE, CHECK (is_enabled IN (0,1)))""",
        """CREATE TABLE IF NOT EXISTS `sync_queue` (`operation_id` TEXT NOT NULL, `actor_uid` TEXT NOT NULL, `category_id` TEXT NOT NULL, `entity_type` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `action` TEXT NOT NULL, `payload_json` TEXT, `base_revision` INTEGER NOT NULL DEFAULT 0, `client_occurred_at` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `retry_count` INTEGER NOT NULL DEFAULT 0, `last_error` TEXT, `next_retry_at` INTEGER, PRIMARY KEY (`operation_id`), FOREIGN KEY (actor_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION, CHECK (action IN ('CREATE','UPDATE','DELETE')), CHECK ((action='DELETE' AND payload_json IS NULL) OR (action IN ('CREATE','UPDATE') AND payload_json IS NOT NULL)), CHECK (status IN ('PENDING','SENDING','ACKED','FAILED','REJECTED')))""",
        """CREATE INDEX IF NOT EXISTS `idx_outbox_pending` ON `sync_queue` (actor_uid, status, next_retry_at)""",
        """CREATE TABLE IF NOT EXISTS `sync_state` (`user_uid` TEXT NOT NULL, `category_id` TEXT NOT NULL, `last_server_seq` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (`user_uid`, `category_id`), FOREIGN KEY (user_uid) REFERENCES users (uid) ON UPDATE NO ACTION ON DELETE NO ACTION)"""
    )

    fun create(db: SupportSQLiteDatabase) { createStatements.forEach(db::execSQL) }

    fun replaceEmptyRoomTables(db: SupportSQLiteDatabase) {
        // Only Room's freshly generated, empty business tables may be replaced.
        tables.forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
        }
        tables.asReversed().forEach { db.execSQL("DROP TABLE `$it`") }
        create(db)
    }

    fun installPartialIndexes(db: SupportSQLiteDatabase) {
        // Install after Room schema validation: Room cannot model WHERE predicates.
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_one_active_owner ON category_members(category_id) WHERE role='OWNER' AND status='ACTIVE'")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_invite ON category_invitations(category_id, invitee_email_key) WHERE status='PENDING'")
    }

    fun removePartialIndexesForMigration(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS uq_one_active_owner")
        db.execSQL("DROP INDEX IF EXISTS uq_pending_invite")
    }
}

