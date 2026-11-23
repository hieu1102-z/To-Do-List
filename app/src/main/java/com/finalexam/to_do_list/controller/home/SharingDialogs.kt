package com.finalexam.to_do_list.controller.home

import android.content.Intent
import android.text.InputType
import com.finalexam.to_do_list.controller.auth.RegisterActivity
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.Action
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.Tone
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.model.repository.UnifiedTaskRepository
import com.finalexam.to_do_list.model.repository.WorkspaceCategory
import com.finalexam.to_do_list.model.repository.WorkspaceSync
import com.finalexam.to_do_list.model.repository.businessCode
import com.finalexam.to_do_list.model.repository.long
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.uuid
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class SharingDialogs(
    private val activity: HomeActivity,
    private val repository: UnifiedTaskRepository,
    private val dialogs: WorkspaceDialogs
) {
    private val active get() = !activity.isFinishing && !activity.isDestroyed
    private val auth get() = AuthRepository.get(activity)

    fun transfer(category: WorkspaceCategory, changed: () -> Unit) {
        val user = auth.user ?: return
        if (!WorkspaceSync.configured) return showBackendUnavailable()
        dialogs.form(
            title = "Đồng bộ qua tài khoản",
            label = "Tên danh mục",
            submitLabel = "Chuyển danh mục",
            message = "Chuyển “" + category.name + "” và các công việc sang " + user.email +
                ". Tài khoản này sẽ là chủ sở hữu. Danh mục sẽ ẩn khi bạn đăng xuất.",
            value = category.name,
            maxLength = 100
        ) { name, finish ->
            if (name.isBlank()) {
                finish("Vui lòng nhập tên danh mục.")
                return@form
            }
            repository.transferLocal(category.ref.id, user.uid, name) { moved ->
                if (!active) return@transferLocal
                finish(moved.exceptionOrNull()?.let { it.message ?: "Không thể chuyển danh mục." })
                if (moved.isSuccess) {
                    changed()
                    dialogs.notice("Danh mục đang chờ đồng bộ. Bạn có thể chia sẻ khi quá trình chuyển hoàn tất.")
                }
            }
        }
    }

    fun members(category: WorkspaceCategory, changed: () -> Unit) {
        val cid = category.ref.id
        if (category.ref.workspace.uid != repository.currentUid) return
        repository.online({ outcome ->
            if (!active) return@online
            outcome.fold({ snapshot ->
                val profiles = snapshot.profiles.associate { it.string("uid") to it.string("full_name") }
                val actions = linkedMapOf<String, () -> Unit>()
                for (member in snapshot.groups["MEMBER"].orEmpty()) {
                    val name = profiles[member.string("user_uid")] ?: "Thành viên"
                    val role = member.string("role")
                    val label = "$name · " + roleLabel(role)
                    val uniqueLabel = if (label in actions) "$label (" + member.string("user_uid").takeLast(5) + ")" else label
                    actions[uniqueLabel] = {
                        if (snapshot.role == "OWNER" && role != "OWNER") memberActions(category, member, name, changed)
                        else dialogs.notice(roleLabel(role))
                    }
                }
                if (snapshot.role == "OWNER") {
                    actions["Mời theo email"] = {
                        repository.sharing(cid) { ready ->
                            if (ready.getOrDefault(false)) invite(category, changed)
                            else dialogs.notice("Hãy chờ danh mục đồng bộ xong trước khi mời.")
                        }
                    }
                    actions["Lời mời đã gửi"] = { sent(category, changed) }
                } else actions["Rời danh mục"] = {
                    dialogs.confirm("Rời danh mục?", "Bạn sẽ mất quyền truy cập " + category.name + ".",
                        Action("Rời danh mục", Tone.DESTRUCTIVE) {
                            command("LEAVE", mapOf("category_id" to cid), "Đã rời danh mục.", changed)
                        })
                }
                choose(category.label, actions)
            }, ::error)
        }) { it.snapshot(cid) }
    }

    private fun memberActions(category: WorkspaceCategory, member: Row, name: String, changed: () -> Unit) {
        val fields = mapOf("category_id" to category.ref.id, "target_uid" to member["user_uid"], "base_revision" to member["revision"])
        val nextRole = if (member["role"] == "EDITOR") "VIEWER" else "EDITOR"
        choose(name, linkedMapOf(
            "Đổi thành " + roleLabel(nextRole) to { command("ROLE", fields + ("role" to nextRole)) { changed(); members(category, changed) } },
            "Gỡ thành viên" to {
                dialogs.confirm("Gỡ $name?", "$name sẽ mất quyền truy cập " + category.name + ".",
                    Action("Gỡ", Tone.DESTRUCTIVE) {
                        command("REMOVE_MEMBER", fields) { changed(); members(category, changed) }
                    })
            }
        ))
    }

    private fun invite(category: WorkspaceCategory, changed: () -> Unit) {
        if (!active) return
        dialogs.form(
            title = "Mời vào " + category.name,
            label = "Email người nhận",
            submitLabel = "Chọn quyền",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        ) { email, finish ->
            val address = email.trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                finish("Địa chỉ email không hợp lệ.")
                return@form
            }
            finish(null)
            choose("Quyền của $address", linkedMapOf(
                "Chỉnh sửa" to { sendInvite(category, address, "EDITOR", changed) },
                "Chỉ xem" to { sendInvite(category, address, "VIEWER", changed) }
            ))
        }
    }

    private fun sendInvite(category: WorkspaceCategory, email: String, role: String, changed: () -> Unit) {
        command("INVITE", mapOf("category_id" to category.ref.id, "email" to email, "role" to role, "invitation_id" to uuid()),
            "Đã gửi lời mời trong ứng dụng.", changed)
    }

    fun invitations() {
        if (!WorkspaceSync.configured) return showBackendUnavailable()
        if (auth.user?.emailVerified != true) {
            dialogs.show("Xác minh email", "Xác minh địa chỉ email để xem và nhận lời mời.",
                primary = Action("Xác minh") {
                    activity.startActivity(Intent(activity, RegisterActivity::class.java)
                        .putExtra(RegisterActivity.EXTRA_VERIFY_ONLY, true))
                })
            return
        }
        listInvites(null, {})
    }

    private fun sent(category: WorkspaceCategory, changed: () -> Unit) = listInvites(category, changed)

    private fun listInvites(category: WorkspaceCategory?, changed: () -> Unit) {
        val fields = category?.let { mapOf("category_id" to it.ref.id) }.orEmpty()
        repository.online({ result ->
            if (!active) return@online
            result.fold({ rows ->
                val actions = rows.map { row ->
                    val title = row.string("category_name") + " · " + roleLabel(row.string("offered_role")) +
                        " · " + inviteStatus(row.string("status"))
                    title to { invitation(row, category, changed) }
                }
                choices(if (category == null) "Lời mời danh mục" else "Lời mời đã gửi", actions)
            }, ::error)
        }) { it.pages(if (category == null) "INBOX" else "SENT", fields) }
    }

    private fun invitation(row: Row, category: WorkspaceCategory?, changed: () -> Unit) {
        val fields = mapOf("invitation_id" to row["id"], "category_id" to row["category_id"], "base_revision" to row["revision"])
        val end = Instant.ofEpochMilli(row.long("expires_at")).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val note = roleLabel(row.string("offered_role")) + " · " + inviteStatus(row.string("status")) + "\nHạn: $end"
        val who = if (category == null) row["inviter_name"] else row["invitee_email_key"]
        val title = row.string("category_name")
        val message = "$who\n$note"
        when {
            row["status"] != "PENDING" -> dialogs.show(title, message)
            category == null -> dialogs.show(title, message,
                primary = Action("Chấp nhận") {
                    command("ACCEPT", fields, "Đã tham gia danh mục. Danh mục sẽ xuất hiện sau khi đồng bộ.", changed)
                },
                secondary = Action("Từ chối", Tone.DESTRUCTIVE) { command("DECLINE", fields) { invitations() } },
                extra = Action("Đóng", Tone.NEUTRAL) {})
            else -> dialogs.show(title, message, primary = Action("Hủy lời mời", Tone.DESTRUCTIVE) {
                command("CANCEL", fields) { sent(category, changed) }
            })
        }
    }

    fun conflicts(changed: () -> Unit) {
        repository.conflicts { result ->
            if (!active) return@conflicts
            result.fold({ rows ->
                choices("Vấn đề đồng bộ", rows.map { row ->
                    val draft = row["payload_json"] as? String
                    val server = row["current"] as? Map<*, *>
                    val title = server?.get("title") ?: server?.get("name") ?: "Thay đổi trong danh mục"
                    (title.toString() + " · " + reason(row.string("last_error"))) to {
                        val details = server?.filterKeys { it in setOf("title", "name", "description", "priority", "due_at", "is_done") }
                        dialogs.show(
                            title = "Xem thay đổi",
                            message = "Bản hiện tại\n" + (details?.let { JSONObject(it).toString(2) } ?: "Không còn dữ liệu") +
                                "\n\nThay đổi của bạn\n" + (draft ?: "Xóa vĩnh viễn"),
                            primary = if (row["transfer_pending"] == true) null else Action("Bỏ thay đổi", Tone.DESTRUCTIVE) {
                                repository.discard(row.string("operation_id")) { if (it.isSuccess) changed() else error(it.exceptionOrNull()) }
                            },
                            extra = draft?.let {
                                Action("Sao chép bản nháp", Tone.NEUTRAL) {
                                    activity.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("Bản nháp", it))
                                    dialogs.notice("Đã sao chép. Bạn có thể mở bản hiện tại và sửa lại.")
                                }
                            })
                    }
                })
            }, ::error)
        }
    }

    private fun command(kind: String, data: Row, message: String? = null, success: () -> Unit) {
        val request = data + mapOf("kind" to kind, "operation_id" to uuid(), "client_occurred_at" to System.currentTimeMillis())
        repository.online({ result ->
            if (!active) return@online
            result.fold({ value ->
                success()
                val pending = "Máy chủ đang dọn dữ liệu liên quan.".takeIf { value["pending"] == true }
                listOfNotNull(message, pending).takeIf { it.isNotEmpty() }?.let {
                    dialogs.notice(it.joinToString(" "))
                }
            }, ::error)
        }) { it.call(request) }
    }

    private fun roleLabel(role: String) = when (role) {
        "OWNER" -> "Chủ sở hữu"
        "EDITOR" -> "Chỉnh sửa"
        else -> "Chỉ xem"
    }

    private fun inviteStatus(status: String) = when (status) {
        "PENDING" -> "Đang chờ"
        "ACCEPTED" -> "Đã chấp nhận"
        "REJECTED" -> "Đã từ chối"
        "CANCELLED" -> "Đã hủy"
        else -> "Đã hết hạn"
    }

    fun showBackendUnavailable() = dialogs.notice(reason("BACKEND_NOT_CONFIGURED"), error = true)

    private fun reason(code: String) = when (code) {
        "BACKEND_NOT_CONFIGURED" -> "Bản APK này chưa được cấu hình máy chủ đồng bộ."
        "REVISION_CONFLICT" -> "Xung đột với bản trên máy chủ"
        "PERMISSION_DENIED" -> "Bạn không có quyền thực hiện thao tác này"
        "INVITATION_EXPIRED" -> "Lời mời đã hết hạn"
        "INVITATION_ALREADY_PENDING" -> "Đã có một lời mời đang chờ"
        "ALREADY_ACTIVE_MEMBER" -> "Người này đã là thành viên"
        "NAME_ALREADY_EXISTS" -> "Tên danh mục đã tồn tại"
        "CATEGORY_BUSY" -> "Máy chủ đang dọn danh mục. Hãy thử lại sau"
        "NETWORK", "UNAVAILABLE", "DEADLINE_EXCEEDED" -> "Không thể kết nối máy chủ"
        else -> "Không thể hoàn tất thao tác"
    }

    private fun error(error: Throwable?) = dialogs.notice(reason(error?.businessCode().orEmpty()), error = true)

    private fun choose(title: String, options: Map<String, () -> Unit>) =
        choices(title, options.entries.map { it.key to it.value })

    private fun choices(title: String, options: List<Pair<String, () -> Unit>>) = dialogs.choices(title, options)
}
