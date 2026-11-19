package com.finalexam.to_do_list.util

import com.finalexam.to_do_list.model.local.relation.HomeTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation only; uses stored reminder data rather than the Figma sample values. */
object TaskDetailText {
    fun due(task: HomeTask, zone: ZoneId = ZoneId.systemDefault()): String = task.dueAt?.let {
        Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))
    } ?: "Không có hạn"

    fun reminder(task: HomeTask, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = task.remindAt ?: return "Không nhắc"
        val offset = task.dueAt?.minus(at)
        val text = when {
            offset == 0L -> "Đúng giờ"
            offset != null && offset > 0 && offset % 86_400_000L == 0L -> "Trước ${offset / 86_400_000L} ngày"
            offset != null && offset > 0 && offset % 3_600_000L == 0L -> "Trước ${offset / 3_600_000L} giờ"
            offset != null && offset > 0 && offset % 60_000L == 0L -> "Trước ${offset / 60_000L} phút"
            else -> Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))
        }
        return if (task.reminderEnabled == false) "$text (đã tắt)" else text
    }

    fun repeat(type: String): String = when (type) {
        "DAILY" -> "Hằng ngày"
        "WEEKLY" -> "Hằng tuần"
        "MONTHLY" -> "Hằng tháng"
        else -> "Không lặp"
    }
}
