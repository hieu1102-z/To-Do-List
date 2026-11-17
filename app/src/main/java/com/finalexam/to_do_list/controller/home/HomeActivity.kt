package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.controller.WorkspaceActivity
import com.finalexam.to_do_list.controller.auth.LoginActivity
import com.finalexam.to_do_list.databinding.ActivityHomeBinding
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import com.finalexam.to_do_list.util.TaskFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

open class HomeActivity : WorkspaceActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: GuestTaskRepository
    private lateinit var auth: AuthRepository
    private var tasks = emptyList<HomeTask>()
    private var filter = TaskFilter.TODAY
    private var category: String? = null
    private var selectedDate: LocalDate? = null
    private var selectedNavigation = 0
    private var loading = true
    private var failed = false
    private val zone get() = ZoneId.systemDefault()
    private val brand = Color.rgb(80, 100, 238)
    private val ink = Color.rgb(27, 31, 59)
    private val muted = Color.rgb(119, 125, 139)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        repository = GuestTaskRepository.get(this)
        auth = AuthRepository(this)
        filter = TaskFilter.entries.getOrElse(savedInstanceState?.getInt("filter") ?: 0) { TaskFilter.TODAY }
        category = savedInstanceState?.getString("category")
        selectedDate = savedInstanceState?.getString("selectedDate")?.let(LocalDate::parse)
        selectedNavigation = savedInstanceState?.getInt("navigation") ?: 0
        binding.addTask.setOnClickListener { editTask(null) }
        binding.search.setOnClickListener {
            binding.query.visibility = if (binding.query.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (binding.query.visibility == View.GONE) binding.query.text.clear() else binding.query.requestFocus()
        }
        binding.query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.notifications.setOnClickListener {
            val upcoming = tasks.filter { !it.done && it.dueAt != null }.sortedBy { it.dueAt }
            AlertDialog.Builder(this).setTitle("Việc sắp đến hạn")
                .setMessage(if (upcoming.isEmpty()) "Chưa có công việc đặt hạn. Bạn có thể thêm hạn khi tạo hoặc sửa công việc." else upcoming.take(10).joinToString("\n\n") { "${it.title}\n${taskMeta(it)}" })
                .setPositiveButton("Đóng", null).show()
        }
        binding.empty.setOnClickListener { if (failed) reload() }
        render()
    }

    override fun onResume() { super.onResume(); if (::repository.isInitialized) reload() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("filter", filter.ordinal)
        outState.putString("category", category)
        outState.putString("selectedDate", selectedDate?.toString())
        outState.putInt("navigation", selectedNavigation)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        loading = true
        failed = false
        repository.load { result ->
            if (isFinishing || isDestroyed) return@load
            loading = false
            result.fold({ tasks = it }, { failed = true; toast("Không thể đọc dữ liệu. Chạm thông báo để thử lại.") })
            render()
        }
    }

    private fun render() {
        val today = LocalDate.now(zone)
        binding.greeting.text = "Xin chào, " + (auth.user?.displayName?.takeIf { it.isNotBlank() } ?: "bạn")
        binding.date.text = today.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", Locale.forLanguageTag("vi-VN")))
        val todays = tasks.filter { it.dueAt == null || Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() == today }
        val completed = todays.count { it.done }
        val percent = if (todays.isEmpty()) 0 else completed * 100 / todays.size
        binding.progressLabel.text = "$completed/${todays.size} công việc"
        binding.progressPercent.text = "$percent%"
        binding.progress.progress = percent
        binding.filters.removeAllViews()
        TaskFilter.entries.forEach { option ->
            val button = TextView(this).apply {
                text = option.label; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (filter == option && selectedDate == null) Color.WHITE else muted)
                background = rounded(if (filter == option && selectedDate == null) brand else Color.TRANSPARENT, 9)
                isSelected = filter == option && selectedDate == null
                setOnClickListener { filter = option; selectedDate = null; render() }
            }
            binding.filters.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val query = binding.query.text.toString().trim()
        val visible = tasks.filter { task ->
            val dateMatches = selectedDate?.let { date -> !task.done && task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date } == true }
                ?: filter.includes(task.done, task.dueAt, today, zone)
            dateMatches && (category == null || task.category == category) &&
                (query.isEmpty() || task.title.contains(query, true) || task.description.contains(query, true))
        }
        binding.sectionTitle.text = category ?: selectedDate?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: when (filter) {
            TaskFilter.TODAY -> "Công việc hôm nay"
            TaskFilter.WEEK -> "Công việc 7 ngày tới"
            TaskFilter.OVERDUE -> "Công việc quá hạn"
            TaskFilter.DONE -> "Đã hoàn thành"
        }
        binding.count.text = "${visible.size} công việc"
        binding.taskList.removeAllViews()
        visible.forEach { addRow(it) }
        binding.empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.text = when {
            loading -> "Đang tải công việc…"
            failed -> "Không thể đọc dữ liệu.\nChạm để thử lại."
            query.isNotEmpty() -> "Không tìm thấy công việc phù hợp."
            else -> "Chưa có công việc nào.\nNhấn + để bắt đầu ngày của bạn."
        }
        binding.addTask.isEnabled = !loading && !failed
        buildNavigation()
    }

    private fun addRow(task: HomeTask) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            setPadding(dp(8), dp(10), dp(14), dp(10)); background = rounded(Color.WHITE, 16, Color.rgb(232, 234, 240))
            isClickable = true; isFocusable = true
            setOnClickListener { editTask(task) }
            setOnLongClickListener { confirmDelete(task); true }
        }
        val check = CheckBox(this).apply {
            isChecked = task.done
            setButtonDrawable(R.drawable.task_checkbox)
            buttonTintList = null
            contentDescription = if (task.done) "Bỏ hoàn thành ${task.title}" else "Hoàn thành ${task.title}"
            setOnClickListener { isEnabled = false; repository.toggle(task.id) { changed(it) } }
        }
        row.addView(check, LinearLayout.LayoutParams(dp(40), dp(48)))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        content.addView(label(task.title, 14f, ink, true).apply {
            if (task.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        })
        content.addView(label(taskMeta(task), 11f, muted).apply { setPadding(0, dp(7), 0, dp(7)) })
        val badgeColor = when (task.priority) { 3 -> 0xFFFCE9EC.toInt(); 1 -> 0xFFE6F3EC.toInt(); else -> 0xFFFFF2D8.toInt() }
        val badge = label(when (task.priority) { 3 -> "Cao"; 1 -> "Thấp"; else -> "Trung bình" }, 10f,
            when (task.priority) { 3 -> 0xFFB94662.toInt(); 1 -> 0xFF468466.toInt(); else -> 0xFF9B762E.toInt() }).apply {
            background = rounded(badgeColor, 5); setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        content.addView(badge, LinearLayout.LayoutParams(-2, -2))
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        binding.taskList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun taskMeta(task: HomeTask): String {
        val date = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val due = when {
            date == null -> "Không có hạn"
            date.toLocalDate() == LocalDate.now(zone) -> "Hôm nay, " + date.format(DateTimeFormatter.ofPattern("HH:mm"))
            else -> date.format(DateTimeFormatter.ofPattern("dd/MM, HH:mm"))
        }
        return "$due · ${task.category}"
    }

    private fun buildNavigation() {
        binding.navigation.removeAllViews()
        listOf("Trang chủ" to R.drawable.ic_home, "Lịch" to R.drawable.ic_calendar, "Danh mục" to R.drawable.ic_grid, "Cá nhân" to R.drawable.ic_person).forEachIndexed { index, (name, icon) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                contentDescription = name; isClickable = true; isFocusable = true; isSelected = selectedNavigation == index
                setOnClickListener {
                    when (index) {
                        0 -> { selectedNavigation = 0; category = null; selectedDate = null; render() }
                        1 -> selectCalendarDate()
                        2 -> selectCategory()
                        3 -> accountMenu()
                    }
                }
            }
            item.addView(ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(if (selectedNavigation == index) brand else muted) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(label(name, 10f, if (selectedNavigation == index) brand else muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
            binding.navigation.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun selectCalendarDate() {
        val date = selectedDate ?: LocalDate.now(zone)
        DatePickerDialog(this, { _, year, month, day ->
            selectedDate = LocalDate.of(year, month + 1, day); selectedNavigation = 1; render()
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun selectCategory() {
        val categories = arrayOf("Tất cả danh mục", "Cá nhân", "Học tập", "Công việc", "Mua sắm")
        AlertDialog.Builder(this).setTitle("Danh mục trên thiết bị")
            .setSingleChoiceItems(categories, categories.indexOf(category ?: categories[0])) { dialog, which ->
                category = if (which == 0) null else categories[which]; selectedNavigation = 2; render(); dialog.dismiss()
            }.setNeutralButton("Chia sẻ danh sách") { _, _ -> requireSharingAccount() }
            .setNegativeButton("Đóng", null).show()
    }

    private fun accountMenu() {
        val signedIn = auth.user != null
        AlertDialog.Builder(this).setTitle(if (signedIn) auth.user?.email else "Bạn đang dùng chế độ khách")
            .setMessage("Công việc trong không gian offline được lưu trên thiết bị. Đăng nhập không tự tải dữ liệu này lên mạng.")
            .setPositiveButton(if (signedIn) "Đăng xuất" else "Đăng nhập") { _, _ ->
                if (signedIn) { auth.signOut(); render(); toast("Đã đăng xuất. Công việc offline vẫn được giữ.") }
                else startActivity(Intent(this, LoginActivity::class.java))
            }.setNeutralButton("Chia sẻ danh sách") { _, _ -> requireSharingAccount() }
            .setNegativeButton("Đóng", null).show()
    }

    private fun requireSharingAccount() {
        if (auth.user == null) {
            AlertDialog.Builder(this).setTitle("Đăng nhập để chia sẻ")
                .setMessage("Bạn cần tài khoản để chia sẻ danh sách. Các chức năng offline vẫn dùng được mà không cần đăng nhập.")
                .setPositiveButton("Đăng nhập") { _, _ -> startActivity(Intent(this, LoginActivity::class.java)) }
                .setNegativeButton("Tiếp tục offline", null).show()
        } else {
            AlertDialog.Builder(this).setTitle("Không gian offline")
                .setMessage("Bạn đã đăng nhập. Chia sẻ danh sách và chuyển dữ liệu từ thiết bị lên tài khoản sẽ được bổ sung khi triển khai backend chia sẻ.")
                .setPositiveButton("Đã hiểu", null).show()
        }
    }

    private fun editTask(task: HomeTask?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        fun input(hintText: String, value: String, multiline: Boolean = false) = EditText(this).apply {
            hint = hintText; setText(value); textSize = 14f; setTextColor(ink)
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = if (multiline) 3 else 1
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val title = input("Tiêu đề *", task?.title ?: "").apply { filters = arrayOf(InputFilter.LengthFilter(200)) }
        val description = input("Mô tả", task?.description ?: "", true)
        form.addView(label("Danh mục", 12f, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
        val categories = listOf("Cá nhân", "Học tập", "Công việc", "Mua sắm")
        val categoryInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            setSelection(categories.indexOf(task?.category ?: category ?: "Cá nhân").coerceAtLeast(0))
        }
        form.addView(categoryInput, LinearLayout.LayoutParams(-1, dp(48)))
        form.addView(label("Ưu tiên", 12f, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
        val priority = Spinner(this).apply {
            adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Thấp", "Trung bình", "Cao"))
            setSelection((task?.priority ?: 2) - 1)
        }
        form.addView(priority, LinearLayout.LayoutParams(-1, dp(48)))
        var dueAt = task?.dueAt
        val dateButton = Button(this).apply { isAllCaps = false; text = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) } ?: "Chọn hạn hoàn thành" }
        dateButton.setOnClickListener {
            val calendar = Calendar.getInstance().apply { dueAt?.let { timeInMillis = it } }
            DatePickerDialog(this, { _, year, month, day ->
                TimePickerDialog(this, { _, hour, minute ->
                    calendar.set(year, month, day, hour, minute, 0); calendar.set(Calendar.MILLISECOND, 0)
                    dueAt = calendar.timeInMillis
                    dateButton.text = Instant.ofEpochMilli(dueAt!!).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        form.addView(dateButton, LinearLayout.LayoutParams(-1, dp(48)))
        form.addView(Button(this).apply { text = "Bỏ hạn hoàn thành"; isAllCaps = false; setOnClickListener { dueAt = null; dateButton.text = "Chọn hạn hoàn thành" } }, LinearLayout.LayoutParams(-1, dp(48)))
        val scroll = ScrollView(this).apply { addView(form) }
        val builder = AlertDialog.Builder(this).setTitle(if (task == null) "Thêm công việc" else "Sửa công việc")
            .setView(scroll).setPositiveButton("Lưu", null).setNegativeButton("Hủy", null)
        if (task != null) builder.setNeutralButton("Xóa") { _, _ -> confirmDelete(task) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.toString().trim().isEmpty()) { title.error = "Vui lòng nhập tiêu đề"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                repository.save(task?.id, title.text.toString(), description.text.toString(), categories[categoryInput.selectedItemPosition], priority.selectedItemPosition + 1, dueAt) { result ->
                    if (isFinishing || isDestroyed) return@save
                    if (result.isSuccess) { dialog.dismiss(); reload() }
                    else { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; toast("Không thể lưu công việc. Vui lòng thử lại.") }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(task: HomeTask) {
        AlertDialog.Builder(this).setTitle("Xóa công việc?").setMessage("“${task.title}” sẽ bị xóa khỏi thiết bị.")
            .setNegativeButton("Hủy", null).setPositiveButton("Xóa") { _, _ -> repository.delete(task.id) { changed(it) } }.show()
    }

    private fun changed(result: Result<Unit>) {
        if (isFinishing || isDestroyed) return
        if (result.isFailure) toast("Không thể lưu thay đổi. Vui lòng thử lại.")
        reload()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
}
