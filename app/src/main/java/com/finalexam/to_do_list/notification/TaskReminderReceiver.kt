package com.finalexam.to_do_list.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.repository.WorkspaceSync
import com.finalexam.to_do_list.model.repository.rows
import java.util.concurrent.Executors

class TaskReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMINDER = "com.finalexam.to_do_list.ACTION_TASK_REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        private val io = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)

        if (taskId.isNullOrBlank() || taskTitle.isNullOrBlank()) {
            return
        }

        val uid = intent.getStringExtra("account_uid")
        if (uid != null) {
            val scheduledAt = intent.getLongExtra("scheduled_at", 0)
            val pending = goAsync()
            io.execute {
                try {
                    if (WorkspaceSync.current(context) != uid || !NotificationPreferences.isEnabled(context)) return@execute
                    val db = AppDatabase.accountBuilder(context, uid).build()
                    try {
                        val row = db.openHelper.readableDatabase.rows("""
                            SELECT t.title FROM task_reminders r JOIN tasks t ON t.id=r.task_id
                            JOIN categories c ON c.id=t.category_id JOIN category_members m ON m.category_id=c.id AND m.user_uid=r.user_uid
                            WHERE r.task_id=? AND r.user_uid=? AND r.remind_at=? AND r.is_enabled=1 AND r.deleted_at IS NULL
                              AND t.deleted_at IS NULL AND c.deleted_at IS NULL AND t.is_done=0 AND m.status='ACTIVE'
                        """.trimIndent(), taskId, uid, scheduledAt).firstOrNull()
                        val title = row?.get("title") as? String
                        if (title != null && WorkspaceSync.current(context) == uid) {
                            NotificationHelper.show(context, taskId, title, uid)
                        }
                    } finally { db.close() }
                } catch (error: android.database.SQLException) {
                    android.util.Log.w("ReminderReceiver", "Account reminder could not be checked", error)
                } finally { pending.finish() }
            }
            return
        }

        // Đánh dấu có thông báo mới chưa đọc
        NotificationPreferences.setUnreadNotification(context, true)

        context.sendBroadcast(Intent(ACTION_REMINDER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
        })

        NotificationHelper.show(
            context,
            taskId,
            taskTitle
        )
    }
}
