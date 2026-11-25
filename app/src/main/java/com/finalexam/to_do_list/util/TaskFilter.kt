package com.finalexam.to_do_list.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TaskFilter(val label: String) {
    ALL("Tất cả"), TODAY("Hôm nay"), WEEK("7 ngày tới"), OVERDUE("Quá hạn"), DONE("Đã xong");

    fun includes(done: Boolean, dueAt: Long?, today: LocalDate, zone: ZoneId, now: Long = System.currentTimeMillis()): Boolean {
        if (done) return this == DONE
        val overdue = dueAt != null && dueAt < now
        val date = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        return when (this) {
            ALL -> !overdue
            TODAY -> date == null || date == today
            WEEK -> date != null && !date.isBefore(today) && date.isBefore(today.plusDays(7))
            OVERDUE -> overdue
            DONE -> false
        }
    }
}
