package com.finalexam.to_do_list

import com.finalexam.to_do_list.util.TaskDefaults
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TaskDefaultsTest {
    @Test fun nextDayStartsAtMidnight() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val clock = Clock.fixed(Instant.parse("2026-11-19T10:45:30Z"), zone)
        assertEquals(Instant.parse("2026-11-19T17:00:00Z").toEpochMilli(), TaskDefaults.nextMidnight(clock))
    }
    @Test fun yearBoundary() {
        val clock = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"))
        assertEquals(Instant.parse("2026-12-31T17:00:00Z").toEpochMilli(), TaskDefaults.nextMidnight(clock))
    }
    @Test fun usesCalendarDayAcrossDst() {
        val clock = Clock.fixed(Instant.parse("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), TaskDefaults.nextMidnight(clock))
    }
    @Test fun unclassifiedIsFirstChoice() {
        assertEquals("to do", TaskDefaults.categories.first())
        assertEquals(1, TaskDefaults.categories.count { it == "to do" })
    }
}
