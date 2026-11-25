package com.finalexam.to_do_list.controller.home

import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.auth.RegisterActivity
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.Action
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.ConflictChoice
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.Tone
import com.finalexam.to_do_list.databinding.ViewCategoryMembersBinding
import com.finalexam.to_do_list.databinding.ViewPendingCategoryInvitationBinding
import com.finalexam.to_do_list.model.repository.AccountSession
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.Row
import com.finalexam.to_do_list.model.repository.UnifiedTaskRepository
import com.finalexam.to_do_list.model.repository.WorkspaceCategory
import com.finalexam.to_do_list.model.repository.WorkspaceMember
import com.finalexam.to_do_list.model.repository.WorkspaceMembers
import com.finalexam.to_do_list.model.repository.WorkspacePendingInvitation
import com.finalexam.to_do_list.model.repository.WorkspaceRequestError
import com.finalexam.to_do_list.model.repository.WorkspaceSync
import com.finalexam.to_do_list.model.repository.businessCode
import com.finalexam.to_do_list.model.repository.long
import com.finalexam.to_do_list.model.repository.string
import com.finalexam.to_do_list.model.repository.uuid
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

    fun bindMembers(
        category: WorkspaceCategory,
        container: LinearLayout,
        shareButton: View,
        isCurrent: () -> Boolean,
        changed: () -> Unit,
        close: () -> Unit
    ) {
        val cid = category.ref.id
        shareButton.isVisible = false
        val session = AccountSession.current(activity)?.takeIf { active && it.uid == category.ref.workspace.uid } ?: return
        val binding = ViewCategoryMembersBinding.inflate(activity.layoutInflater, container, true)
        ViewCompat.setAccessibilityHeading(binding.heading, true)

        fun attached() = active && isCurrent() && AccountSession.current(activity) === session
        fun setStatus(message: String?, error: Boolean = false) {
            binding.status.text = message
            binding.status.setTextColor(ContextCompat.getColor(activity, if (error) R.color.task_delete_danger else R.color.muted))
            binding.status.isVisible = message != null
        }
        fun showFeedback(message: String, isError: Boolean) {
            if (!active || AccountSession.current(activity) !== session) return
            if (attached()) setStatus(message, isError) else dialogs.notice(message, error = isError)
        }
        fun showMembership(role: String?, showMembers: Boolean) {
            binding.panel.isVisible = showMembers
            shareButton.isVisible = role == "OWNER" && !showMembers
            binding.invite.isVisible = role == "OWNER" && showMembers
            binding.leave.isVisible = role != null && role != "OWNER"
        }

        var displayed: WorkspaceMembers? = null
        var pending = emptyList<WorkspacePendingInvitation>()
        var refreshGeneration = 0
        var pendingGeneration = 0
        val cancelling = mutableSetOf<String>()
        lateinit var refresh: () -> Unit
        lateinit var confirmCancel: (WorkspacePendingInvitation) -> Unit
        fun isShared(list: WorkspaceMembers) = list.role != "OWNER" || list.members.any { it.uid != session.uid }
        fun canManage() = attached() && displayed?.role == "OWNER"

        fun updateVisibility() {
            val list = displayed
            showMembership(list?.role, list != null && (isShared(list) || pending.isNotEmpty()))
        }

        fun displayPending(items: List<WorkspacePendingInvitation>) {
            pending = items
            binding.pendingMembers.removeAllViews()
            for (invite in items) {
                val row = ViewPendingCategoryInvitationBinding.inflate(activity.layoutInflater, binding.pendingMembers, false)
                row.details.text = activity.getString(R.string.category_members_pending_entry, invite.email, roleLabel(invite.role))
                row.cancel.contentDescription = activity.getString(R.string.category_members_cancel_invitation_for, invite.email)
                row.cancel.isEnabled = invite.id !in cancelling
                row.cancel.alpha = if (row.cancel.isEnabled) 1f else 0.4f
                row.cancel.setOnClickListener { confirmCancel(invite) }
                binding.pendingMembers.addView(row.root)
            }
            updateVisibility()
        }

        fun refreshPending() {
            if (!canManage()) return
            val generation = ++pendingGeneration
            repository.refreshPendingInvitations(category) { result ->
                if (!canManage() || generation != pendingGeneration) return@refreshPendingInvitations
                val warning = activity.getString(R.string.category_members_pending_unavailable)
                result.fold({ items ->
                    displayPending(items)
                    if (binding.status.text == warning) setStatus(null)
                }, { failure ->
                    if (failure.businessCode() == "PERMISSION_DENIED") displayPending(emptyList())
                    else setStatus(warning, error = true)
                })
            }
        }

        fun loadPending(synced: Boolean) {
            val generation = pendingGeneration
            repository.cachedPendingInvitations(category) { result ->
                if (!canManage() || generation != pendingGeneration) return@cachedPendingInvitations
                result.onSuccess(::displayPending)
                if (synced) refreshPending()
            }
        }

        fun invitationsChanged() {
            changed()
            refreshPending()
        }

        fun display(list: WorkspaceMembers) {
            val previous = displayed
            displayed = list
            if (previous?.role == "OWNER" && list.role != "OWNER") {
                pendingGeneration++
                displayPending(emptyList())
            } else updateVisibility()
            setStatus(activity.getString(R.string.category_members_empty)
                .takeIf { isShared(list) && list.members.isEmpty() && pending.isEmpty() })
            if (list.role == "OWNER" && (previous?.role != "OWNER" || !previous.synced && list.synced)) {
                loadPending(list.synced)
            }
            if (previous == list) return
            binding.members.removeAllViews()
            val sorted = list.members.sortedWith(compareBy<WorkspaceMember> { it.role != "OWNER" }.thenBy { it.name })
            for (member in sorted) {
                val name = member.name.ifBlank { activity.getString(R.string.category_members_unknown) }
                val row = TextView(activity).apply {
                    text = activity.getString(R.string.category_members_entry, name, roleLabel(member.role))
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(activity, R.color.ink))
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(48)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    if (list.role == "OWNER" && member.role != "OWNER") {
                        setBackgroundResource(R.drawable.bg_task_delete_cancel)
                        isFocusable = true
                        setOnClickListener {
                            if (!canManage()) return@setOnClickListener
                            memberActions(category, member, name, ::canManage, ::showFeedback) {
                                changed()
                                if (attached()) refresh()
                            }
                        }
                    }
                }
                binding.members.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }

        refresh = {
            val generation = ++refreshGeneration
            repository.refreshMembers(category) { result ->
                if (!attached() || generation != refreshGeneration) return@refreshMembers
                result.fold(::display) { failure ->
                    val code = failure.businessCode()
                    if (code == "PERMISSION_DENIED") {
                        displayed = null
                        pendingGeneration++
                        binding.members.removeAllViews()
                        displayPending(emptyList())
                    }
                    val cached = displayed
                    if (cached == null) setStatus(reason(code), error = true)
                    else if (isShared(cached)) setStatus(activity.getString(R.string.category_members_cached))
                }
            }
        }

        confirmCancel = confirm@{ invite ->
            if (!canManage() || invite.id in cancelling) return@confirm
            dialogs.confirm(
                activity.getString(R.string.category_members_cancel_invitation_title),
                activity.getString(R.string.category_members_cancel_invitation_message, invite.email),
                Action(activity.getString(R.string.category_members_cancel_invitation), Tone.DESTRUCTIVE) {
                    val currentInvite = pending.firstOrNull { it.id == invite.id } ?: return@Action
                    if (!canManage() || !cancelling.add(invite.id)) return@Action
                    displayPending(pending)
                    setStatus(null)
                    repository.cancelPendingInvitation(category, currentInvite) { result ->
                        cancelling.remove(invite.id)
                        if (!canManage()) return@cancelPendingInvitation
                        result.fold({
                            displayPending(pending.filterNot { it.id == invite.id })
                            setStatus(activity.getString(R.string.category_members_invitation_cancelled))
                            invitationsChanged()
                        }, { failure ->
                            displayPending(pending)
                            val code = failure.businessCode()
                            setStatus(reason(code), error = true)
                            if (code in setOf("INVITATION_NOT_PENDING", "INVITATION_EXPIRED", "REVISION_CONFLICT", "NOT_FOUND", "PERMISSION_DENIED")) {
                                refreshPending()
                                refresh()
                            }
                        })
                    }
                },
                cancel = activity.getString(R.string.category_members_keep_invitation)
            )
        }

        fun showInvite() {
            if (!canManage()) return
            repository.sharing(cid) { ready ->
                if (!canManage()) return@sharing
                if (ready.getOrDefault(false)) invite(category, ::invitationsChanged, ::canManage, ::showFeedback)
                else setStatus(activity.getString(R.string.category_members_wait_for_sync))
            }
        }
        binding.invite.setOnClickListener { showInvite() }
        shareButton.setOnClickListener { showInvite() }
        binding.leave.setOnClickListener {
            if (!attached() || displayed?.role == "OWNER") return@setOnClickListener
            dialogs.confirm("Rời danh mục?", "Bạn sẽ mất quyền truy cập " + category.name + ".",
                Action("Rời danh mục", Tone.DESTRUCTIVE) {
                    if (attached()) {
                        command("LEAVE", mapOf("category_id" to cid), "Đã rời danh mục.", ::showFeedback) {
                            if (attached()) close()
                            changed()
                        }
                    }
                })
        }

        if (category.role != "OWNER") {
            binding.panel.isVisible = true
            setStatus(activity.getString(R.string.category_members_loading))
        }
        repository.cachedMembers(category) { result ->
            if (!attached()) return@cachedMembers
            result.onSuccess(::display)
            if (result.getOrNull()?.synced ?: (category.revision > 0)) refresh()
            else result.onFailure {
                showMembership(null, false)
                setStatus(reason(it.businessCode()), error = true)
            }
        }
    }

    private fun memberActions(category: WorkspaceCategory, member: WorkspaceMember, name: String,
                              canManage: () -> Boolean, feedback: (String, Boolean) -> Unit, changed: () -> Unit) {
        val fields = mapOf("category_id" to category.ref.id, "target_uid" to member.uid, "base_revision" to member.revision)
        val nextRole = if (member.role == "EDITOR") "VIEWER" else "EDITOR"
        choose(name, linkedMapOf(
            "Đổi thành " + roleLabel(nextRole) to {
                if (canManage()) command("ROLE", fields + ("role" to nextRole), feedback = feedback, success = changed)
            },
            "Gỡ thành viên" to {
                if (canManage()) {
                    dialogs.confirm("Gỡ $name?", "$name sẽ mất quyền truy cập " + category.name + ".",
                        Action("Gỡ", Tone.DESTRUCTIVE) {
                            if (canManage()) command("REMOVE_MEMBER", fields, feedback = feedback, success = changed)
                        })
                }
            }
        ))
    }

    private fun invite(category: WorkspaceCategory, changed: () -> Unit, canManage: () -> Boolean,
                       feedback: (String, Boolean) -> Unit) {
        if (!canManage()) return
        dialogs.form(
            title = "Mời vào " + category.name,
            label = "Email người nhận",
            submitLabel = "Chọn quyền",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        ) { email, finish ->
            if (!canManage()) {
                finish(null)
                return@form
            }
            val address = email.trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                finish("Địa chỉ email không hợp lệ.")
                return@form
            }
            finish(null)
            choose("Quyền của $address", linkedMapOf(
                "Chỉnh sửa" to { if (canManage()) sendInvite(category, address, "EDITOR", changed, feedback) },
                "Chỉ xem" to { if (canManage()) sendInvite(category, address, "VIEWER", changed, feedback) }
            ))
        }
    }

    private fun sendInvite(category: WorkspaceCategory, email: String, role: String, changed: () -> Unit,
                           feedback: (String, Boolean) -> Unit) {
        command("INVITE", mapOf("category_id" to category.ref.id, "email" to email, "role" to role, "invitation_id" to uuid()),
            "Đã gửi lời mời trong ứng dụng.", feedback, changed)
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
        repository.online({ result ->
            if (!active) return@online
            result.fold({ rows ->
                val actions = rows.map { row ->
                    val title = row.string("category_name") + " · " + roleLabel(row.string("offered_role")) +
                        " · " + inviteStatus(row.string("status"))
                    title to { if (active) invitation(row) }
                }
                choices("Lời mời danh mục", actions)
            }, ::error)
        }) { it.pages("INBOX") }
    }

    private fun invitation(row: Row) {
        val fields = mapOf("invitation_id" to row["id"], "category_id" to row["category_id"], "base_revision" to row["revision"])
        val end = Instant.ofEpochMilli(row.long("expires_at")).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val note = roleLabel(row.string("offered_role")) + " · " + inviteStatus(row.string("status")) + "\nHạn: $end"
        val title = row.string("category_name")
        val message = "${row["inviter_name"]}\n$note"
        if (row["status"] != "PENDING") {
            dialogs.show(title, message)
        } else {
            dialogs.show(title, message,
                primary = Action("Chấp nhận") {
                    command("ACCEPT", fields, "Đã tham gia danh mục. Danh mục sẽ xuất hiện sau khi đồng bộ.") {}
                },
                secondary = Action("Từ chối", Tone.DESTRUCTIVE) { command("DECLINE", fields) { invitations() } },
                extra = Action("Đóng", Tone.NEUTRAL) {})
        }
    }

    fun conflicts(changed: () -> Unit) {
        val session = AccountSession.current(activity) ?: return
        repository.conflicts { result ->
            if (!active || AccountSession.current(activity) !== session) return@conflicts
            result.fold({ rows ->
                dialogs.conflictChoices(rows.map { row ->
                    val title = (row["target_label"] as? String)?.takeIf(String::isNotBlank)
                        ?: activity.getString(if (row["entity_type"] == "CATEGORY") {
                            R.string.sync_conflict_category_change
                        } else R.string.sync_conflict_unknown_task)
                    val category = (row["category_label"] as? String)?.takeIf(String::isNotBlank)
                        ?: activity.getString(R.string.sync_conflict_unknown_category)
                    val issue = restoreConflictReason(row)?.let(activity::getString) ?: reason(row.string("last_error"))
                    ConflictChoice(title, category, issue) { showConflict(row, session, changed) }
                })
            }, ::error)
        }
    }

    private fun showConflict(row: Row, session: AccountSession, changed: () -> Unit) {
        fun isCurrent() = active && AccountSession.current(activity) === session
        if (!isCurrent()) return
        val operationId = row.string("operation_id")
        val comparison = buildSyncConflictComparison(row, ZoneId.systemDefault(), activity::getString)
        val title = activity.getString(comparison.title)
        val discard = Action("Bỏ thay đổi", Tone.DESTRUCTIVE) {
            if (isCurrent()) {
                repository.discard(operationId) { result ->
                    if (isCurrent()) result.fold({ changed() }, ::error)
                }
            }
        }.takeUnless { row["default_category_pending"] == true }
        val revision = ((row["current"] as? Map<*, *>)?.get("revision") as? Number)?.toLong()
        val reapplyFields = (row["reapply_fields"] as? Set<*>).orEmpty()
        val selectable = comparison.fields.mapNotNull { field ->
            field.key?.takeIf { field.changed && it in reapplyFields }
        }.toSet()

        if (revision == null || selectable.isEmpty() || discard == null) {
            dialogs.show(title, comparison.summary, primary = discard,
                content = syncConflictTable(activity, comparison.fields))
            return
        }

        dialogs.selectConflictFields(
            title = title,
            content = { selected -> syncConflictTable(activity, comparison.fields, selectable, selected) },
            discard = discard
        ) { selected ->
            if (!isCurrent()) return@selectConflictFields
            repository.reapplyConflict(operationId, revision, selected) { result ->
                if (!isCurrent()) return@reapplyConflict
                result.fold({
                    changed()
                    dialogs.notice(activity.getString(R.string.sync_conflict_reapply_saved))
                }, { failure ->
                    when {
                        failure is WorkspaceRequestError && failure.code == "REVISION_CONFLICT" -> {
                            dialogs.notice(activity.getString(R.string.sync_conflict_current_updated), error = true)
                            conflicts(changed)
                        }
                        failure is WorkspaceRequestError -> error(failure)
                        else -> dialogs.notice(failure.message ?: activity.getString(R.string.sync_conflict_reapply_failed),
                            error = true)
                    }
                })
            }
        }
    }

    private fun command(kind: String, data: Row, message: String? = null,
                        feedback: (String, Boolean) -> Unit = dialogs::notice, success: () -> Unit) {
        val request = data + mapOf("kind" to kind, "operation_id" to uuid(), "client_occurred_at" to System.currentTimeMillis())
        repository.online({ result ->
            if (!active) return@online
            result.fold({ value ->
                success()
                val pending = "Máy chủ đang dọn dữ liệu liên quan.".takeIf { value["pending"] == true }
                listOfNotNull(message, pending).takeIf { it.isNotEmpty() }?.let {
                    feedback(it.joinToString(" "), false)
                }
            }, { failure -> feedback(reason(failure.businessCode()), true) })
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
        "INVITATION_NOT_PENDING" -> "Lời mời đã được xử lý"
        "INVITATION_ALREADY_PENDING" -> "Đã có một lời mời đang chờ"
        "ALREADY_ACTIVE_MEMBER" -> "Người này đã là thành viên"
        "NAME_ALREADY_EXISTS" -> "Tên danh mục đã tồn tại"
        "CATEGORY_BUSY" -> "Máy chủ đang dọn danh mục. Hãy thử lại sau"
        "NETWORK", "UNAVAILABLE", "DEADLINE_EXCEEDED" -> "Không thể kết nối máy chủ"
        else -> "Không thể hoàn tất thao tác"
    }

    private fun error(error: Throwable?) = dialogs.notice(reason(error?.businessCode().orEmpty()), error = true)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun choose(title: String, options: Map<String, () -> Unit>) =
        choices(title, options.entries.map { it.key to it.value })

    private fun choices(title: String, options: List<Pair<String, () -> Unit>>) = dialogs.choices(title, options)
}
