package com.finalexam.to_do_list.notification

import android.content.Context

object NotificationPreferences {

    private const val PREF_NAME = "notification_settings"
    private const val KEY_ENABLED = "notifications_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private const val KEY_UNREAD = "notification_unread"

    fun hasUnreadNotification(context: Context): Boolean {
        return context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(KEY_UNREAD, false)
    }

    fun setUnreadNotification(
        context: Context,
        unread: Boolean
    ) {
        context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(KEY_UNREAD, unread)
            .apply()
    }

    private const val KEY_DISMISSED_REMINDERS = "dismissed_reminders"

    fun dismissedReminders(context: Context): Set<String> = context.getSharedPreferences(
        PREF_NAME, Context.MODE_PRIVATE
    ).getStringSet(KEY_DISMISSED_REMINDERS, emptySet())?.toSet() ?: emptySet()

    fun dismissReminders(context: Context, taskIds: Set<String>) {
        if (taskIds.isEmpty()) return
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val dismissed = preferences.getStringSet(KEY_DISMISSED_REMINDERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        dismissed.addAll(taskIds)
        preferences.edit().putStringSet(KEY_DISMISSED_REMINDERS, dismissed).apply()
    }
}
