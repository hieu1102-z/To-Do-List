package com.finalexam.to_do_list.util

import java.time.Clock
import java.time.LocalDate

object TaskDefaults {
    const val UNCATEGORIZED = "to do"
    val categories = listOf(UNCATEGORIZED, "Học tập", "Công việc", "Cá nhân", "Mua sắm")

    /** Calendar midnight in the device's zone, including month/year and DST boundaries. */
    fun nextMidnight(clock: Clock = Clock.systemDefaultZone()): Long =
        LocalDate.now(clock).plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
}
