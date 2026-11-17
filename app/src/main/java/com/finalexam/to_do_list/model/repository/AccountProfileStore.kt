package com.finalexam.to_do_list.model.repository

import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.entity.UserEntity
import com.finalexam.to_do_list.model.remote.AuthUser
import java.util.Locale

fun interface AccountProfileStore { fun cache(user: AuthUser) }

/** Local profile cache only. Does not create server business data or touch the guest DB. */
class RoomAccountProfileStore(private val databaseFor: (String) -> AppDatabase) : AccountProfileStore {
    override fun cache(user: AuthUser) {
        require(user.uid.isNotBlank() && !user.uid.startsWith("local:"))
        require(user.email.isNotBlank())
        val database = databaseFor(user.uid)
        try {
            database.runInTransaction {
                val old = database.users().find(user.uid)
                database.users().upsert(UserEntity(uid = user.uid, email = user.email.trim(),
                    emailKey = user.email.trim().lowercase(Locale.ROOT), fullName = user.displayName.orEmpty(),
                    emailVerified = user.emailVerified, avatarUrl = user.avatarUrl,
                    serverUpdatedAt = old?.serverUpdatedAt))
            }
        } finally { database.close() }
    }
}
