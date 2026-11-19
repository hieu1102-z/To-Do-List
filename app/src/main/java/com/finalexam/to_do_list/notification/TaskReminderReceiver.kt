package com.finalexam.to_do_list.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id")
        val taskTitle = intent.getStringExtra("task_title")

        if (taskId.isNullOrBlank() || taskTitle.isNullOrBlank()) {
            return
        }

        // Đánh dấu có thông báo mới chưa đọc
        NotificationPreferences.setUnreadNotification(context, true)

        NotificationHelper.show(
            context,
            taskId,
            taskTitle
        )
    }
}
