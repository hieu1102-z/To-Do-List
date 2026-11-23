package com.finalexam.to_do_list.notification

import android.content.Context
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import com.finalexam.to_do_list.model.repository.AccountWorkspace
import com.finalexam.to_do_list.model.repository.WorkspaceSync
import com.finalexam.to_do_list.model.repository.long
import com.finalexam.to_do_list.model.repository.rows
import com.finalexam.to_do_list.model.repository.string

internal object AccountReminders {
    private fun prefs(context: Context) = context.getSharedPreferences("account_reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun reconcile(context: Context, store: AccountWorkspace) {
        val uid = store.uid
        if (WorkspaceSync.current(context) != uid) return
        val active = if (NotificationPreferences.isEnabled(context)) store.sql.rows("""
            SELECT t.id,t.title,r.remind_at FROM task_reminders r JOIN tasks t ON t.id=r.task_id
            JOIN categories c ON c.id=t.category_id JOIN category_members m ON m.category_id=c.id AND m.user_uid=r.user_uid
            WHERE r.user_uid=? AND m.status='ACTIVE' AND r.deleted_at IS NULL AND r.is_enabled=1
              AND t.deleted_at IS NULL AND c.deleted_at IS NULL AND t.is_done=0 AND r.remind_at>?
        """.trimIndent(), uid, System.currentTimeMillis()) else emptyList()
        val present = active.map { it.string("id") }.toSet()
        (prefs(context).getStringSet(uid, emptySet()).orEmpty() - present).forEach {
            TaskReminderScheduler.cancel(context, it, "", uid)
            NotificationManagerCompat.from(context).cancel(uid, it.hashCode())
        }
        active.forEach {
            TaskReminderScheduler.schedule(context, it.string("id"), it.string("title"), it.long("remind_at"), uid)
        }
        prefs(context).edit().putStringSet(uid, present).apply()
    }

    @Synchronized
    fun cancel(context: Context, uid: String) {
        prefs(context).getStringSet(uid, emptySet()).orEmpty().forEach {
            TaskReminderScheduler.cancel(context, it, "", uid)
            NotificationManagerCompat.from(context).cancel(uid, it.hashCode())
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == uid }.forEach { manager.cancel(uid, it.id) }
        prefs(context).edit().remove(uid).apply()
    }
}
