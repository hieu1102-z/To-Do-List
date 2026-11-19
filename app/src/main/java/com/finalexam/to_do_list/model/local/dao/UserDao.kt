package com.finalexam.to_do_list.model.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.finalexam.to_do_list.model.local.entity.UserEntity

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE uid=:uid") fun find(uid: String): UserEntity?
    // REPLACE would delete the parent row and can break its existing foreign keys.
    @Upsert fun upsert(user: UserEntity)
}
