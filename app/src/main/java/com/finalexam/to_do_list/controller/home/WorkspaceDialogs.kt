package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.databinding.DialogWorkspaceBinding
import com.finalexam.to_do_list.databinding.ViewWorkspaceNoticeBinding

internal class WorkspaceDialogs(private val activity: ComponentActivity) {
    enum class Tone { NEUTRAL, PRIMARY, DESTRUCTIVE }

    data class Action(val label: String, val tone: Tone = Tone.PRIMARY, val run: () -> Unit)

    private val closeLabel get() = activity.getString(R.string.workspace_dialog_close)
    private val cancelLabel get() = activity.getString(R.string.task_delete_cancel)
    private val active get() = !activity.isFinishing && !activity.isDestroyed
    private var notice: ViewWorkspaceNoticeBinding? = null

    fun show(
        title: String,
        message: String? = null,
        primary: Action? = null,
        secondary: Action = Action(closeLabel, Tone.NEUTRAL) {},
        extra: Action? = null,
        onCancel: (() -> Unit)? = null
    ) = panel(title, message) { dialog, binding ->
        if (onCancel != null) dialog.setOnCancelListener { onCancel() }
        binding.secondary.bind(secondary, dialog)
        primary?.let { binding.primary.bind(it, dialog) }
        extra?.let { binding.extra.bind(it, dialog) }
    }

    fun confirm(title: String, message: String, action: Action, cancel: String = cancelLabel) =
        show(title, message, action, Action(cancel, Tone.NEUTRAL) {})

    fun choices(title: String, options: List<Pair<String, () -> Unit>>) {
        val message = activity.getString(R.string.workspace_dialog_empty).takeIf { options.isEmpty() }
        panel(title, message) { dialog, binding ->
            binding.secondary.bind(Action(closeLabel, Tone.NEUTRAL) {}, dialog)
            options.forEachIndexed { index, (label, select) ->
                val button = Button(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    bind(Action(label, Tone.NEUTRAL, select), dialog)
                }
                binding.options.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(8)
                })
            }
        }
    }

    fun customChoice(title: String, content: View, apply: Action) = panel(title, null) { dialog, binding ->
        binding.options.addView(content)
        binding.secondary.bind(Action(cancelLabel, Tone.NEUTRAL) {}, dialog)
        binding.primary.bind(apply, dialog)
    }

    fun form(
        title: String,
        label: String,
        submitLabel: String,
        message: String? = null,
        value: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        maxLength: Int? = null,
        submit: (String, (String?) -> Unit) -> Unit
    ) = panel(title, message) { dialog, binding ->
        binding.field.isVisible = true
        binding.fieldLabel.text = label
        binding.input.apply {
            this.inputType = inputType
            maxLength?.let { filters = arrayOf(InputFilter.LengthFilter(it)) }
            setText(value)
            setSelection(text.length)
        }
        binding.secondary.bind(Action(cancelLabel, Tone.NEUTRAL) {}, dialog)
        binding.primary.style(submitLabel, Tone.PRIMARY)
        fun setBusy(busy: Boolean) {
            binding.primary.isEnabled = !busy
            binding.primary.alpha = if (busy) 0.65f else 1f
            binding.secondary.isEnabled = !busy
            binding.input.isEnabled = !busy
            dialog.setCancelable(!busy)
            dialog.setCanceledOnTouchOutside(!busy)
        }
        binding.primary.setOnClickListener {
            if (!binding.primary.isEnabled) return@setOnClickListener
            binding.error.isVisible = false
            val text = binding.input.text.toString()
            setBusy(true)
            submit(text) { error ->
                if (!active || !dialog.isShowing) return@submit
                if (error == null) {
                    dialog.dismiss()
                } else {
                    setBusy(false)
                    binding.error.text = error
                    binding.error.isVisible = true
                }
            }
        }
        binding.input.setOnEditorActionListener { _, action, _ ->
            action == EditorInfo.IME_ACTION_DONE && binding.primary.isEnabled && binding.primary.performClick()
        }
    }

    fun notice(message: String, error: Boolean = false) {
        if (!active) return
        val container = activity.findViewById<FrameLayout>(android.R.id.content)
        container.post {
            if (!active) return@post
            notice?.let { container.removeView(it.root) }
            val banner = ViewWorkspaceNoticeBinding.inflate(activity.layoutInflater, container, false)
            notice = banner
            if (error) banner.accent.setBackgroundResource(R.color.task_delete_danger)
            val insets = ViewCompat.getRootWindowInsets(container)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val width = (container.width - dp(32) - (insets?.left ?: 0) -
                (insets?.right ?: 0)).coerceIn(1, dp(440))
            container.addView(banner.root, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (insets?.top ?: 0) + dp(12)
            })
            banner.message.text = message
            fun dismiss() {
                container.removeView(banner.root)
                if (notice === banner) notice = null
            }
            banner.close.setOnClickListener { dismiss() }
            val timeout = if (Build.VERSION.SDK_INT >= 29) {
                activity.getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(
                    5_000, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
                )
            } else 5_000
            banner.root.postDelayed({ dismiss() }, timeout.toLong())
        }
    }

    private fun panel(title: String, message: String?, bind: (AlertDialog, DialogWorkspaceBinding) -> Unit) {
        if (!active) return
        val binding = DialogWorkspaceBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).create()
        dialog.setView(binding.root, 0, 0, 0, 0)
        binding.heading.text = title
        ViewCompat.setAccessibilityHeading(binding.heading, true)
        binding.message.text = message
        binding.message.isVisible = !message.isNullOrEmpty()
        bind(dialog, binding)
        dialog.setOnShowListener {
            val width = (activity.resources.displayMetrics.widthPixels - dp(40)).coerceIn(1, dp(440))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.show()
    }

    private fun Button.bind(action: Action, dialog: AlertDialog) {
        style(action.label, action.tone)
        setOnClickListener {
            dialog.dismiss()
            action.run()
        }
    }

    private fun Button.style(label: String, tone: Tone) {
        text = label
        isVisible = true
        isAllCaps = false
        minHeight = dp(48)
        backgroundTintList = null
        setBackgroundResource(when (tone) {
            Tone.NEUTRAL -> R.drawable.bg_task_delete_cancel
            Tone.PRIMARY -> R.drawable.bg_primary
            Tone.DESTRUCTIVE -> R.drawable.bg_task_delete_confirm
        })
        setTextColor(ContextCompat.getColor(activity, if (tone == Tone.NEUTRAL) R.color.ink else android.R.color.white))
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
