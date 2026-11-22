package com.finalexam.to_do_list.model.repository

import android.content.Context

object GuestProfilePreferences {
    private const val PREFS = "guest_profile"
    private const val NAME = "display_name"
    private const val AVATAR = "avatar_uri"

    fun name(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(NAME, null)?.takeIf { it.isNotBlank() }

    fun saveName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NAME, name.trim()).apply()
    }

    fun avatar(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(AVATAR, null)

    fun saveAvatar(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(AVATAR, uri).apply()
    }
}
