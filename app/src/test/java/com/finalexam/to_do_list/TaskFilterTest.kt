package com.finalexam.to_do_list

import com.finalexam.to_do_list.util.TaskFilter
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskFilterTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val today = LocalDate.of(2026, 11, 17)
    private fun due(offset: Long, hour: Int = 12) = today.plusDays(offset).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private val now get() = due(0)
    private fun matches(filter: TaskFilter, done: Boolean = false, at: Long?) = filter.includes(done, at, today, zone, now)

    @Test fun completedTasksOnlyAppearInDone() {
        TaskFilter.entries.filter { it != TaskFilter.DONE }.forEach { assertFalse(matches(it, true, due(0))) }
        assertTrue(matches(TaskFilter.DONE, true, null))
        assertFalse(matches(TaskFilter.DONE, false, due(0)))
    }

    @Test fun weekHasExactlySevenLocalCalendarDays() {
        assertFalse(matches(TaskFilter.WEEK, at = due(-1)))
        assertTrue(matches(TaskFilter.WEEK, at = due(0)))
        assertTrue(matches(TaskFilter.WEEK, at = due(6, 23)))
        assertFalse(matches(TaskFilter.WEEK, at = due(7, 0)))
    }

    @Test fun overdueIncludesEarlierTodayButNotFutureOrUnscheduled() {
        assertTrue(matches(TaskFilter.OVERDUE, at = due(0, 8)))
        assertTrue(matches(TaskFilter.OVERDUE, at = due(-1)))
        assertFalse(matches(TaskFilter.OVERDUE, at = due(0, 13)))
        assertFalse(matches(TaskFilter.OVERDUE, at = null))
    }

    @Test fun todayUsesDeviceTimezoneAndKeepsUnscheduledWorkVisible() {
        assertTrue(matches(TaskFilter.TODAY, at = null))
        assertTrue(matches(TaskFilter.TODAY, at = due(0, 0)))
        assertTrue(matches(TaskFilter.TODAY, at = due(0, 23)))
        assertFalse(matches(TaskFilter.TODAY, at = due(1, 0)))
    }
}
