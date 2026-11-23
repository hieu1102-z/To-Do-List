package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import com.finalexam.to_do_list.controller.auth.RegisterActivity
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

internal class SharingDialogs(private val activity: HomeActivity, private val repository: UnifiedTaskRepository) {
    private val active get() = !activity.isFinishing && !activity.isDestroyed
    private val auth get() = AuthRepository.get(activity)

    fun copy(category: WorkspaceCategory, changed: () -> Unit) {
        val user = auth.user ?: return
        if (!WorkspaceSync.configured) return showBackendUnavailable()
        repository.copyPreview(category.ref.id) { result ->
            if (!active || auth.user?.uid != user.uid) return@copyPreview
            result.fold({ count ->
                val name = EditText(activity).apply { setSingleLine(); setText(category.name) }
                AlertDialog.Builder(activity).setTitle("Sao chép danh mục lên tài khoản")
                    .setMessage("Sao chép $count công việc vào " + user.email + ". Bản Local sẽ được giữ trên thiết bị.")
                    .setView(name).setNegativeButton("Hủy", null)
                    .setPositiveButton("Sao chép") { _, _ ->
                        if (auth.user?.uid == user.uid) {
                            repository.copyLocal(category.ref.id, name.text.toString()) {
                                if (it.isSuccess) {
                                    toast("Đã lưu bản sao. Bạn có thể mời sau khi bản sao đồng bộ xong.")
                                    changed()
                                } else error(it.exceptionOrNull())
                            }
                        }
                    }.show()
            }, ::error)
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
                        else toast(roleLabel(role))
                    }
                }
                if (snapshot.role == "OWNER") {
                    actions["Mời theo email"] = {
                        repository.sharing(cid) { ready ->
                            if (ready.getOrDefault(false)) invite(category, changed)
                            else toast("Hãy chờ danh mục đồng bộ xong trước khi mời.")
                        }
                    }
                    actions["Lời mời đã gửi"] = { sent(category, changed) }
                } else actions["Rời danh mục"] = {
                    AlertDialog.Builder(activity).setTitle("Rời danh mục?")
                        .setMessage("Bạn sẽ mất quyền truy cập " + category.name + ".")
                        .setNegativeButton("Hủy", null).setPositiveButton("Rời danh mục") { _, _ ->
                            command("LEAVE", mapOf("category_id" to cid)) {
                                toast("Đã rời danh mục.")
                                changed()
                            }
                        }.show()
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
                AlertDialog.Builder(activity).setTitle("Gỡ $name?")
                    .setNegativeButton("Hủy", null).setPositiveButton("Gỡ") { _, _ ->
                        command("REMOVE_MEMBER", fields) { changed(); members(category, changed) }
                    }.show()
            }
        ))
    }

    private fun invite(category: WorkspaceCategory, changed: () -> Unit) {
        if (!active) return
        val email = EditText(activity).apply {
            setSingleLine()
            hint = "Email người nhận"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(activity).setTitle("Mời vào " + category.name).setView(email)
            .setNegativeButton("Hủy", null).setPositiveButton("Chọn quyền") { _, _ ->
                val address = email.text.toString().trim()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                    toast("Địa chỉ email không hợp lệ.")
                    return@setPositiveButton
                }
                choose("Quyền của $address", linkedMapOf(
                    "Chỉnh sửa" to { sendInvite(category, address, "EDITOR", changed) },
                    "Chỉ xem" to { sendInvite(category, address, "VIEWER", changed) }
                ))
            }.show()
    }

    private fun sendInvite(category: WorkspaceCategory, email: String, role: String, changed: () -> Unit) {
        command("INVITE", mapOf("category_id" to category.ref.id, "email" to email, "role" to role, "invitation_id" to uuid())) {
            toast("Đã gửi lời mời trong ứng dụng.")
            changed()
        }
    }

    fun invitations() {
        if (!WorkspaceSync.configured) return showBackendUnavailable()
        if (auth.user?.emailVerified != true) {
            AlertDialog.Builder(activity).setTitle("Xác minh email")
                .setMessage("Xác minh địa chỉ email để xem và nhận lời mời.")
                .setNegativeButton("Đóng", null).setPositiveButton("Xác minh") { _, _ ->
                    activity.startActivity(Intent(activity, RegisterActivity::class.java)
                        .putExtra(RegisterActivity.EXTRA_VERIFY_ONLY, true))
                }.show()
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
        val dialog = AlertDialog.Builder(activity).setTitle(row.string("category_name")).setMessage("$who\n$note")
            .setNeutralButton("Đóng", null)
        if (row["status"] == "PENDING") {
            if (category == null) {
                dialog.setPositiveButton("Chấp nhận") { _, _ -> command("ACCEPT", fields) {
                    toast("Đã tham gia danh mục. Danh mục sẽ xuất hiện sau khi đồng bộ.")
                    changed()
                } }.setNegativeButton("Từ chối") { _, _ -> command("DECLINE", fields) { invitations() } }
            } else dialog.setPositiveButton("Hủy lời mời") { _, _ -> command("CANCEL", fields) { sent(category, changed) } }
        }
        dialog.show()
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
                        val builder = AlertDialog.Builder(activity).setTitle("Xem thay đổi")
                            .setMessage("Bản hiện tại\n" + (details?.let { JSONObject(it).toString(2) } ?: "Không còn dữ liệu") +
                                "\n\nThay đổi của bạn\n" + (draft ?: "Xóa vĩnh viễn"))
                            .setNegativeButton("Đóng", null).setPositiveButton("Bỏ thay đổi") { _, _ ->
                                repository.discard(row.string("operation_id")) { if (it.isSuccess) changed() else error(it.exceptionOrNull()) }
                            }
                        if (draft != null) builder.setNeutralButton("Sao chép bản nháp") { _, _ ->
                            activity.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Bản nháp", draft))
                            toast("Đã sao chép. Bạn có thể mở bản hiện tại và sửa lại.")
                        }
                        builder.show()
                        Unit
                    }
                })
            }, ::error)
        }
    }

    private fun command(kind: String, data: Row, success: () -> Unit) {
        val request = data + mapOf("kind" to kind, "operation_id" to uuid(), "client_occurred_at" to System.currentTimeMillis())
        repository.online({ result ->
            if (!active) return@online
            result.fold({ value ->
                if (value["pending"] == true) toast("Máy chủ đang dọn dữ liệu liên quan.")
                success()
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

    fun showBackendUnavailable() = toast(reason("BACKEND_NOT_CONFIGURED"))

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

    private fun error(error: Throwable?) { toast(reason(error?.businessCode().orEmpty())) }
    private fun toast(text: String) {
        if (active) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    private fun choose(title: String, options: Map<String, () -> Unit>) =
        choices(title, options.entries.map { it.key to it.value })

    private fun choices(title: String, options: List<Pair<String, () -> Unit>>) {
        if (!active) return
        val builder = AlertDialog.Builder(activity).setTitle(title).setNegativeButton("Đóng", null)
        if (options.isEmpty()) builder.setMessage("Không có mục nào.")
        else builder.setItems(options.map { it.first }.toTypedArray()) { _, index -> options[index].second() }
        builder.show()
    }
}
