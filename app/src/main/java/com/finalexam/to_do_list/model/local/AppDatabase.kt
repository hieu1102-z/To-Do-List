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
    version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun users(): UserDao

    companion object {
        const val GUEST_DATABASE_NAME = "guest-workspace.db"

        fun guestBuilder(context: Context, name: String = GUEST_DATABASE_NAME): Builder<AppDatabase> =
            builder(context, name, guest = true).addMigrations(MIGRATION_1_2)

        fun accountFileName(uid: String): String {
            require(uid.isNotBlank() && !uid.startsWith("local:"))
            val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "account-$hash.db"
        }

        fun accountBuilder(context: Context, uid: String): Builder<AppDatabase> =
            builder(context, accountFileName(uid), guest = false)

        private fun builder(context: Context, name: String, guest: Boolean): Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { SchemaSql.replaceEmptyRoomTables(db) }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Configure the whole SQLite connection pool, including WAL readers.
                        // Room's generated PRAGMA only configures its current connection.
                        db.setForeignKeyConstraintsEnabled(true)
                        db.beginTransaction()
                        try {
                            SchemaSql.installPartialIndexes(db)
                            if (guest) GuestBootstrap.initialize(db)
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    }
                })

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaSql.removePartialIndexesForMigration(db)
                SchemaSql.create(db)
                val uid = GuestBootstrap.initialize(db)
                var expected = 0L
                db.query("SELECT * FROM guest_tasks").use { rows ->
                    fun text(column: String) = rows.getString(rows.getColumnIndexOrThrow(column))
                    fun number(column: String) = rows.getLong(rows.getColumnIndexOrThrow(column))
                    fun optionalNumber(column: String): Long? = rows.getColumnIndexOrThrow(column).let { if (rows.isNull(it)) null else rows.getLong(it) }
                    while (rows.moveToNext()) {
                        val categoryId = GuestBootstrap.ensureCategory(db, uid, text("category"), System.currentTimeMillis())
                        db.execSQL("""INSERT INTO tasks(id,category_id,created_by_uid,title,description,due_at,priority,is_done,completed_at,created_at,client_updated_at)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(text("id"), categoryId, uid, text("title"), text("description"),
                            optionalNumber("dueAt"), number("priority"), number("done"), optionalNumber("completedAt"), number("createdAt"), number("updatedAt")))
                        expected++
                    }
                }
                db.query("SELECT COUNT(*) FROM tasks").use { check(it.moveToFirst() && it.getLong(0) == expected) { "Task migration count mismatch." } }
                db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Foreign key violation during migration." } }
                db.execSQL("DROP TABLE guest_tasks")
                // Room validates modeled columns/FKs/indexes next. WHERE indexes are added onOpen.
            }
        }
    }
}
