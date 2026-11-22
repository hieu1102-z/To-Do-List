package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.databinding.DialogTaskDetailBinding
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import com.finalexam.to_do_list.util.TaskDetailText

/** Figma detail content adapted to a floating window. */
internal object TaskDetailDialog {
    fun show(activity: ComponentActivity, task: HomeTask, repository: GuestTaskRepository,
             onEdit: () -> Unit, onDelete: (AlertDialog) -> Unit, onChanged: () -> Unit) {
        val binding = DialogTaskDetailBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).create()
        dialog.setView(binding.root, 0, 0, 0, 0)
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun badge(text: String, fill: String, color: String, stroke: String = "#D9DCE5") {
            binding.badges.addView(TextView(activity).apply {
                this.text = text; textSize = 11f; gravity = Gravity.CENTER
                includeFontPadding = false; minHeight = dp(30)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setTypeface(typeface, Typeface.NORMAL); setTextColor(Color.parseColor(color))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat(); setColor(Color.parseColor(fill))
                    setStroke(dp(1), Color.parseColor(stroke))
                }
            }, ViewGroup.LayoutParams(-2, -2))
        }
        badge(activity.getString(if (task.done) R.string.task_detail_done else R.string.task_detail_pending),
            if (task.done) "#E8F5ED" else "#EAEFFD", if (task.done) "#358260" else "#4F6BED",
            if (task.done) "#358260" else "#4F6BED")
        when (task.priority) {
            3 -> badge("Cao", "#FCECEF", "#BA4B56")
            1 -> badge("Thấp", "#E8F5ED", "#358260")
            else -> badge("Trung bình", "#FFF4DF", "#94651C")
        }
        badge(TaskDetailText.repeat(task.repeatType), "#FFFFFF", "#5B6270")
        binding.taskTitle.text = task.title
        ViewCompat.setAccessibilityHeading(binding.taskTitle, true)
        binding.due.text = TaskDetailText.due(task)
        binding.reminder.text = TaskDetailText.reminder(task)
        binding.category.text = task.category
        binding.participant.text = task.participantNames?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.task_detail_no_participant)
        binding.description.text = task.description.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.task_detail_no_description)

        fun showSubtasks() {
            repository.subtasks(task.id) { result ->
                if (activity.isFinishing || activity.isDestroyed) return@subtasks
                result.fold({ items ->
                    binding.subtaskList.removeAllViews()
                    if (items.isEmpty()) {
                        binding.subtaskList.addView(TextView(activity).apply {
                            text = "Chưa có công việc con"; textSize = 12f; setTextColor(Color.rgb(119, 125, 139))
                            setPadding(0, dp(6), 0, dp(8))
                        })
                    }
                    items.forEach { subtask ->
                        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
                        lateinit var editSubtask: () -> Unit
                        editSubtask = {
                            val input = EditText(activity).apply { setSingleLine(true); setText(subtask.title); setSelection(text.length); filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
                            AlertDialog.Builder(activity).setTitle("Sửa công việc con").setView(input)
                                .setNegativeButton("Hủy", null).setPositiveButton("Lưu") { _, _ ->
                                    repository.updateSubtask(subtask.id, input.text.toString(), subtask.isDone) { changed ->
                                        if (changed.isSuccess) showSubtasks() else binding.error.apply { visibility = View.VISIBLE; text = "Không thể sửa công việc con." }
                                    }
                                }.show()
                        }
                        val check = CheckBox(activity).apply {
                            isChecked = subtask.isDone; setButtonDrawable(R.drawable.task_checkbox); buttonTintList = null
                            contentDescription = if (subtask.isDone) "Bỏ hoàn thành ${subtask.title}" else "Hoàn thành ${subtask.title}"
                            setOnCheckedChangeListener { _, checked ->
                                isEnabled = false
                                repository.updateSubtask(subtask.id, subtask.title, checked) { changed ->
                                    if (changed.isFailure) binding.error.apply { visibility = View.VISIBLE; text = "Không thể cập nhật công việc con." }
                                    else showSubtasks()
                                }
                            }
                        }
                        row.addView(check, LinearLayout.LayoutParams(dp(38), dp(42)))
                        val name = TextView(activity).apply {
                            text = subtask.title; textSize = 13f; setTextColor(Color.rgb(27, 31, 59)); isClickable = true
                            setOnClickListener { editSubtask.invoke() }
                        }
                        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(TextView(activity).apply {
                            text = "Xóa"; textSize = 12f; setTextColor(Color.rgb(190, 66, 82)); gravity = Gravity.CENTER
                            setPadding(dp(8), dp(8), dp(4), dp(8)); isClickable = true
                            setOnClickListener {
                                AlertDialog.Builder(activity).setMessage("Xóa công việc con này?")
                                    .setNegativeButton("Hủy", null)
                                    .setPositiveButton("Xóa") { _, _ ->
                                        repository.deleteSubtask(subtask.id) { deleted ->
                                            if (deleted.isSuccess) showSubtasks() else binding.error.apply { visibility = View.VISIBLE; text = "Không thể xóa công việc con." }
                                        }
                                    }.show()
                            }
                        })
                        binding.subtaskList.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                }, { binding.error.apply { visibility = View.VISIBLE; text = "Không thể tải công việc con." } })
            }
        }
        binding.addSubtask.setOnClickListener {
            val input = EditText(activity).apply { hint = "Tên công việc con"; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
            AlertDialog.Builder(activity).setTitle("Thêm công việc con").setView(input)
                .setNegativeButton("Hủy", null).setPositiveButton("Thêm") { _, _ ->
                    repository.addSubtask(task.id, input.text.toString()) { added ->
                        if (added.isSuccess) showSubtasks() else binding.error.apply { visibility = View.VISIBLE; text = added.exceptionOrNull()?.message ?: "Không thể thêm công việc con." }
                    }
                }.show()
        }
        showSubtasks()
        binding.completeLabel.setText(if (task.done) R.string.task_detail_uncomplete else R.string.task_detail_complete)
        binding.complete.contentDescription = binding.completeLabel.text
        binding.complete.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        binding.close.setOnClickListener { dialog.dismiss() }
        binding.edit.setOnClickListener { dialog.dismiss(); onEdit() }
        binding.delete.setOnClickListener { onDelete(dialog) }
        binding.complete.setOnClickListener {
            binding.complete.isEnabled = false
            binding.complete.alpha = 0.6f
            binding.edit.isEnabled = false; binding.delete.isEnabled = false
            dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false)
            binding.close.isEnabled = false
            repository.toggle(task.id) { result ->
                if (activity.isFinishing || activity.isDestroyed) return@toggle
                if (result.isSuccess) { dialog.dismiss(); onChanged() }
                else if (dialog.isShowing) {
                    binding.error.visibility = View.VISIBLE
                    binding.complete.isEnabled = true; binding.complete.alpha = 1f
                    binding.edit.isEnabled = true; binding.delete.isEnabled = true
                    binding.close.isEnabled = true
                    dialog.setCancelable(true); dialog.setCanceledOnTouchOutside(true)
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
        dialog.setOnShowListener {
            val display = activity.resources.displayMetrics
            val width = minOf(display.widthPixels - dp(48), dp(560)).coerceAtLeast(dp(240))
            binding.content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            binding.content.layoutParams = binding.content.layoutParams.apply {
                height = minOf(binding.content.measuredHeight, (display.heightPixels * 0.5f).toInt())
            }
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
