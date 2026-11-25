package com.finalexam.to_do_list

import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.home.SyncConflictField
import com.finalexam.to_do_list.controller.home.buildSyncConflictComparison
import com.finalexam.to_do_list.controller.home.restoreConflictReason
import com.finalexam.to_do_list.model.repository.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SyncConflictComparisonTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val due = Instant.parse("2026-11-24T17:00:00Z").toEpochMilli()
    private val labels = mapOf(
        R.string.task_detail_due to "Đến hạn",
        R.string.task_detail_no_description to "Chưa có mô tả.",
        R.string.task_detail_done to "Đã hoàn thành",
        R.string.task_detail_pending to "Chưa hoàn thành",
        R.string.category_appearance_icon to "Biểu tượng",
        R.string.category_appearance_color to "Màu",
        R.string.category_icon_plus to "Dấu cộng",
        R.string.category_icon_book to "Sách",
        R.string.category_color_blue to "Xanh dương",
        R.string.category_color_green to "Xanh lá",
        R.string.sync_conflict_restore_missing_server to "Công việc này không còn trên máy chủ.",
        R.string.sync_conflict_restore_missing to "Không tìm thấy công việc để khôi phục.",
        R.string.sync_conflict_restore_not_in_trash to "Công việc hiện không nằm trong thùng rác."
    )

    @Test fun taskValuesUseReadableLabelsAndOnlyDisplayDraftFields() {
        val current = mapOf("title" to "Nộp bài", "description" to "", "due_at" to due, "priority" to 2L,
            "is_done" to 0L, "repeat_type" to "NONE")
        val draft = mapOf("title" to "Nộp bài nhóm", "description" to "", "due_at" to due, "priority" to 3,
            "repeat_type" to "WEEKLY", "timezone" to zone.id, "repeat_anchor_day" to null, "command_type" to "UPDATE")
        val comparison = compare("TASK", "UPDATE", current, draft)

        assertNull(comparison.summary)
        assertEquals(listOf(
            SyncConflictField("Tên công việc", "Nộp bài", "Nộp bài nhóm", true, "title"),
            SyncConflictField("Mô tả", "Chưa có mô tả.", "Chưa có mô tả.", false, "description"),
            SyncConflictField("Đến hạn", "25/11/2026, 00:00", "25/11/2026, 00:00", false, "due_at"),
            SyncConflictField("Ưu tiên", "Trung bình", "Cao", true, "priority"),
            SyncConflictField("Lặp lại", "Không lặp", "Hằng tuần", true, "repeat_type")
        ), comparison.fields)
    }

    @Test fun explicitNullDueDateMeansRemovingTheDueDate() {
        val comparison = compare("TASK", "UPDATE", mapOf("due_at" to due), mapOf("due_at" to null))
        assertEquals(SyncConflictField("Đến hạn", "25/11/2026, 00:00", "Không có hạn", true, "due_at"), comparison.fields.single())
    }

    @Test fun completionHandlesRoomAndDraftBooleanRepresentations() {
        val comparison = compare("TASK", "COMPLETE", mapOf("is_done" to 0L), mapOf("is_done" to true))
        assertEquals(SyncConflictField("Hoàn thành", "Chưa hoàn thành", "Đã hoàn thành", true, "is_done"), comparison.fields.single())
    }

    @Test fun missingCurrentKeepsDraftVisible() {
        val comparison = compare("TASK", "UPDATE", null, mapOf("title" to "Bản nháp"))
        assertEquals("Chưa có bản hiện tại để so sánh.", comparison.summary)
        assertEquals(SyncConflictField("Tên công việc", "—", "Bản nháp", false, "title"), comparison.fields.single())
    }

    @Test fun trashAndReminderActionsCompareResultingStates() {
        val restore = compare("TASK", "RESTORE", mapOf("deleted_at" to due), null)
        assertEquals(SyncConflictField("Trạng thái lưu trữ", "Trong thùng rác", "Đang sử dụng", true), restore.fields.single())
        val reminder = compare("REMINDER", "REMOVE_REMINDER", mapOf("remind_at" to due), null)
        assertEquals(SyncConflictField("Nhắc nhở", "25/11/2026, 00:00", "Không nhắc", true), reminder.fields.single())
    }

    @Test fun missingRestoresUseSavedContext() {
        val row = mapOf("entity_type" to "SUBTASK", "kind" to "RESTORE", "last_error" to "NOT_FOUND",
            "restore_title" to "Công việc con C", "target_label" to "Công việc cha", "category_label" to "Học tập",
            "current" to mapOf("deleted_at" to null))
        val details = buildSyncConflictComparison(row, zone, labels::getValue)

        assertEquals(R.string.sync_conflict_restore_missing_server, restoreConflictReason(row))
        assertEquals(R.string.sync_conflict_restore_title, details.title)
        assertEquals("Công việc con C\n\nHọc tập\n\nCông việc này không còn trên máy chủ.", details.summary)
        assertTrue(details.fields.isEmpty())
    }

    @Test fun legacyRestoreReportsMissingUnlessCurrentExists() {
        val row = mapOf("entity_type" to "TASK", "kind" to "RESTORE", "last_error" to "NOT_IN_TRASH")
        val missing = buildSyncConflictComparison(row, zone, labels::getValue)
        val found = buildSyncConflictComparison(row + ("current" to mapOf("deleted_at" to null)), zone, labels::getValue)

        assertEquals(R.string.sync_conflict_restore_missing, restoreConflictReason(row))
        assertEquals("Không tìm thấy công việc để khôi phục.", missing.summary)
        assertEquals("Công việc hiện không nằm trong thùng rác.", found.summary)
        assertTrue(missing.fields.isEmpty() && found.fields.isEmpty())
        assertNull(restoreConflictReason(row + ("kind" to "UPDATE")))
    }

    @Test fun categoryUsesExistingIconAndPaletteNames() {
        val comparison = compare("CATEGORY", "UPDATE",
            mapOf("icon" to null, "color" to null), mapOf("icon" to "book", "color" to "#287853"))
        assertEquals(listOf("Dấu cộng", "Xanh dương"), comparison.fields.map { it.current })
        assertEquals(listOf("Sách", "Xanh lá"), comparison.fields.map { it.draft })
        assertEquals(listOf("icon", "color"), comparison.fields.map { it.key })
        assertTrue(comparison.fields.all { it.changed })
    }

    private fun compare(type: String, kind: String, current: Row?, draft: Row?) =
        buildSyncConflictComparison(mapOf("entity_type" to type, "kind" to kind, "current" to current, "draft" to draft),
            zone, labels::getValue)
}
