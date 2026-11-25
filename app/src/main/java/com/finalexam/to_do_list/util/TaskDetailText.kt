package com.finalexam.to_do_list.util

import com.finalexam.to_do_list.model.local.relation.HomeTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation only; uses stored reminder data rather than the Figma sample values. */
object TaskDetailText {
    private val timestampFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")

    fun due(task: HomeTask, zone: ZoneId = ZoneId.systemDefault()): String = due(task.dueAt, zone)

    internal fun due(at: Long?, zone: ZoneId): String = at?.let { time(it, zone) } ?: "Không có hạn"

    internal fun time(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).format(timestampFormat)

    fun reminder(task: HomeTask, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = task.remindAt ?: return "Không nhắc"
        val offset = task.dueAt?.minus(at)
        val text = when {
            offset == 0L -> "Đúng giờ"
            offset != null && offset > 0 && offset % 86_400_000L == 0L -> "Trước ${offset / 86_400_000L} ngày"
            offset != null && offset > 0 && offset % 3_600_000L == 0L -> "Trước ${offset / 3_600_000L} giờ"
            offset != null && offset > 0 && offset % 60_000L == 0L -> "Trước ${offset / 60_000L} phút"
            else -> time(at, zone)
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
