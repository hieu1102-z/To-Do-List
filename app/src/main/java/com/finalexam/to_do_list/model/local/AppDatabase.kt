package com.finalexam.to_do_list.model.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.finalexam.to_do_list.model.local.dao.TaskDao
import com.finalexam.to_do_list.model.local.dao.UserDao
import com.finalexam.to_do_list.model.local.entity.*
import java.security.MessageDigest

@Database(entities = [UserEntity::class, CategoryEntity::class, CategoryMemberEntity::class,
    CategoryInvitationEntity::class, TaskEntity::class, TaskParticipantEntity::class,
    SubtaskEntity::class, TaskReminderEntity::class, SyncQueueEntity::class, SyncStateEntity::class],
    version = 6, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun users(): UserDao

    companion object {
        fun accountFileName(uid: String): String {
            require(uid.isNotBlank() && !uid.startsWith("local:"))
            val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "account-$hash.db"
        }

        fun accountBuilder(context: Context, uid: String): Builder<AppDatabase> =
            builder(context, accountFileName(uid))

        internal fun builder(context: Context, name: String): Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        SchemaSql.replaceEmptyRoomTables(db)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Configure the whole SQLite connection pool, including WAL readers.
                        // Room's generated PRAGMA only configures its current connection.
                        db.setForeignKeyConstraintsEnabled(true)
                        db.beginTransaction()
                        try {
                            SchemaSql.installPartialIndexes(db)
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    }
                })

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaSql.removePartialIndexesForMigration(db)
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaSql.removePartialIndexesForMigration(db)
                db.execSQL("DROP TABLE IF EXISTS local_category_copies")
                db.execSQL("DROP TABLE IF EXISTS local_category_transfers")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaSql.removePartialIndexesForMigration(db)
                val columns = db.query("PRAGMA table_info(sync_queue)").use { rows ->
                    buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
                }
                if ("predecessor_id" !in columns) db.execSQL("ALTER TABLE sync_queue ADD COLUMN predecessor_id TEXT")
                if ("dispatched" !in columns) db.execSQL("ALTER TABLE sync_queue ADD COLUMN dispatched INTEGER NOT NULL DEFAULT 0")
                if ("membership_revision" !in columns) db.execSQL("ALTER TABLE sync_queue ADD COLUMN membership_revision INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaSql.removePartialIndexesForMigration(db)
                listOf("categories", "tasks", "subtasks", "task_reminders", "category_invitations").forEach { table ->
                    var hasDeletedAt = false
                    db.query("PRAGMA table_info(`$table`)").use { cursor ->
                        while (cursor.moveToNext()) {
                            if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "deleted_at") hasDeletedAt = true
                        }
                    }
                    if (!hasDeletedAt) db.execSQL("ALTER TABLE `$table` ADD COLUMN `deleted_at` INTEGER")
                }
            }
        }
    }
}
