package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.WorkspaceActivity
import com.finalexam.to_do_list.databinding.ActivityAddTaskBinding
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import com.finalexam.to_do_list.util.TaskDefaults
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

class AddTaskActivity : WorkspaceActivity() {
    private lateinit var binding: ActivityAddTaskBinding
    private lateinit var model: AddTaskViewModel
    private var dueAt: Long = TaskDefaults.nextMidnight()
    private var reminderMinutes: Long? = 0L
    private var priority = 2
    private var repeatType = "NONE"
    private var category = TaskDefaults.UNCATEGORIZED
    private var categoryNames = TaskDefaults.categories
    private val reminderValues = listOf(null, 0L, 5L, 15L, 30L, 60L, 1440L)
    private val reminderLabels = arrayOf("Không nhắc", "Đúng giờ", "Trước 5 phút", "Trước 15 phút", "Trước 30 phút", "Trước 1 giờ", "Trước 1 ngày")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        model = ViewModelProvider(this)[AddTaskViewModel::class.java]
        category = savedInstanceState?.getString("category")
            ?.takeIf { it in categoryNames } ?: TaskDefaults.UNCATEGORIZED
        priority = savedInstanceState?.getInt("priority", 2) ?: 2
        repeatType = savedInstanceState?.getString("repeatType") ?: "NONE"
        dueAt = savedInstanceState?.takeIf { it.containsKey("dueAt") }?.getLong("dueAt")
            ?: TaskDefaults.nextMidnight()
        reminderMinutes = if (savedInstanceState?.getBoolean("noReminder") == true) null
            else savedInstanceState?.getLong("reminderMinutes", 0L) ?: 0L
        setupDropdowns()
        GuestTaskRepository.get(this).categories { result ->
            result.onSuccess { names ->
                if (isFinishing || isDestroyed) return@onSuccess
                categoryNames = names.ifEmpty { TaskDefaults.categories }
                if (category !in categoryNames) category = categoryNames.first()
                binding.categories.adapter = adapter(categoryNames)
                binding.categories.setSelection(categoryNames.indexOf(category))
            }
        }
        binding.close.setOnClickListener { if (!saving()) finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!saving()) finish() }
        })
        binding.due.setOnClickListener { selectDue() }
        binding.reminder.setOnClickListener {
            if (saving()) return@setOnClickListener
            AlertDialog.Builder(this).setTitle(R.string.task_editor_reminder)
                .setSingleChoiceItems(reminderLabels, reminderValues.indexOf(reminderMinutes)) { dialog, index ->
                    reminderMinutes = reminderValues[index]; renderChoices(); dialog.dismiss()
                }.setNegativeButton("Hủy", null).show()
        }
        binding.save.setOnClickListener {
            if (binding.taskTitle.text.toString().trim().isEmpty()) {
                binding.taskTitle.error = getString(R.string.task_editor_title_error)
                binding.taskTitle.requestFocus()
                return@setOnClickListener
            }
            model.save(GuestTaskRepository.get(this), binding.taskTitle.text.toString(),
                binding.description.text.toString(), category, priority, dueAt, reminderMinutes, repeatType)
        }
        renderChoices()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state == AddTaskViewModel.SaveState.SAVED) {
                        setResult(RESULT_OK); finish()
                    } else {
                        val busy = state == AddTaskViewModel.SaveState.SAVING
                        binding.save.isEnabled = !busy
                        binding.close.isEnabled = !busy
                        binding.taskTitle.isEnabled = !busy
                        binding.description.isEnabled = !busy
                        binding.due.isEnabled = !busy
                        binding.reminder.isEnabled = !busy
                        binding.categories.isEnabled = !busy
                        binding.repeats.isEnabled = !busy
                        binding.save.setText(if (busy) R.string.task_editor_saving else R.string.task_editor_save)
                        binding.error.visibility = if (state == AddTaskViewModel.SaveState.ERROR) View.VISIBLE else View.GONE
                        if (state == AddTaskViewModel.SaveState.ERROR) binding.error.setText(R.string.task_editor_save_error)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("category", category)
        outState.putInt("priority", priority)
        outState.putString("repeatType", repeatType)
        outState.putLong("dueAt", dueAt)
        outState.putBoolean("noReminder", reminderMinutes == null)
        reminderMinutes?.let { outState.putLong("reminderMinutes", it) }
        super.onSaveInstanceState(outState)
    }

    private fun saving() = model.state.value == AddTaskViewModel.SaveState.SAVING

    private fun selectDue() {
        if (saving()) return
        val calendar = Calendar.getInstance().apply { timeInMillis = dueAt }
        val date = DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(year, month, day, hour, minute, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                dueAt = calendar.timeInMillis
                renderChoices()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        date.show()
    }

    private fun renderChoices() {
        binding.due.text = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm"))
        binding.reminder.text = reminderLabels[reminderValues.indexOf(reminderMinutes)]
        binding.priorities.removeAllViews()
        listOf(Triple("Cao", "#FCECEF", "#BA4B56"), Triple("Trung bình", "#FFF4DF", "#94651C"), Triple("Thấp", "#E8F5ED", "#358260"))
            .forEachIndexed { index, (label, fill, textColor) ->
                chip(binding.priorities, label, priority == 3 - index, fill, textColor) {
                    priority = 3 - index; renderChoices()
                }
            }
    }

    private fun setupDropdowns() {
        binding.categories.adapter = adapter(categoryNames)
        binding.categories.setSelection(categoryNames.indexOf(category))
        binding.categories.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                category = categoryNames[position]
            }
        }
        binding.repeats.adapter = adapter(listOf("Không", "Hằng ngày", "Hằng tuần", "Hằng tháng"))
        binding.repeats.setSelection(listOf("NONE", "DAILY", "WEEKLY", "MONTHLY").indexOf(repeatType).coerceAtLeast(0))
        binding.repeats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                repeatType = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY")[position]
            }
        }
    }

    private fun adapter(items: List<String>) = ArrayAdapter(this, R.layout.item_task_selected, items).apply {
        setDropDownViewResource(R.layout.item_task_dropdown)
    }

    private fun chip(parent: ViewGroup, label: String, selected: Boolean,
                     fill: String? = null, textColor: String? = null, onClick: () -> Unit) {
        val brand = Color.parseColor("#4F6BED")
        val view = TextView(this).apply {
            text = label; textSize = 11f; gravity = Gravity.CENTER
            minHeight = dp(30); includeFontPadding = false
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(textColor?.let(Color::parseColor) ?: if (selected) brand else Color.parseColor("#5C6170"))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(fill?.let(Color::parseColor) ?: if (selected) Color.parseColor("#E5EDFF") else Color.WHITE)
                setStroke(dp(1), if (selected) brand else Color.parseColor("#D6DBE5"))
            }
            isSelected = selected; isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"; info.isCheckable = true; info.isChecked = selected
                }
            }
            setOnClickListener { if (!saving()) onClick() }
        }
        parent.addView(view, ViewGroup.LayoutParams(-2, -2))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
