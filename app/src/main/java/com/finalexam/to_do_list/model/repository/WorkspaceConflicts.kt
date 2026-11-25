package com.finalexam.to_do_list.model.repository

import java.time.ZoneId

private val editableFields = mapOf(
    "TASK" to setOf("title", "description", "due_at", "priority", "repeat_type"),
    "SUBTASK" to setOf("title", "is_done"),
    "CATEGORY" to setOf("name", "icon", "color"),
    "REMINDER" to setOf("remind_at", "is_enabled")
)

private data class SavedConflict(val kind: String, val draft: Row?, val current: Row?, val fields: Set<String>)

internal fun AccountWorkspace.syncConflictRows(): List<Row> {
    val queue = commands()
    val categoryLabels = mutableMapOf<String, String?>()
    return queue.filter { it["status"] == "REJECTED" }.map { command ->
        val conflict = conflict(command, queue)
        val cid = command.string("category_id")
        val displayContext = if (conflict.kind == "RESTORE") command.restoreDisplayContext() else null
        val categoryLabel = categoryLabels.getOrPut(cid) { conflictCategoryLabel(cid) } ?: displayContext?.categoryLabel
        command + mapOf("current" to conflict.current, "kind" to conflict.kind, "draft" to conflict.draft,
            "target_label" to (conflictTargetLabel(command, conflict) ?: displayContext?.taskTitle),
            "category_label" to categoryLabel, "restore_title" to displayContext?.taskTitle,
            "reapply_fields" to conflict.fields, "default_category_pending" to (conflict.kind == "ENSURE_DEFAULT_CATEGORY"))
    }
}

private fun AccountWorkspace.conflictTargetLabel(command: Row, conflict: SavedConflict): String? {
    val type = command.string("entity_type")
    if (type == "CATEGORY") return null
    val currentTitle = (conflict.current?.get("title") as? String)?.takeIf(String::isNotBlank)
    if (type == "TASK" && currentTitle != null) return currentTitle
    val taskId = command.string("entity_id").substringBefore('/')
    val categoryId = command.string("category_id")
    val cachedTask = database.tasks().findIncludingDeleted(taskId)?.takeIf { it.categoryId == categoryId }
    return cachedTask?.title?.takeIf(String::isNotBlank) ?: currentTitle ?:
        (conflict.draft?.get("title") as? String)?.takeIf(String::isNotBlank)
}

internal fun AccountWorkspace.conflictCategoryLabel(cid: String): String? {
    if (cid == defaultCategoryId(uid)) return "Không có danh mục"
    val category = sql.rows("""
        SELECT c.name, c.owner_uid, u.full_name
        FROM categories c JOIN users u ON u.uid=c.owner_uid WHERE c.id=?
    """.trimIndent(), cid).firstOrNull() ?: return null
    val name = category.string("name")
    if (category["owner_uid"] == uid) return name
    val owner = category.string("full_name").takeIf(String::isNotBlank) ?: "Chưa rõ chủ sở hữu"
    return "$name · $owner"
}

internal fun AccountWorkspace.reapplyRejectedChange(operationId: String, expectedRevision: Long, fields: Set<String>): Unit =
    transaction {
        val queue = commands()
        val command = checkNotNull(queue.firstOrNull { it["operation_id"] == operationId && it["status"] == "REJECTED" }) {
            "Bản nháp này không còn khả dụng."
        }
        val conflict = conflict(command, queue)
        val current = conflict.current
        if (current == null || current.long("revision") != expectedRevision) {
            throw WorkspaceRequestError("REVISION_CONFLICT")
        }
        require(fields.isNotEmpty() && conflict.fields.containsAll(fields)) {
            "Các trường đã chọn chưa thể áp dụng. Hãy mở lại bản so sánh."
        }

        val cid = command.string("category_id")
        val type = command.string("entity_type")
        val route = command.string("entity_id")
        val selected = checkNotNull(conflict.draft).filterKeys { it in fields }
        if (type == "CATEGORY") {
            val category = categories().first { it.ref.id == cid }
            val merged = current + selected
            val updated = updateCategory(category, merged.string("name"), merged["color"] as? String, merged["icon"] as? String)
            check(updated != category) { "Danh mục đã có các giá trị bạn chọn." }
        } else {
            val now = System.currentTimeMillis()
            val payload = when {
                conflict.kind == "COMPLETE" -> mapOf("is_done" to flag(selected.getValue("is_done")))
                type == "TASK" -> taskConflictPatch(current, selected)
                else -> selected
            }
            val localFields = if (conflict.kind == "COMPLETE") {
                payload + ("completed_at" to now.takeIf { payload.long("is_done") == 1L })
            } else payload
            upsert(snapshotGroups.getValue(type), current + localFields +
                ("client_updated_at" to maxOf(now, current.long("client_updated_at") + 1)))
            enqueue(cid, type, route, conflict.kind, expectedRevision, payload)
        }

        sql.execSQL("DELETE FROM sync_queue WHERE actor_uid=? AND operation_id=? AND status='REJECTED'", arrayOf(uid, operationId))
    }

private fun AccountWorkspace.conflict(command: Row, queue: List<Row>): SavedConflict {
    val type = command.string("entity_type")
    val cid = command.string("category_id")
    val route = command.string("entity_id")
    val request = envelope(command)
    val kind = request.string("kind")
    val draft = request["payload"]?.asRow()
    val revisionConflict = command["last_error"] == "REVISION_CONFLICT"
    val legacyRestoreError = kind == "RESTORE" && type in setOf("TASK", "SUBTASK") &&
        command["last_error"] == "NOT_IN_TRASH"
    val current = conflictEntity(type, cid, route)?.takeUnless {
        revisionConflict && it.long("revision") == command.long("base_revision") ||
            legacyRestoreError && needsSnapshot(cid, category(cid)?.optionalLong("server_seq"))
    }
    val allowed = when {
        kind == "UPDATE" -> editableFields[type].orEmpty()
        kind == "COMPLETE" && type == "TASK" -> setOf("is_done")
        else -> emptySet()
    }
    if (!revisionConflict || draft == null || current == null || allowed.isEmpty()) {
        return SavedConflict(kind, draft, current, emptySet())
    }
    val category = category(cid)
    val role = role(cid)
    val editable = when (type) {
        "CATEGORY" -> role == "OWNER" && cid != defaultCategoryId(uid)
        "REMINDER" -> role != null
        else -> role == "OWNER" || role == "EDITOR"
    }
    val pending = queue.any { op ->
        op["status"] != "REJECTED" && op["category_id"] == cid && op["entity_type"] == type && op["entity_id"] == route
    }
    val fields = if (editable && !pending && current.long("revision") > 0 && category != null &&
        category["deleted_at"] == null && current["deleted_at"] == null && current["parent_deleted_at"] == null &&
        !needsSnapshot(cid, category.long("server_seq"))) {
        allowed.filterTo(linkedSetOf()) { key -> draft.containsKey(key) && comparable(draft[key]) != comparable(current[key]) }
    } else emptySet()
    return SavedConflict(kind, draft, current, fields)
}

private fun AccountWorkspace.conflictEntity(type: String, cid: String, route: String): Row? {
    val id = route.substringAfterLast('/')
    val taskId = route.substringBefore('/')
    return when (type) {
        "CATEGORY" -> category(cid)
        "TASK" -> sql.rows("SELECT * FROM tasks WHERE id=? AND category_id=?", id, cid).firstOrNull()
        "SUBTASK" -> sql.rows("""
            SELECT s.*, t.deleted_at AS parent_deleted_at FROM subtasks s JOIN tasks t ON t.id=s.task_id
            WHERE s.id=? AND t.id=? AND t.category_id=?
        """.trimIndent(), id, taskId, cid).firstOrNull()
        "REMINDER", "PARTICIPANT" -> sql.rows("""
            SELECT r.*, t.deleted_at AS parent_deleted_at
            FROM ${snapshotGroups.getValue(type)} r JOIN tasks t ON t.id=r.task_id
            WHERE t.id=? AND t.category_id=? AND r.user_uid=?
        """.trimIndent(), taskId, cid, uid).firstOrNull()
        else -> null
    }
}

private fun taskConflictPatch(current: Row, selected: Row): Row {
    val merged = current + selected
    val zone = runCatching { ZoneId.of(merged.string("timezone")) }.getOrElse { ZoneId.systemDefault() }
    val normalized = taskFields(merged.string("title"), (merged["description"] as? String).orEmpty(),
        merged.long("priority").toInt(), merged.optionalLong("due_at"), merged.string("repeat_type"), zone)
    val patch = normalized.filterKeys { it in selected }
    val anchor = normalized["repeat_anchor_day"]
    // Keep the saved timezone and refresh only the anchor derived from the selected schedule.
    return if (("due_at" in selected || "repeat_type" in selected) &&
        comparable(anchor) != comparable(current["repeat_anchor_day"])) {
        patch + ("repeat_anchor_day" to anchor)
    } else patch
}

private fun comparable(value: Any?): Any? = when (value) {
    is Boolean -> flag(value)
    is Number -> value.toLong()
    else -> value
}

private fun flag(value: Any?): Long = if (value == true || (value as? Number)?.toLong() == 1L) 1L else 0L
