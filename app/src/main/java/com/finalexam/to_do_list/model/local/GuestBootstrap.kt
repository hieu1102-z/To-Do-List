package com.finalexam.to_do_list.model.local

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale
import java.util.UUID

/** The local UID exception is confined to guest-workspace.db and never authenticated. */
internal object GuestBootstrap {
    val categoryNames = listOf("Cá nhân", "Học tập", "Công việc", "Mua sắm")
    fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    fun ensureUser(db: SupportSQLiteDatabase): String {
        val users = mutableListOf<String>()
        db.query("SELECT uid FROM users WHERE uid LIKE 'local:%'").use { while (it.moveToNext()) users.add(it.getString(0)) }
        check(users.size <= 1) { "Guest workspace contains multiple local identities." }
        return users.singleOrNull() ?: "local:${UUID.randomUUID()}".also { uid ->
            db.execSQL("INSERT INTO users(uid,email,email_key,full_name) VALUES(?, '', '', 'Khách')", arrayOf<Any?>(uid))
        }
    }

    fun ensureCategory(db: SupportSQLiteDatabase, uid: String, name: String, now: Long): String {
        val key = nameKey(name)
        db.query("SELECT id FROM categories WHERE owner_uid=? AND name_key=? AND deleted_at IS NULL", arrayOf<Any?>(uid, key)).use {
            if (it.moveToFirst()) return it.getString(0)
        }
        val id = UUID.randomUUID().toString()
        db.execSQL("INSERT INTO categories(id,owner_uid,name,name_key,created_at,client_updated_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(id, uid, name.trim(), key, now, now))
        db.execSQL("INSERT INTO category_members(category_id,user_uid,role,joined_at) VALUES(?,?,'OWNER',?)", arrayOf<Any?>(id, uid, now))
        return id
    }

    fun initialize(db: SupportSQLiteDatabase): String {
        val uid = ensureUser(db)
        val now = System.currentTimeMillis()
        categoryNames.forEach { ensureCategory(db, uid, it, now) }
        return uid
    }
}
