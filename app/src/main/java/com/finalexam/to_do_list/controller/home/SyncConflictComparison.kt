package com.finalexam.to_do_list.controller.home

import androidx.annotation.StringRes
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.util.TaskDetailText
import java.time.ZoneId

internal data class SyncConflictField(
    val label: String,
    val current: String,
    val draft: String,
    val changed: Boolean,
    val key: String? = null
)

internal data class SyncConflictComparison(
    val summary: String?,
    val fields: List<SyncConflictField>,
    @get:StringRes val title: Int = R.string.sync_conflict_compare_title
)

@StringRes
internal fun restoreConflictReason(row: Row): Int? {
    if (row["kind"] != "RESTORE" || row["entity_type"] !in setOf("TASK", "SUBTASK")) return null
    return when (row["last_error"]) {
        "NOT_FOUND" -> R.string.sync_conflict_restore_missing_server
        "NOT_IN_TRASH" -> if (row["current"] == null) {
            R.string.sync_conflict_restore_missing
        } else {
            R.string.sync_conflict_restore_not_in_trash
        }
        else -> null
    }
}

internal fun buildSyncConflictComparison(
    row: Row,
    zone: ZoneId,
    getString: (Int) -> String
): SyncConflictComparison {
    restoreConflictReason(row)?.let { reason ->
        val name = (row["restore_title"] as? String)?.takeIf(String::isNotBlank)
            ?: (row["target_label"] as? String)?.takeIf(String::isNotBlank)
        val category = (row["category_label"] as? String)?.takeIf(String::isNotBlank)
        val parts = listOfNotNull(name, category, getString(reason))
        return SyncConflictComparison(parts.joinToString("\n\n"), emptyList(), R.string.sync_conflict_restore_title)
    }
    val current = row["current"] as? Map<*, *>
    val draft = row["draft"] as? Map<*, *>
    val fields = mutableListOf<SyncConflictField>()

    fun add(label: String, currentValue: String?, draftValue: String, key: String? = null) {
        fields += SyncConflictField(label, currentValue ?: "—", draftValue, currentValue != null && currentValue != draftValue, key)
    }

    fun compare(key: String, label: String, format: (Any?) -> String) {
        if (draft?.containsKey(key) != true) return
        val currentValue = if (current?.containsKey(key) == true) format(current[key]) else null
        add(label, currentValue, format(draft[key]), key)
    }

    fun completion(value: Any?): String = getString(
        if (value.isEnabled()) R.string.task_detail_done else R.string.task_detail_pending
    )

    fun reminder(value: Any?): String =
        (value as? Number)?.toLong()?.let { TaskDetailText.time(it, zone) } ?: "Không nhắc"

    when (row["entity_type"]) {
        "TASK" -> {
            compare("title", "Tên công việc", ::displayText)
            compare("description", "Mô tả") {
                (it as? String)?.takeIf(String::isNotBlank) ?: getString(R.string.task_detail_no_description)
            }
            compare("due_at", getString(R.string.task_detail_due)) { TaskDetailText.due((it as? Number)?.toLong(), zone) }
            compare("priority", "Ưu tiên") {
                when ((it as? Number)?.toInt()) {
                    1 -> "Thấp"
                    2 -> "Trung bình"
                    3 -> "Cao"
                    else -> "Chưa đặt"
                }
            }
            compare("repeat_type", "Lặp lại") { TaskDetailText.repeat(it as? String ?: "NONE") }
            compare("is_done", "Hoàn thành", ::completion)
        }
        "SUBTASK" -> {
            compare("title", getString(R.string.subtask_name), ::displayText)
            compare("is_done", "Hoàn thành", ::completion)
        }
        "CATEGORY" -> {
            compare("name", "Tên danh mục", ::displayText)
            compare("icon", getString(R.string.category_appearance_icon)) {
                getString(CategoryIcon.fromKey(it as? String).label)
            }
            compare("color", getString(R.string.category_appearance_color)) {
                val hex = it as? String
                val color = CategoryColor.entries.firstOrNull { option -> option.hex.equals(hex, ignoreCase = true) }
                when {
                    color != null -> getString(color.label)
                    hex.isNullOrBlank() -> getString(CategoryColor.BLUE.label)
                    else -> "Màu tùy chỉnh ($hex)"
                }
            }
        }
        "REMINDER" -> {
            compare("remind_at", "Nhắc nhở", ::reminder)
            compare("is_enabled", "Thông báo") { if (it.isEnabled()) "Đã bật" else "Đã tắt" }
        }
    }

    when (row["kind"]) {
        "TRASH", "RESTORE", "PURGE" -> {
            val currentState = current?.let { if (it["deleted_at"] == null) "Đang sử dụng" else "Trong thùng rác" }
            val draftState = when (row["kind"]) {
                "TRASH" -> "Trong thùng rác"
                "RESTORE" -> "Đang sử dụng"
                else -> "Đã xóa vĩnh viễn"
            }
            add("Trạng thái lưu trữ", currentState, draftState)
        }
        "REMOVE_REMINDER" -> add("Nhắc nhở", current?.let { reminder(it["remind_at"]) }, "Không nhắc")
        "WITHDRAW" -> add("Người nhận", current?.let { "Bạn đang nhận" }, "Rút khỏi công việc")
        "CREATE" -> if (row["entity_type"] == "PARTICIPANT") {
            add("Người nhận", current?.let { "Bạn đang nhận" }, "Nhận công việc")
        }
        "ENSURE_DEFAULT_CATEGORY" -> add("Danh mục", current?.let { displayText(it["name"]) }, "Tạo danh mục mặc định")
    }

    val summary = when {
        current == null -> "Chưa có bản hiện tại để so sánh."
        fields.isEmpty() -> "Thao tác này không có trường dữ liệu để so sánh."
        else -> null
    }
    return SyncConflictComparison(summary, fields)
}

private fun displayText(value: Any?): String = (value as? String)?.takeIf(String::isNotBlank) ?: "Để trống"

private fun Any?.isEnabled(): Boolean = this == true || this is Number && toInt() == 1
