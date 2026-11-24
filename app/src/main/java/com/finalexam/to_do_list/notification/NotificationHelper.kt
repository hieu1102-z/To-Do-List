package com.finalexam.to_do_list.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.finalexam.to_do_list.R

object NotificationHelper {

    private const val CHANNEL_ID = "task_reminders"
    private const val CHANNEL_NAME = "Nhắc nhở công việc"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi đến thời hạn công việc"
            }

            val manager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        taskId: String,
        taskTitle: String,
        uid: String
    ) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Đến giờ thực hiện công việc!")
            .setContentText(taskTitle)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Công việc \"$taskTitle\" đã đến thời hạn.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            uid,
            taskId.hashCode(),
            notification
        )
    }
}
