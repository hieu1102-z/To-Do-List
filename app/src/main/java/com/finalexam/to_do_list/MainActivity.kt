
package com.finalexam.to_do_list

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import com.finalexam.to_do_list.controller.WorkspaceActivity
import com.finalexam.to_do_list.controller.auth.LoginActivity
import com.finalexam.to_do_list.controller.home.HomeActivity
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.Action
import com.finalexam.to_do_list.controller.home.WorkspaceDialogs.Tone
import com.finalexam.to_do_list.model.repository.AccountSession
import com.finalexam.to_do_list.model.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : WorkspaceActivity() {
    private val auth by lazy { AuthRepository.get(this) }
    private val dialogs by lazy { WorkspaceDialogs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply {
            addView(ProgressBar(context), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        prepare()
    }

    private fun prepare(confirmedUid: String? = null) {
        val app = applicationContext
        val uid = auth.user?.uid
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AccountSession.cleanupLegacy(app)
                    uid?.let { AccountSession.prepare(app, it, confirmedUid == it) }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            result.fold({ pending ->
                when {
                    uid == null || auth.user?.uid != uid -> open(LoginActivity::class.java)
                    pending == null -> open(HomeActivity::class.java)
                    else -> {
                        val detail = if (pending.unknown) "Không đọc được toàn bộ hàng đợi của tài khoản cũ."
                            else "Tài khoản cũ còn ${pending.count} thay đổi chưa đồng bộ."
                        dialogs.show("Đổi tài khoản?", "$detail Nếu tiếp tục, dữ liệu tài khoản cũ trên thiết bị sẽ bị xóa.",
                            Action("Xóa và tiếp tục", Tone.DESTRUCTIVE) { prepare(uid) },
                            Action("Hủy", Tone.NEUTRAL) { auth.signOut(); open(LoginActivity::class.java) },
                            onCancel = { auth.signOut(); open(LoginActivity::class.java) })
                    }
                }
            }, {
                dialogs.show("Không thể chuẩn bị dữ liệu",
                    "Chưa thể dọn hoặc mở dữ liệu trên thiết bị. Vui lòng thử lại.",
                    Action("Thử lại") { prepare(confirmedUid) },
                    Action("Về đăng nhập", Tone.NEUTRAL) {
                        auth.signOut()
                        open(LoginActivity::class.java)
                    }, onCancel = { auth.signOut(); open(LoginActivity::class.java) })
            })
        }
    }

    private fun open(screen: Class<*>) {
        startActivity(Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
