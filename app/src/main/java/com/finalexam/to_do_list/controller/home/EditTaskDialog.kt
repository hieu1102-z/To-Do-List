package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.databinding.ActivityAddTaskBinding
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import com.finalexam.to_do_list.util.TaskDefaults
import com.finalexam.to_do_list.util.TaskDetailText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** Uses the exact add form layout inside a floating, scrollable dialog. */
internal object EditTaskDialog {
    fun show(activity: ComponentActivity, task: HomeTask, repository: GuestTaskRepository,
             onDelete: (AlertDialog) -> Unit, onSaved: () -> Unit) {
        val form = ActivityAddTaskBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).create()
        dialog.setView(form.root, 0, 0, 0, 0)
        var busy = false
        var dueAt = task.dueAt
        var priority = task.priority
        var category = task.category
        var reminderChanged = false
        val originalOffset = task.remindAt?.let { at -> task.dueAt?.minus(at)?.div(60_000L) }
        var reminderMinutes: Long? = originalOffset
        val offsets = listOf(null, 0L, 5L, 15L, 30L, 60L, 1440L)
        val reminderLabels = arrayOf("Không nhắc", "Đúng giờ", "Trước 5 phút", "Trước 15 phút", "Trước 30 phút", "Trước 1 giờ", "Trước 1 ngày")
        val repeats = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY")
        val initialRepeat = repeats.indexOf(task.repeatType).coerceAtLeast(0)
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        form.root.setBackgroundResource(R.drawable.bg_task_detail)
        form.heading.setText(R.string.task_editor_edit_heading)
        form.close.setContentDescription(activity.getString(R.string.task_editor_close_edit))
        form.save.setText(R.string.task_editor_save_changes)
        form.taskTitle.setText(task.title)
        form.description.setText(task.description)
        fun renderDate() {
            form.due.text = dueAt?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))
            } ?: "Không có hạn"
        }
        fun renderPriorities() {
            form.priorities.removeAllViews()
            listOf(Triple("Cao", "#FCECEF", "#BA4B56"), Triple("Trung bình", "#FFF4DF", "#94651C"), Triple("Thấp", "#E8F5ED", "#358260"))
                .forEachIndexed { index, (label, fill, color) ->
                    val selected = priority == 3 - index
                    form.priorities.addView(TextView(activity).apply {
                        text = label; textSize = 11f; gravity = Gravity.CENTER
                        includeFontPadding = false; minHeight = dp(30)
                        setPadding(dp(10), dp(7), dp(10), dp(7)); setTextColor(Color.parseColor(color))
                        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat(); setColor(Color.parseColor(fill))
                            setStroke(dp(1), Color.parseColor(if (selected) "#4F6BED" else "#D6DBE5"))
                        }
                        isSelected = selected; isFocusable = true
                        contentDescription = "$label${if (selected) ", đã chọn" else ""}"
                        setOnClickListener { if (!busy) { priority = 3 - index; renderPriorities() } }
                    }, ViewGroup.LayoutParams(-2, -2))
                }
        }
        fun adapter(values: List<String>) = ArrayAdapter(activity, R.layout.item_task_selected, values).apply {
            setDropDownViewResource(R.layout.item_task_dropdown)
        }
        val categories = (TaskDefaults.categories + task.category).distinct()
        form.categories.adapter = adapter(categories)
        form.categories.setSelection(categories.indexOf(category))
        form.categories.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { category = categories[position] }
        }
        form.repeats.adapter = adapter(listOf("Không", "Hằng ngày", "Hằng tuần", "Hằng tháng"))
        form.repeats.setSelection(initialRepeat)
        form.repeats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != initialRepeat) {
                    form.repeats.setSelection(initialRepeat)
                    AlertDialog.Builder(activity).setTitle(R.string.task_editor_repeat)
                        .setMessage("Thay đổi lịch lặp chưa được triển khai. Lịch lặp hiện tại được giữ nguyên.")
                        .setPositiveButton("Đã hiểu", null).show()
                }
            }
        }
        form.reminder.text = TaskDetailText.reminder(task)
        form.reminder.setOnClickListener {
            if (busy) return@setOnClickListener
            AlertDialog.Builder(activity).setTitle(R.string.task_editor_reminder)
                .setSingleChoiceItems(reminderLabels, if (!reminderChanged && task.remindAt == null) 0 else offsets.indexOf(reminderMinutes)) { picker, index ->
                    reminderChanged = true; reminderMinutes = offsets[index]
                    form.reminder.text = reminderLabels[index]; picker.dismiss()
                }.setNegativeButton("Hủy", null).show()
        }
        form.due.setOnClickListener {
            if (busy) return@setOnClickListener
            val calendar = Calendar.getInstance().apply { dueAt?.let { timeInMillis = it } }
            val picker = DatePickerDialog(activity, { _, year, month, day ->
                TimePickerDialog(activity, { _, hour, minute ->
                    calendar.set(year, month, day, hour, minute, 0); calendar.set(Calendar.MILLISECOND, 0)
                    dueAt = calendar.timeInMillis; renderDate()
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
            picker.setButton(AlertDialog.BUTTON_NEUTRAL, "Bỏ hạn") { _, _ -> dueAt = null; renderDate() }
            picker.show()
        }
        val delete = TextView(activity).apply {
            text = activity.getString(R.string.task_detail_delete); textSize = 13f
            gravity = Gravity.CENTER; setTextColor(Color.parseColor("#BA4B56"))
            isFocusable = true
            setOnClickListener { if (!busy) onDelete(dialog) }
        }
        (form.heading.parent as LinearLayout).addView(delete, LinearLayout.LayoutParams(dp(48), dp(48)))
        form.close.setOnClickListener { if (!busy) dialog.dismiss() }
        fun setBusy(value: Boolean) {
            busy = value
            listOf(form.save, form.close, form.taskTitle, form.description, form.due, form.reminder, form.categories, form.repeats, delete)
                .forEach { it.isEnabled = !value }
            dialog.setCancelable(!value); dialog.setCanceledOnTouchOutside(!value)
            form.save.setText(if (value) R.string.task_editor_saving else R.string.task_editor_save_changes)
        }
        form.save.setOnClickListener {
            if (busy) return@setOnClickListener
            if (form.taskTitle.text.toString().trim().isEmpty()) {
                form.taskTitle.error = activity.getString(R.string.task_editor_title_error)
                form.taskTitle.requestFocus(); return@setOnClickListener
            }
            setBusy(true)
            form.error.visibility = View.GONE
            val callback: (Result<Unit>) -> Unit = { result ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (result.isSuccess) { dialog.dismiss(); onSaved() }
                    else if (dialog.isShowing) {
                        setBusy(false); form.error.setText(R.string.task_editor_save_error); form.error.visibility = View.VISIBLE
                    }
                }
            }
            if (reminderChanged) repository.updateWithReminder(task.id, form.taskTitle.text.toString(), form.description.text.toString(),
                category, priority, dueAt, reminderMinutes, callback)
            else repository.save(task.id, form.taskTitle.text.toString(), form.description.text.toString(), category, priority, dueAt, callback)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
        dialog.setOnShowListener {
            val display = activity.resources.displayMetrics
            val width = minOf(display.widthPixels - dp(48), dp(560))
            form.content.layoutParams = (form.content.layoutParams as LinearLayout.LayoutParams).apply { weight = 0f; height = -2 }
            form.content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            form.content.layoutParams = form.content.layoutParams.apply {
                height = minOf(form.content.measuredHeight, (display.heightPixels * 0.5f).toInt())
            }
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        renderDate(); renderPriorities(); dialog.show()
    }
}
