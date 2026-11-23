package com.finalexam.to_do_list.model.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.finalexam.to_do_list.model.local.AppDatabase
import com.finalexam.to_do_list.model.local.entity.SyncQueueEntity
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.local.relation.TrashTask
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class WorkspaceRef(val uid: String? = null)
data class CategoryRef(val workspace: WorkspaceRef, val id: String)
data class TaskRef(val workspace: WorkspaceRef, val id: String)
data class WorkspaceCategory(
    val ref: CategoryRef, val name: String, val role: String, val owner: String = "", val revision: Long = 0
) {
    val label get() = name + if (ref.workspace.uid == null) " · Local" else
        " · Tài khoản" + if (owner.isNotBlank() && role != "OWNER") " · $owner" else ""
}

internal typealias Row = Map<String, Any?>
internal data class WorkspaceSnapshot(
    val category: Row, val role: String, val seq: Long, val groups: Map<String, List<Row>>, val profiles: List<Row>
)
internal val snapshotGroups = linkedMapOf(
    "MEMBER" to "category_members", "TASK" to "tasks", "SUBTASK" to "subtasks",
    "PARTICIPANT" to "task_participants", "REMINDER" to "task_reminders"
)
internal fun Row.string(key: String) = get(key) as String
internal fun Row.long(key: String) = (get(key) as? Number)?.toLong() ?: 0L
internal fun Row.optionalLong(key: String) = (get(key) as? Number)?.toLong()
internal fun uuid(): String = UUID.randomUUID().toString()
internal fun normalizedName(name: String) = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
internal fun nameKey(name: String) = normalizedName(name).lowercase(Locale.ROOT)

internal fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any?): List<Row> =
    query(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.columnNames.mapIndexed { i, column ->
                column to when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    else -> error("Unsupported workspace column: $column")
                }
            }.toMap())
        }
    }

private fun Row.values() = ContentValues().also { values ->
    forEach { (key, value) ->
        when (value) {
            null -> values.putNull(key)
            is String -> values.put(key, value)
            is Number -> values.put(key, value.toLong())
            is Boolean -> values.put(key, if (value) 1 else 0)
            else -> error("Unsupported workspace value: $key")
        }
    }
}

internal class AccountWorkspace(val uid: String, val database: AppDatabase) : AutoCloseable {
    init { require(uid.isNotBlank() && !uid.startsWith("local:")) }
    val sql get() = database.openHelper.writableDatabase
    private val keys = mapOf(
        "users" to listOf("uid"), "categories" to listOf("id"), "category_members" to listOf("category_id", "user_uid"),
        "tasks" to listOf("id"), "subtasks" to listOf("id"), "task_participants" to listOf("task_id", "user_uid"),
        "task_reminders" to listOf("task_id", "user_uid"), "sync_state" to listOf("user_uid", "category_id")
    )
    private val columns = mutableMapOf<String, Set<String>>()
    private val now get() = System.currentTimeMillis()
    fun <T> transaction(action: () -> T): T = database.runInTransaction(java.util.concurrent.Callable(action))
    override fun close() = database.close()

    fun upsert(table: String, row: Row) {
        val allowed = columns.getOrPut(table) { sql.rows("PRAGMA table_info($table)").map { it.string("name") }.toSet() }
        val filtered = row.filterKeys { it in allowed }
        val pk = keys.getValue(table)
        val where = pk.joinToString(" AND ") { "$it=?" }
        val args = pk.map { filtered.getValue(it) }.toTypedArray()
        if (sql.update(table, SQLiteDatabase.CONFLICT_ABORT, filtered.values(), where, args) == 0) {
            sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, filtered.values())
        }
    }

    fun home(): List<HomeTask> {
        val queue = commands().groupBy { it.string("category_id") }
        return database.tasks().homeTasks(uid).map { task ->
            val pending = queue[task.categoryId].orEmpty().filter {
                it.string("entity_id").substringBefore('/') == task.id || it.string("entity_type") == "CATEGORY"
            }
            val status = when {
                pending.any { it["last_error"] == "REVISION_CONFLICT" } -> "Xung đột"
                pending.any { it["status"] in setOf("REJECTED", "FAILED") } -> "Lỗi đồng bộ"
                pending.isNotEmpty() || task.revision == 0L -> "Chờ đồng bộ"
                else -> "Đã đồng bộ"
            }
            task.copy(workspaceUid = uid, syncLabel = status)
        }
    }

    fun categories(): List<WorkspaceCategory> = sql.rows("""
        SELECT c.*, m.role, u.full_name FROM categories c JOIN category_members m ON m.category_id=c.id
        JOIN users u ON u.uid=c.owner_uid
        WHERE c.deleted_at IS NULL AND m.user_uid=? AND m.status='ACTIVE' ORDER BY c.name
    """.trimIndent(), uid).map {
        WorkspaceCategory(CategoryRef(WorkspaceRef(uid), it.string("id")), it.string("name"),
            it.string("role"), it.string("full_name"), it.long("revision"))
    }

    fun category(cid: String) = sql.rows("SELECT * FROM categories WHERE id=?", cid).singleOrNull()
    fun role(cid: String) = database.tasks().role(cid, uid)
    fun requireRole(cid: String, vararg roles: String) {
        check(role(cid) in roles) { "Bạn không có quyền thực hiện thao tác này." }
    }

    fun createCategory(label: String, id: String = uuid(), color: String? = null): String {
        val name = normalizedName(label)
        require(name.length in 1..100) { "Tên danh mục cần từ 1 đến 100 ký tự." }
        require(database.tasks().categoryId(uid, nameKey(name)) == null) { "Danh mục này đã tồn tại." }
        val time = now
        upsert("categories", mapOf("id" to id, "owner_uid" to uid, "name" to name, "name_key" to nameKey(name),
            "created_at" to time, "client_updated_at" to time, "color" to color))
        upsert("category_members", mapOf("category_id" to id, "user_uid" to uid, "role" to "OWNER", "joined_at" to time))
        enqueue(id, "CATEGORY", id, "CREATE", 0, mapOf("name" to name, "color" to color))
        return id
    }

    fun copyCategory(categoryId: String, name: String, copy: LocalCategoryCopy): String {
        sql.rows("SELECT account_category_id FROM local_category_copies WHERE local_category_id=?", categoryId)
            .firstOrNull()?.let { return it.string("account_category_id") }
        val cid = createCategory(name, color = copy.category["color"] as String?)
        for (source in copy.tasks) {
            val sourceId = source.string("id")
            val id = uuid()
            val fields = source.filterKeys { it in setOf("title", "description", "due_at", "priority", "is_done",
                "completed_at", "repeat_type", "timezone", "repeat_anchor_day", "created_at") }
            upsert("tasks", fields + mapOf("id" to id, "category_id" to cid, "created_by_uid" to uid, "client_updated_at" to now))
            enqueue(cid, "TASK", id, "CREATE", 0, fields)
            copy.subtasks[sourceId].orEmpty().forEach { subtask ->
                val subId = uuid()
                val fieldsSub = subtask.filterKeys { it in setOf("title", "is_done", "sort_order") }
                upsert("subtasks", fieldsSub + mapOf("id" to subId, "task_id" to id, "client_updated_at" to now))
                enqueue(cid, "SUBTASK", "$id/$subId", "CREATE", 0, fieldsSub)
            }
            copy.reminders[sourceId]?.let { reminder ->
                val settings = reminder.filterKeys { it in setOf("remind_at", "is_enabled") }
                upsert("task_reminders", settings + mapOf("task_id" to id, "user_uid" to uid, "client_updated_at" to now))
                enqueue(cid, "REMINDER", "$id/$uid", "CREATE", 0, settings)
            }
        }
        sql.execSQL("INSERT INTO local_category_copies VALUES(?,?)", arrayOf(categoryId, cid))
        return cid
    }

    fun todoCategory(): String = database.tasks().categoryId(uid, "to do") ?: createCategory("to do")
    fun commands(): List<Row> = sql.rows("SELECT * FROM sync_queue WHERE actor_uid=? ORDER BY rowid", uid)

    fun enqueue(cid: String, type: String, id: String, kind: String, base: Long, payload: Row = emptyMap()) {
        val membership = sql.rows("SELECT revision FROM category_members WHERE category_id=? AND user_uid=?", cid, uid)
            .firstOrNull()?.long("revision") ?: 0
        val action = when (kind) {
            "CREATE" -> "CREATE"
            "PURGE", "WITHDRAW", "REMOVE_REMINDER" -> "DELETE"
            else -> "UPDATE"
        }
        val prior = sql.rows("""SELECT * FROM sync_queue WHERE actor_uid=? AND category_id=? AND entity_type=?
            AND entity_id=? AND status<>'REJECTED' ORDER BY rowid DESC LIMIT 1""", uid, cid, type, id).firstOrNull()
        if (prior != null && prior["dispatched"] == 0L && prior["action"] == "UPDATE" && kind == "UPDATE" &&
            prior.long("membership_revision") == membership) {
            val body = JSONObject(prior.string("payload_json"))
            if (body.optString("command_type") == "UPDATE") {
                payload.forEach { (key, value) -> body.put(key, value ?: JSONObject.NULL) }
                sql.execSQL("UPDATE sync_queue SET payload_json=? WHERE operation_id=?", arrayOf(body.toString(), prior["operation_id"]))
                return
            }
        }
        val body = JSONObject(payload.mapValues { it.value ?: JSONObject.NULL }).put("command_type", kind)
        database.tasks().enqueueSyncOperation(SyncQueueEntity(
            operationId = uuid(), actorUid = uid, categoryId = cid, entityType = type, entityId = id,
            action = action, payloadJson = if (action == "DELETE") null else body.toString(),
            baseRevision = base, clientOccurredAt = now, predecessorId = prior?.string("operation_id"),
            membershipRevision = membership
        ))
    }

    fun envelope(command: Row): Row {
        val json = command["payload_json"]?.let { JSONObject(it as String) }
        val kind = json?.optString("command_type") ?: when (command["entity_type"]) {
            "PARTICIPANT" -> "WITHDRAW"
            "REMINDER" -> "REMOVE_REMINDER"
            else -> "PURGE"
        }
        val route = command.string("entity_id").split('/')
        val payload = json?.run {
            keys().asSequence().filter { it != "command_type" }.associateWith { key -> get(key).takeUnless { it == JSONObject.NULL } }
        }
        return mapOf("operation_id" to command["operation_id"], "kind" to kind, "category_id" to command["category_id"],
            "entity_type" to command["entity_type"], "entity_id" to route.last(), "task_id" to route.first(),
            "base_revision" to command["base_revision"], "membership_revision" to command["membership_revision"],
            "client_occurred_at" to command["client_occurred_at"],
            "payload" to payload)
    }

    fun sending(id: String) {
        sql.execSQL("UPDATE sync_queue SET status='SENDING', dispatched=1 WHERE operation_id=?", arrayOf(id))
    }

    fun acknowledge(command: Row, result: Row) = transaction {
        val id = command.string("operation_id")
        val revision = result.optionalLong("revision") ?: 0
        sql.execSQL("UPDATE sync_queue SET base_revision=?, predecessor_id=NULL WHERE predecessor_id=?", arrayOf(revision, id))
        val route = command.string("entity_id").split('/')
        if (result["revision"] != null) {
            when (val type = command.string("entity_type")) {
                "CATEGORY", "TASK", "SUBTASK" -> {
                    val table = if (type == "CATEGORY") "categories" else snapshotGroups.getValue(type)
                    sql.execSQL("UPDATE $table SET revision=MAX(revision,?) WHERE id=?", arrayOf(revision, route.last()))
                }
                "REMINDER", "PARTICIPANT" -> {
                    val table = snapshotGroups.getValue(type)
                    sql.execSQL("UPDATE $table SET revision=MAX(revision,?) WHERE task_id=? AND user_uid=?", arrayOf(revision, route.first(), uid))
                }
            }
        }
        sql.execSQL("DELETE FROM sync_queue WHERE operation_id=?", arrayOf(id))
        if (command["entity_type"] == "CATEGORY" && command["action"] == "DELETE") {
            sql.execSQL("DELETE FROM sync_state WHERE user_uid=? AND category_id=?", arrayOf(uid, command["category_id"]))
            sql.execSQL("DELETE FROM local_category_copies WHERE account_category_id=?", arrayOf(command["category_id"]))
        }
    }

    fun failed(command: Row, reason: String, retry: Boolean) = transaction {
        val delay = (15000L shl command.long("retry_count").coerceAtMost(6).toInt())
        sql.execSQL("""UPDATE sync_queue SET status=?,last_error=?,retry_count=retry_count+1,next_retry_at=?
            WHERE operation_id=?""", arrayOf(if (retry) "FAILED" else "REJECTED", reason,
            if (retry) now + delay else null, command["operation_id"]))
        if (!retry) {
            var previous = listOf(command.string("operation_id"))
            while (previous.isNotEmpty()) {
                previous = previous.flatMap { id ->
                    sql.rows("SELECT operation_id FROM sync_queue WHERE predecessor_id=?", id).map { it.string("operation_id") }
                }
                previous.forEach { id ->
                    sql.execSQL("UPDATE sync_queue SET status='REJECTED',last_error=? WHERE operation_id=?", arrayOf(reason, id))
                }
            }
            if (command["action"] == "CREATE") {
                val type = command.string("entity_type")
                val cid = command.string("category_id")
                val entity = command.string("entity_id")
                val root = entity.substringBefore('/')
                when (type) {
                    "CATEGORY" -> {
                        sql.execSQL("DELETE FROM categories WHERE id=? AND revision=0", arrayOf(cid))
                        sql.execSQL("UPDATE sync_queue SET status='REJECTED',last_error=? WHERE category_id=?",
                            arrayOf(reason, cid))
                    }
                    "TASK" -> {
                        sql.execSQL("DELETE FROM tasks WHERE id=? AND revision=0", arrayOf(entity))
                        sql.execSQL("""UPDATE sync_queue SET status='REJECTED',last_error=? WHERE category_id=?
                            AND (entity_id=? OR entity_id LIKE ?)""", arrayOf(reason, cid, entity, "$entity/%"))
                    }
                    "SUBTASK" -> sql.execSQL("DELETE FROM subtasks WHERE id=? AND revision=0",
                        arrayOf(entity.substringAfterLast('/')))
                    "REMINDER", "PARTICIPANT" -> sql.execSQL(
                        "DELETE FROM " + snapshotGroups.getValue(type) + " WHERE task_id=? AND user_uid=? AND revision=0",
                        arrayOf(root, uid))
                }
            }
        }
    }

    fun dropCategory(cid: String) = transaction {
        sql.execSQL("DELETE FROM categories WHERE id=?", arrayOf(cid))
        sql.execSQL("DELETE FROM sync_state WHERE category_id=?", arrayOf(cid))
        sql.execSQL("DELETE FROM local_category_copies WHERE account_category_id=?", arrayOf(cid))
        sql.execSQL("DELETE FROM sync_queue WHERE actor_uid=? AND category_id=?", arrayOf(uid, cid))
    }

    private fun rowId(type: String, row: Row): String = when (type) {
        "MEMBER" -> row.string("user_uid")
        "REMINDER", "PARTICIPANT" -> row.string("task_id") + "/" + row.string("user_uid")
        "SUBTASK" -> row.string("task_id") + "/" + row.string("id")
        else -> row.string("id")
    }

    fun apply(snapshot: WorkspaceSnapshot) = transaction {
        val cid = snapshot.category.string("id")
        val previous = sql.rows("SELECT last_server_seq FROM sync_state WHERE user_uid=? AND category_id=?", uid, cid)
            .firstOrNull()?.long("last_server_seq") ?: -1
        if (snapshot.seq < previous) return@transaction
        if (snapshot.role == "VIEWER") {
            sql.execSQL("DELETE FROM sync_queue WHERE actor_uid=? AND category_id=? AND entity_type<>'REMINDER'", arrayOf(uid, cid))
        }
        val queue = commands().filter { it["category_id"] == cid && it["status"] != "REJECTED" }
        if (queue.any { it["entity_type"] == "CATEGORY" && it["action"] == "DELETE" }) return@transaction
        snapshot.profiles.filter { it["uid"] != uid }.forEach {
            upsert("users", it + mapOf("email" to "", "email_key" to "", "email_verified" to 0))
        }
        if (category(cid) == null || queue.none { it["entity_type"] == "CATEGORY" }) {
            upsert("categories", snapshot.category)
        } else {
            upsert("categories", mapOf("id" to cid, "server_seq" to snapshot.seq))
        }
        fun existing(type: String, table: String) = if (type in setOf("MEMBER", "TASK", "PARTICIPANT")) {
            sql.rows("SELECT * FROM $table WHERE category_id=?", cid)
        } else sql.rows("SELECT x.* FROM $table x JOIN tasks t ON t.id=x.task_id WHERE t.category_id=?", cid)
        for ((type, table) in snapshotGroups.entries.toList().asReversed()) {
            val present = snapshot.groups[type].orEmpty().map { rowId(type, it) }.toSet()
            for (row in existing(type, table)) {
                val id = rowId(type, row)
                val pending = queue.filter { it["entity_type"] == type && it["entity_id"] == id }
                if (id !in present && pending.none { it["action"] == "CREATE" }) {
                    val pk = keys.getValue(table)
                    sql.execSQL("DELETE FROM $table WHERE " + pk.joinToString(" AND ") { "$it=?" },
                        pk.map { row[it] }.toTypedArray())
                }
            }
        }
        for ((type, table) in snapshotGroups) {
            for (row in snapshot.groups[type].orEmpty()) {
                val id = rowId(type, row)
                if (queue.any { it["entity_type"] == type && it["entity_id"] == id }) continue
                if (row["task_id"] != null && database.tasks().findIncludingDeleted(row.string("task_id")) == null) continue
                upsert(table, row)
            }
        }
        upsert("sync_state", mapOf("user_uid" to uid, "category_id" to cid, "last_server_seq" to snapshot.seq))
    }

    fun trash(): List<TrashTask> {
        val result = mutableListOf<TrashTask>()
        val definitions = listOf(
            Triple("CATEGORY", "categories c", "c.id AS id,c.name AS title,c.name AS category,c.deleted_at,c.id AS cid,c.revision,NULL AS parent"),
            Triple("TASK", "tasks t JOIN categories c ON c.id=t.category_id",
                "t.id AS id,t.title,c.name AS category,t.deleted_at,c.id AS cid,t.revision,NULL AS parent"),
            Triple("SUBTASK", "subtasks s JOIN tasks t ON t.id=s.task_id JOIN categories c ON c.id=t.category_id",
                "s.id AS id,s.title,c.name AS category,s.deleted_at,c.id AS cid,s.revision,t.id AS parent")
        )
        for ((type, from, fields) in definitions) {
            val deleted = when (type) { "CATEGORY" -> "c.deleted_at"; "TASK" -> "t.deleted_at"; else -> "s.deleted_at" }
            val parentActive = when (type) { "CATEGORY" -> "1=1"; "TASK" -> "c.deleted_at IS NULL"; else -> "c.deleted_at IS NULL AND t.deleted_at IS NULL" }
            sql.rows("""SELECT $fields,m.role FROM $from JOIN category_members m ON m.category_id=c.id
                    WHERE m.user_uid=? AND m.status='ACTIVE' AND m.role IN ('OWNER','EDITOR')
                    AND $deleted IS NOT NULL AND $parentActive""", uid).mapTo(result) {
                TrashTask(it.string("id"), it.string("title"), it.string("category"), it.long("deleted_at"),
                    it.string("cid"), uid, it.string("role"), type, it["parent"] as String?, it.long("revision"))
            }
        }
        return result.sortedByDescending { it.deletedAt }
    }
}
