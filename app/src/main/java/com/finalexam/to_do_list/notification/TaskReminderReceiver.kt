package com.finalexam.to_do_list.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMINDER = "com.finalexam.to_do_list.ACTION_TASK_REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)

        if (taskId.isNullOrBlank() || taskTitle.isNullOrBlank()) {
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
