package com.finalexam.to_do_list.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.finalexam.to_do_list.model.repository.AccountSession
import com.finalexam.to_do_list.model.repository.rows
import java.util.concurrent.Executors

class TaskReminderReceiver : BroadcastReceiver() {
    companion object {
        private val io = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        val uid = intent.getStringExtra("account_uid") ?: return
        val scheduledAt = intent.getLongExtra("scheduled_at", 0)
        val pending = goAsync()
        io.execute {
            try {
                val session = AccountSession.restore(context)?.takeIf { it.uid == uid } ?: return@execute
                if (!NotificationPreferences.isEnabled(context)) return@execute
                AccountSession.access(context, session) {
                    val row = sql.rows("""
                        SELECT t.title FROM task_reminders r JOIN tasks t ON t.id=r.task_id
                        JOIN categories c ON c.id=t.category_id
                        JOIN category_members m ON m.category_id=c.id AND m.user_uid=r.user_uid
                        WHERE r.task_id=? AND r.user_uid=? AND r.remind_at=? AND r.is_enabled=1 AND r.deleted_at IS NULL
                          AND t.deleted_at IS NULL AND c.deleted_at IS NULL AND t.is_done=0 AND m.status='ACTIVE'
                    """.trimIndent(), taskId, uid, scheduledAt).firstOrNull()
                    val title = row?.get("title") as? String
                    if (title != null && AccountSession.current(context) === session) {
                        NotificationHelper.show(context, taskId, title, uid)
                    }
                }
            } catch (error: android.database.SQLException) {
                android.util.Log.w("ReminderReceiver", "Account reminder could not be checked", error)
            } catch (error: IllegalStateException) {
                android.util.Log.d("ReminderReceiver", "Account session changed", error)
            } finally { pending.finish() }
        }
    }
}
