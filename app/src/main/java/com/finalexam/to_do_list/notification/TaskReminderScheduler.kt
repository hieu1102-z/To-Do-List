package com.finalexam.to_do_list.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object TaskReminderScheduler {
    private fun intent(context: Context, id: String, title: String, uid: String?, at: Long): PendingIntent {
        val broadcast = Intent(context, TaskReminderReceiver::class.java).apply {
            putExtra("task_id", id)
            putExtra("task_title", title)
            putExtra("account_uid", uid)
            putExtra("scheduled_at", at)
            data = if (uid == null) Uri.parse("todolist://reminder/$id") else
                Uri.Builder().scheme("todolist").authority("reminder").appendPath(uid).appendPath(id).build()
        }
        return PendingIntent.getBroadcast(context, 0, broadcast,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)
    fun canScheduleExactAlarms(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(context).canScheduleExactAlarms()

    fun schedule(context: Context, taskId: String, title: String, remindAt: Long, uid: String? = null): Boolean {
        if (remindAt <= System.currentTimeMillis()) {
            cancel(context, taskId, title, uid)
            return false
        }
        val alarm = manager(context)
        val operation = intent(context, taskId, title, uid, remindAt)
        return try {
            if (canScheduleExactAlarms(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
            } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
            true
        } catch (_: SecurityException) {
            runCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation) }.isSuccess
        }
    }

    fun cancel(context: Context, taskId: String, title: String, uid: String? = null) {
        val operation = intent(context, taskId, title, uid, 0)
        manager(context).cancel(operation)
        operation.cancel()
    }
}
