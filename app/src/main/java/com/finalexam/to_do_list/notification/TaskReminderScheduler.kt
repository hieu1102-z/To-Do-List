package com.finalexam.to_do_list.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object TaskReminderScheduler {

    private fun pendingIntent(
        context: Context,
        taskId: String,
        title: String
    ): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            putExtra("task_id", taskId)
            putExtra("task_title", title)
            data = Uri.parse("todolist://reminder/$taskId")
        }

        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            return alarmManager.canScheduleExactAlarms()
        }

        return true
    }

    fun schedule(
        context: Context,
        taskId: String,
        title: String,
        remindAt: Long
    ): Boolean {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (remindAt <= System.currentTimeMillis()) {
            cancel(context, taskId, title)
            return false
        }

        if (!canScheduleExactAlarms(context)) {
            return false
        }

        val operation = pendingIntent(context, taskId, title)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            remindAt,
            operation
        )

        return true
    }

    fun cancel(
        context: Context,
        taskId: String,
        title: String
    ) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val operation = pendingIntent(context, taskId, title)

        alarmManager.cancel(operation)
        operation.cancel()
    }
}
