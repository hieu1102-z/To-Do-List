package com.finalexam.to_do_list

import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.util.TaskDetailText
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TaskDetailTextTest {
    private val task = HomeTask("id", "Title", "", "category", "to do", 3_600_000L,
        2, false, null, 0, 0)

    @Test fun noReminderAndNoDue() {
        assertEquals("Không nhắc", TaskDetailText.reminder(task))
        assertEquals("Không có hạn", TaskDetailText.due(task.copy(dueAt = null)))
        assertEquals("01/01/1970, 01:00", TaskDetailText.due(task, ZoneId.of("UTC")))
    }
    @Test fun realReminderOffset() {
        assertEquals("Trước 1 giờ", TaskDetailText.reminder(task.copy(remindAt = 0, reminderEnabled = true)))
        assertEquals("Trước 15 phút", TaskDetailText.reminder(task.copy(remindAt = 2_700_000L, reminderEnabled = true)))
        assertEquals("Đúng giờ (đã tắt)", TaskDetailText.reminder(task.copy(remindAt = task.dueAt, reminderEnabled = false)))
    }
    @Test fun repeatLabels() {
        assertEquals("Không lặp", TaskDetailText.repeat("NONE"))
        assertEquals("Hằng ngày", TaskDetailText.repeat("DAILY"))
        assertEquals("Hằng tuần", TaskDetailText.repeat("WEEKLY"))
        assertEquals("Hằng tháng", TaskDetailText.repeat("MONTHLY"))
    }
}
