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
import com.finalexam.to_do_list.controller.auth.RegisterActivity
import com.finalexam.to_do_list.databinding.ActivityHomeBinding
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.AuthRepository
import com.finalexam.to_do_list.model.repository.GuestTaskRepository
import com.finalexam.to_do_list.model.repository.GuestProfilePreferences
import com.finalexam.to_do_list.util.TaskFilter
import com.finalexam.to_do_list.util.TaskDefaults
import com.finalexam.to_do_list.view.ChipFlowLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import com.finalexam.to_do_list.notification.NotificationHelper
import android.widget.Toast
import com.finalexam.to_do_list.notification.NotificationPreferences
import androidx.core.app.NotificationManagerCompat

open class HomeActivity : WorkspaceActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: GuestTaskRepository
    private lateinit var auth: AuthRepository
    private var tasks = emptyList<HomeTask>()
    private var filter = TaskFilter.TODAY
    private var category: String? = null
    private var selectedDate: LocalDate? = null
    private var selectedNavigation = 0
    private var calendarRefresh: (() -> Unit)? = null
    private var categoryFormOpen = false
    private var profileEditorOpen = false
    private var trashPageOpen = false
    private var notificationPageOpen = false
    private var searchPageOpen = false
    private var returnFromNotification: (() -> Unit)? = null
    private var searchRefresh: (() -> Unit)? = null
    private var profileAvatarImage: ImageView? = null
    private val profileAvatarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            GuestProfilePreferences.saveAvatar(this, uri.toString())
            profileAvatarImage?.apply { setImageURI(uri); visibility = View.VISIBLE }
        }
    }
    private var loading = true
    private var failed = false

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Toast.makeText(
                    this,
                    "Đã bật quyền thông báo!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Bạn chưa cấp quyền thông báo.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

//    private var personalDialog: AlertDialog? = null
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
        auth = AuthRepository.get(this)

        NotificationHelper.createChannel(this)
        requestNotificationPermission()

        filter = TaskFilter.entries.getOrElse(savedInstanceState?.getInt("filter") ?: 0) { TaskFilter.TODAY }
        category = savedInstanceState?.getString("category")
        selectedDate = savedInstanceState?.getString("selectedDate")?.let(LocalDate::parse)
        selectedNavigation = savedInstanceState?.getInt("navigation") ?: 0
        binding.addTask.setOnClickListener { editTask(null) }
        binding.search.setOnClickListener { showSearchScreen() }
        binding.query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.notifications.setOnClickListener {

            openNotificationPage { showHomeScreen() }
        }
        binding.empty.setOnClickListener { if (failed) reload() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    notificationPageOpen -> { notificationPageOpen = false; returnFromNotification?.invoke() ?: showHomeScreen() }
                    searchPageOpen -> { searchPageOpen = false; showHomeScreen() }
                    profileEditorOpen -> { profileEditorOpen = false; showPersonalScreen() }
                    trashPageOpen -> { trashPageOpen = false; showPersonalScreen() }
                    categoryFormOpen -> { categoryFormOpen = false; selectCategory() }
                    selectedNavigation != 0 -> showHomeScreen()
                    else -> finish()
                }
            }
        })
        render()
    }

    override fun onResume() {
        super.onResume()

        if (::repository.isInitialized) {
            updateNotificationBadge()
            auth.refreshProfile()
            reload()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("filter", filter.ordinal)
        outState.putString("category", category)
        outState.putString("selectedDate", selectedDate?.toString())
        outState.putInt("navigation", selectedNavigation)
        super.onSaveInstanceState(outState)
    }

    private fun updateNotificationBadge() {
        binding.notificationBadge.visibility =
            if (NotificationPreferences.hasUnreadNotification(this)) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
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
        binding.greeting.text = "Xin chào, " + (auth.user?.displayName?.takeIf { it.isNotBlank() } ?: GuestProfilePreferences.name(this) ?: "bạn")
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
            val dateMatches = if (category != null) true else selectedDate?.let { date -> !task.done && task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == date } == true }
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
            setOnClickListener { showTaskDetail(task) }
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
                        3 -> showPersonalScreen()
                    }
                }
            }
            item.addView(ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(if (selectedNavigation == index) brand else muted) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(label(name, 10f, if (selectedNavigation == index) brand else muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
            binding.navigation.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun selectCalendarDate() {
        showCalendarScreen(selectedDate ?: LocalDate.now(zone))
    }

    private fun showHomeScreen() {
        selectedNavigation = 0
        category = null
        selectedDate = null
        calendarRefresh = null
        searchRefresh = null
        setContentView(binding.root)
        render()
    }

    private fun showSearchScreen() {
        searchPageOpen = true
        calendarRefresh = null
        val bg = Color.rgb(246, 247, 251)
        val white = Color.WHITE
        val line = Color.rgb(218, 222, 234)
        var statusFilter = 0 // all, open, done
        var priorityFilter = 0 // all, high, medium, low
        var categoryFilter: String? = null
        var sortFilter = 0 // due date, priority, title
        var categoryNames = TaskDefaults.categories

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(36), dp(24), dp(28)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply {
            text = "‹"; textSize = 38f; gravity = Gravity.CENTER; setTextColor(ink)
            isClickable = true; setOnClickListener { searchPageOpen = false; showHomeScreen() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("Tìm kiếm & bộ lọc", 22f, ink, true).apply { setPadding(dp(8), 0, 0, 0) })
        content.addView(header)

        val searchBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(white, 14, brand)
            setPadding(dp(14), 0, dp(12), 0)
        }
        searchBox.addView(label("⌕", 26f, brand), LinearLayout.LayoutParams(dp(32), dp(54)))
        val query = EditText(this).apply {
            hint = "Tìm công việc"; textSize = 16f; setTextColor(ink); setSingleLine(true)
            setPadding(dp(8), dp(10), dp(8), dp(10)); background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        searchBox.addView(query, LinearLayout.LayoutParams(0, dp(54), 1f))
        val clear = TextView(this).apply {
            text = "⊗"; textSize = 22f; setTextColor(muted); gravity = Gravity.CENTER
            isClickable = true; setOnClickListener { query.text.clear() }
        }
        searchBox.addView(clear, LinearLayout.LayoutParams(dp(32), dp(48)))
        content.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(18) })

        val filterGroups = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(filterGroups)
        val resultsHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(14)) }
        resultsHeader.addView(label("Kết quả tìm kiếm", 18f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        val resultCount = label("0 công việc", 13f, muted)
        resultsHeader.addView(resultCount)
        content.addView(resultsHeader)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun addChoiceGroup(title: String, values: List<String>, selected: Int, onSelect: (Int) -> Unit) {
            filterGroups.addView(label(title, 14f, ink, true).apply { setPadding(0, dp(2), 0, dp(8)) })
            val flow = ChipFlowLayout(this)
            values.forEachIndexed { index, value ->
                val active = selected == index
                val chip = TextView(this).apply {
                    text = value; textSize = 12f; gravity = Gravity.CENTER; includeFontPadding = false
                    setPadding(dp(12), dp(8), dp(12), dp(8)); setTextColor(if (active) brand else muted)
                    background = rounded(if (active) Color.rgb(235, 239, 255) else white, 10, if (active) brand else line)
                    isClickable = true; isFocusable = true; setOnClickListener { onSelect(index) }
                }
                flow.addView(chip)
            }
            filterGroups.addView(flow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }

        fun renderSearch() {
            filterGroups.removeAllViews()
            addChoiceGroup("Trạng thái", listOf("Tất cả", "Chưa xong", "Đã xong"), statusFilter) { statusFilter = it; renderSearch() }
            addChoiceGroup("Ưu tiên", listOf("Tất cả", "Cao", "Trung bình", "Thấp"), priorityFilter) { priorityFilter = it; renderSearch() }
            val selectedCategoryIndex = categoryFilter?.let { categoryNames.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
            addChoiceGroup("Danh mục", listOf("Tất cả") + categoryNames, selectedCategoryIndex) {
                categoryFilter = if (it == 0) null else categoryNames[it - 1]
                renderSearch()
            }
            addChoiceGroup("Sắp xếp theo", listOf("Ngày hạn", "Ưu tiên", "Tên A–Z"), sortFilter) { sortFilter = it; renderSearch() }

            val needle = query.text.toString().trim()
            val filtered = tasks.filter { task ->
                (statusFilter == 0 || (statusFilter == 1 && !task.done) || (statusFilter == 2 && task.done)) &&
                    (priorityFilter == 0 || task.priority == 4 - priorityFilter) &&
                    (categoryFilter == null || task.category == categoryFilter) &&
                    (needle.isEmpty() || task.title.contains(needle, true) || task.description.contains(needle, true))
            }
            val ordered = when (sortFilter) {
                1 -> filtered.sortedWith(compareByDescending<HomeTask> { it.priority }.thenBy { it.dueAt ?: Long.MAX_VALUE })
                2 -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
                else -> filtered.sortedWith(compareBy<HomeTask> { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.priority })
            }
            resultCount.text = "${ordered.size} công việc"
            results.removeAllViews()
            if (ordered.isEmpty()) {
                results.addView(label(if (needle.isEmpty()) "Không có công việc phù hợp với bộ lọc." else "Không tìm thấy công việc phù hợp.", 14f, muted).apply { setPadding(dp(4), dp(16), dp(4), dp(20)) })
            } else ordered.forEach { task ->
                val overdue = !task.done && task.dueAt?.let { it < System.currentTimeMillis() } == true
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; background = rounded(white, 18, line)
                    setPadding(dp(12), dp(12), dp(14), dp(12)); isClickable = true; isFocusable = true
                    setOnClickListener { showTaskDetail(task) }
                }
                val check = CheckBox(this).apply {
                    isChecked = task.done; setButtonDrawable(R.drawable.task_checkbox); buttonTintList = null
                    contentDescription = if (task.done) "Bỏ hoàn thành ${task.title}" else "Hoàn thành ${task.title}"
                    setOnClickListener {
                        isEnabled = false
                        repository.toggle(task.id) { result ->
                            if (result.isFailure) toast("Không thể cập nhật công việc.")
                            refreshTasks()
                        }
                    }
                }
                card.addView(check, LinearLayout.LayoutParams(dp(40), dp(40)))
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(2), 0, 0) }
                details.addView(label(task.title, 15f, ink, true))
                val dueLabel = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd/MM, HH:mm")) } ?: "Không có hạn"
                details.addView(label("$dueLabel · ${task.category}", 12f, if (overdue) Color.rgb(205, 72, 85) else muted).apply { setPadding(0, dp(9), 0, dp(8)) })
                val badges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val badgeFill = when (task.priority) { 3 -> Color.rgb(252, 233, 236); 1 -> Color.rgb(231, 244, 237); else -> Color.rgb(255, 243, 220) }
                val badgeText = when (task.priority) { 3 -> Color.rgb(190, 66, 82); 1 -> Color.rgb(57, 133, 99); else -> Color.rgb(157, 111, 35) }
                badges.addView(label(when (task.priority) { 3 -> "Cao"; 1 -> "Thấp"; else -> "Trung bình" }, 11f, badgeText).apply {
                    background = rounded(badgeFill, 7); setPadding(dp(8), dp(4), dp(8), dp(4))
                })
                if (overdue) badges.addView(label("Quá hạn", 11f, Color.rgb(205, 72, 85)).apply { setPadding(dp(8), dp(3), 0, dp(3)) })
                details.addView(badges)
                card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                results.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderSearch() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        searchRefresh = { renderSearch() }
        renderSearch()
        setContentView(root)
        repository.categories { result -> result.onSuccess { names ->
            if (searchPageOpen) { categoryNames = names; renderSearch() }
        } }
    }

    private fun showCalendarScreen(date: LocalDate) {
        selectedDate = date
        selectedNavigation = 1
        val bg = Color.rgb(246, 247, 251)
        val white = Color.WHITE
        val line = Color.rgb(218, 222, 234)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Lịch", 24f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_calendar)
            imageTintList = ColorStateList.valueOf(brand)
            contentDescription = "Chọn ngày"
            isClickable = true; isFocusable = true
            setOnClickListener {
                val current = selectedDate ?: LocalDate.now(zone)
                DatePickerDialog(this@HomeActivity, { _, year, month, day ->
                    showCalendarScreen(LocalDate.of(year, month + 1, day))
                }, current.year, current.monthValue - 1, current.dayOfMonth).show()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        content.addView(header)

        val monthRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(18))
        }
        val monthTitle = label("", 21f, ink, true)
        monthRow.addView(monthTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val previousMonth = TextView(this).apply { text = "‹"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(muted) }
        val nextMonth = TextView(this).apply { text = "›"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(muted) }
        monthRow.addView(previousMonth, LinearLayout.LayoutParams(dp(42), dp(42)))
        monthRow.addView(nextMonth, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(monthRow)

        val weekStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        content.addView(weekStrip, LinearLayout.LayoutParams(-1, dp(96)))
        val dayHeading = label("", 18f, ink, true)
        val taskCount = label("", 13f, muted)
        val dayHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(26), 0, dp(16)) }
        dayHeader.addView(dayHeading, LinearLayout.LayoutParams(0, -2, 1f))
        dayHeader.addView(taskCount)
        content.addView(dayHeader)
        val timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(timeline)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addScreenNavigation(root, 1)

        fun renderCalendar() {
            val selected = selectedDate ?: LocalDate.now(zone)
            val month = selected.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("vi-VN"))
            monthTitle.text = "${month.replaceFirstChar { it.titlecase(Locale.forLanguageTag("vi-VN")) }}, ${selected.year}"
            weekStrip.removeAllViews()
            val start = selected.with(java.time.DayOfWeek.MONDAY)
            val weekdayLabels = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
            repeat(7) { index ->
                val day = start.plusDays(index.toLong())
                val isSelected = day == selected
                val dateTasks = tasks.filter { it.dueAt?.let { due -> Instant.ofEpochMilli(due).atZone(zone).toLocalDate() == day } == true }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = rounded(if (isSelected) brand else white, 15)
                    isClickable = true; isFocusable = true
                    contentDescription = "${day.dayOfMonth} ${day.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("vi-VN"))}"
                    setOnClickListener { selectedDate = day; renderCalendar() }
                }
                cell.addView(label(weekdayLabels[index], 11f, if (isSelected) Color.WHITE else muted).apply { gravity = Gravity.CENTER })
                cell.addView(label(day.dayOfMonth.toString(), 18f, if (isSelected) Color.WHITE else ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) })
                cell.addView(label(if (dateTasks.isNotEmpty()) "•" else " ", 10f, if (isSelected) Color.WHITE else brand).apply { gravity = Gravity.CENTER })
                weekStrip.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(5) })
            }
            dayHeading.text = selected.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM", Locale.forLanguageTag("vi-VN")))
                .replaceFirstChar { it.titlecase(Locale.forLanguageTag("vi-VN")) }
            val dayTasks = tasks.filter { it.dueAt?.let { due -> Instant.ofEpochMilli(due).atZone(zone).toLocalDate() == selected } == true }
                .sortedBy { it.dueAt }
            taskCount.text = "${dayTasks.size} công việc"
            timeline.removeAllViews()
            if (dayTasks.isEmpty()) {
                timeline.addView(label("Không có công việc trong ngày này.", 14f, muted).apply { setPadding(0, dp(20), 0, dp(12)) })
            } else {
                dayTasks.forEach { task ->
                    val time = Instant.ofEpochMilli(requireNotNull(task.dueAt)).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(0, 0, 0, dp(18)) }
                    row.addView(label(time, 12f, brand, true).apply { setPadding(dp(3), dp(14), dp(8), 0) }, LinearLayout.LayoutParams(dp(58), -1))
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; background = rounded(white, 18, line)
                        setPadding(dp(12), dp(12), dp(12), dp(12)); isClickable = true; isFocusable = true
                        setOnClickListener { showTaskDetail(task) }
                    }
                    val check = CheckBox(this).apply {
                        isChecked = task.done; setButtonDrawable(R.drawable.task_checkbox); buttonTintList = null
                        contentDescription = if (task.done) "Bỏ hoàn thành ${task.title}" else "Hoàn thành ${task.title}"
                        setOnClickListener {
                            isEnabled = false
                            repository.toggle(task.id) { result ->
                                if (result.isFailure) toast("Không thể cập nhật công việc.")
                                repository.load { loaded ->
                                    loaded.onSuccess { tasks = it; render(); renderCalendar() }
                                }
                            }
                        }
                    }
                    card.addView(check, LinearLayout.LayoutParams(dp(38), dp(40)))
                    val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(2), 0, 0) }
                    details.addView(label(task.title, 15f, ink, true))
                    details.addView(label("${selected.format(DateTimeFormatter.ofPattern("dd/MM"))} · ${task.category}", 12f, muted).apply { setPadding(0, dp(8), 0, dp(8)) })
                    val (badgeColor, badgeText) = when (task.priority) {
                        3 -> Color.rgb(252, 233, 236) to Color.rgb(190, 66, 82)
                        1 -> Color.rgb(231, 244, 237) to Color.rgb(57, 133, 99)
                        else -> Color.rgb(255, 243, 220) to Color.rgb(157, 111, 35)
                    }
                    details.addView(label(when (task.priority) { 3 -> "Cao"; 1 -> "Thấp"; else -> "Trung bình" }, 11f, badgeText).apply {
                        background = rounded(badgeColor, 7); setPadding(dp(8), dp(4), dp(8), dp(4))
                    }, LinearLayout.LayoutParams(-2, -2))
                    card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
                    timeline.addView(row)
                }
            }
        }
        previousMonth.setOnClickListener { selectedDate = (selectedDate ?: LocalDate.now(zone)).minusMonths(1); renderCalendar() }
        nextMonth.setOnClickListener { selectedDate = (selectedDate ?: LocalDate.now(zone)).plusMonths(1); renderCalendar() }
        calendarRefresh = { renderCalendar() }
        renderCalendar()
        setContentView(root)
    }

    private fun selectCategory() {
        selectedNavigation = 2
        calendarRefresh = null
        repository.categories { result ->
            if (isFinishing || isDestroyed) return@categories
            result.onSuccess { showCategoryScreen(it) }
                .onFailure { toast("Không thể tải danh mục.") }
        }
    }

    private fun showCategoryScreen(names: List<String>) {
        val bg = Color.rgb(246, 247, 251); val white = Color.WHITE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(28)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Danh mục", 24f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "+"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(brand)
            isClickable = true; setOnClickListener { showAddCategoryScreen() }
            contentDescription = "Thêm danh mục"
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        content.addView(header)
        content.addView(label("${names.size} danh mục · ${tasks.size} công việc", 14f, muted).apply {
            setPadding(0, dp(24), 0, dp(26))
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 2; useDefaultMargins = false }
        names.forEachIndexed { index, name ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = rounded(white, 18, Color.rgb(218, 222, 234))
                setPadding(dp(16), dp(16), dp(12), dp(16)); isClickable = true; isFocusable = true
                setOnClickListener { category = name; selectedDate = null; selectedNavigation = 0; setContentView(binding.root); render() }
                setOnLongClickListener { showCategoryActions(name); true }
            }
            val iconRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val icon = TextView(this).apply {
                text = listOf("⌂", "▣", "♙", "▱", "＋")[index % 5]; textSize = 22f; gravity = Gravity.CENTER
                setTextColor(brand); background = rounded(Color.rgb(235, 239, 255), 13)
            }
            iconRow.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42), 1f))
            iconRow.addView(TextView(this).apply {
                text = "⋮"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(muted)
                contentDescription = "Tùy chọn danh mục $name"
                isClickable = true; setOnClickListener { showCategoryActions(name) }
            }, LinearLayout.LayoutParams(dp(30), dp(42)))
            card.addView(iconRow)
            card.addView(label(name, 16f, ink, true).apply { setPadding(0, dp(16), 0, dp(5)) })
            card.addView(label("${tasks.count { it.category == name }} công việc", 13f, muted))
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(142); columnSpec = android.widget.GridLayout.spec(index % 2, 1f)
                setMargins(if (index % 2 == 0) 0 else dp(7), dp(0), if (index % 2 == 0) dp(7) else 0, dp(8))
            }
            grid.addView(card, lp)
        }
        content.addView(grid)
        content.addView(label("Xem nhanh", 18f, ink, true).apply { setPadding(0, dp(20), 0, dp(16)) })
        val quick = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(white, 18); setPadding(dp(16), dp(6), dp(16), dp(6)) }
        listOf("☼" to "Hôm nay", "▦" to "7 ngày tới", "◷" to "Quá hạn", "✓" to "Đã hoàn thành").forEachIndexed { i, (symbol, title) ->
            val f = TaskFilter.entries[i]
            val count = tasks.count { f.includes(it.done, it.dueAt, LocalDate.now(zone), zone) }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, dp(11)); isClickable = true; setOnClickListener { filter = f; category = null; selectedDate = null; showHomeScreen() } }
            row.addView(label(symbol, 20f, if (i == 2) Color.rgb(205, 72, 85) else brand), LinearLayout.LayoutParams(dp(34), -2))
            row.addView(label(title, 14f, ink), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("$count   ›", 14f, muted, true))
            quick.addView(row)
        }
        content.addView(quick)
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addScreenNavigation(root, 2)
        setContentView(root)
    }

    private fun showCategoryActions(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Đổi tên", "Xóa danh mục")) { _, which ->
                if (which == 0) renameCategoryDialog(name) else confirmDeleteCategory(name)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun renameCategoryDialog(oldName: String) {
        val input = EditText(this).apply { setSingleLine(true); setText(oldName); setSelection(text.length); filters = arrayOf(InputFilter.LengthFilter(100)) }
        AlertDialog.Builder(this)
            .setTitle("Đổi tên danh mục")
            .setView(input)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        repository.renameCategory(oldName, input.text.toString()) { result ->
                            if (isFinishing || isDestroyed) return@renameCategory
                            result.fold({
                                dialog.dismiss()
                                repository.categories { categories -> categories.onSuccess { showCategoryScreen(it) } }
                                refreshTasks()
                            }, { error -> input.error = error.message ?: "Không thể đổi tên danh mục" })
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun confirmDeleteCategory(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Xóa danh mục")
            .setMessage("Danh mục và các công việc bên trong sẽ được chuyển vào thùng rác. Bạn có thể tiếp tục dùng các danh mục còn lại.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                repository.deleteCategory(name) { result ->
                    if (isFinishing || isDestroyed) return@deleteCategory
                    result.fold({
                        if (category == name) category = null
                        refreshTasks()
                        repository.categories { categories -> categories.onSuccess { showCategoryScreen(it) } }
                    }, { error -> toast(error.message ?: "Không thể xóa danh mục") })
                }
            }
            .show()
    }

    private fun showAddCategoryScreen() {
        categoryFormOpen = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)); setBackgroundColor(Color.rgb(246, 247, 251)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "×"; textSize = 30f; setTextColor(ink); isClickable = true; setOnClickListener { categoryFormOpen = false; selectCategory() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("Thêm danh mục", 22f, ink, true))
        root.addView(header)
        val icon = TextView(this).apply { text = "＋"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(brand); background = rounded(Color.rgb(235, 239, 255), 16) }
        root.addView(icon, LinearLayout.LayoutParams(dp(80), dp(80)).apply { topMargin = dp(54) })
        root.addView(label("Nhóm các công việc liên quan để dễ theo dõi và sắp xếp.", 16f, muted).apply { setPadding(0, dp(24), 0, dp(34)) })
        root.addView(label("Tên danh mục", 14f, ink, true))
        val input = EditText(this).apply { setSingleLine(true); hint = "Ví dụ: Sức khỏe"; textSize = 16f; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(Color.WHITE, 14, Color.rgb(214, 219, 231)); filters = arrayOf(InputFilter.LengthFilter(100)) }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        root.addView(label("Ví dụ: Sức khỏe, Du lịch, Dự án cá nhân", 12f, muted).apply { setPadding(0, dp(8), 0, dp(30)) })
        val save = Button(this)
        save.apply { text = "Lưu danh mục"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(brand, 14); setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { input.error = "Nhập tên danh mục"; return@setOnClickListener }
            isEnabled = false
            repository.createCategory(name) { result ->
                if (isFinishing || isDestroyed) return@createCategory
                result.onSuccess {
                    categoryFormOpen = false
                    category = null
                    selectedNavigation = 2
                    refreshTasks()
                    repository.categories { cats -> cats.onSuccess { showCategoryScreen(it) } }
                }.onFailure { error ->
                    save.isEnabled = true
                    Toast.makeText(this@HomeActivity, error.message ?: "Không thể lưu danh mục", Toast.LENGTH_LONG).show()
                }
            }
        } }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(58)))
        setContentView(root)
    }

    private fun addScreenNavigation(root: LinearLayout, selected: Int) {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8)) }
        listOf("Trang chủ" to R.drawable.ic_home, "Lịch" to R.drawable.ic_calendar, "Danh mục" to R.drawable.ic_grid, "Cá nhân" to R.drawable.ic_person).forEachIndexed { index, pair ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; setOnClickListener { notificationPageOpen = false; returnFromNotification = null; when(index) { 0 -> showHomeScreen(); 1 -> selectCalendarDate(); 2 -> selectCategory(); 3 -> showPersonalScreen() } } }
            item.addView(ImageView(this).apply { setImageResource(pair.second); imageTintList = ColorStateList.valueOf(if(index == selected) brand else muted) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(label(pair.first, 10f, if(index == selected) brand else muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
            bar.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        root.addView(bar)
    }

    private fun accountMenu() {
        val signedIn = auth.user != null
        val dialog = AlertDialog.Builder(this).setTitle(if (signedIn) auth.user?.email else "Bạn đang dùng chế độ khách")
            .setMessage("Công việc trong không gian offline được lưu trên thiết bị. Đăng nhập không tự tải dữ liệu này lên mạng.")
            .setPositiveButton(if (signedIn) "Đăng xuất" else "Đăng nhập") { _, _ ->
                if (signedIn) { auth.signOut(); render(); toast("Đã đăng xuất. Công việc offline vẫn được giữ.") }
                else startActivity(Intent(this, LoginActivity::class.java))
            }.setNeutralButton("Chia sẻ danh sách") { _, _ -> requireSharingAccount() }
            .setNegativeButton("Đóng", null)
        if (signedIn && auth.user?.emailVerified == false) {
            dialog.setView(Button(this).apply {
                setText(R.string.verify_email_title)
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@HomeActivity, RegisterActivity::class.java)
                        .putExtra(RegisterActivity.EXTRA_VERIFY_ONLY, true))
                }
            })
        }
        dialog.show()
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

    private fun showTaskDetail(task: HomeTask) {
        TaskDetailDialog.show(this, task, repository,
            onEdit = { editTask(task) },
            onDelete = { dialog -> confirmDelete(task) { dialog.dismiss() } },
            onChanged = { refreshTasks() })
    }

    private fun editTask(task: HomeTask?) {
        if (task == null) {
            startActivity(Intent(this, AddTaskActivity::class.java))
            return
        }
        EditTaskDialog.show(this, task, repository,
            onDelete = { dialog -> confirmDelete(task) { dialog.dismiss() } },
            onSaved = { refreshTasks() })
    }
    private fun confirmDelete(task: HomeTask, onDeleted: () -> Unit = {}) {
        DeleteTaskDialog.show(this, task, repository) {
            onDeleted()
            refreshTasks()
        }
    }

    private fun refreshTasks() {
        repository.load { result ->
            if (isFinishing || isDestroyed) return@load
            result.fold({ tasks = it; render(); calendarRefresh?.invoke(); searchRefresh?.invoke() }, { failed = true; toast("Không thể tải lại công việc.") })
        }
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


    private fun openNotificationPage(onBack: () -> Unit) {
        NotificationPreferences.setUnreadNotification(this, false)
        updateNotificationBadge()
        returnFromNotification = onBack
        showNotificationScreen()
    }

    private fun showNotificationScreen() {
        notificationPageOpen = true
        val bg = Color.rgb(246, 247, 251)
        val white = Color.WHITE
        val ink = Color.rgb(27, 31, 59)
        val muted = Color.rgb(119, 125, 139)
        val red = Color.rgb(204, 69, 82)
        val border = Color.rgb(216, 221, 233)

        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        var selectionMode = false
        val selectedReminderIds = mutableSetOf<String>()
        var visibleReminderIds = emptySet<String>()
        var refreshReminder: (() -> Unit)? = null
        var confirmDeleteReminders: ((Set<String>, String) -> Unit)? = null

        fun text(
            value: String,
            size: Float,
            color: Int,
            bold: Boolean = false
        ) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Thanh tiêu đề
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(12), dp(4))
            setOnClickListener { notificationPageOpen = false; returnFromNotification?.invoke() ?: showHomeScreen() }
        }

        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))

        val title = text("Thông báo", 23f, ink, true)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val selectAction = text("Chọn", 14f, brand, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                selectionMode = !selectionMode
                if (!selectionMode) selectedReminderIds.clear()
                refreshReminder?.invoke()
            }
        }
        val deleteAllAction = text("Xóa tất cả", 14f, red, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), 0, dp(10))
            setOnClickListener {
                if (visibleReminderIds.isEmpty()) return@setOnClickListener
                confirmDeleteReminders?.invoke(visibleReminderIds, "Xóa tất cả thông báo đang hiển thị?")
            }
        }
        header.addView(selectAction)
        header.addView(deleteAllAction)

        root.addView(
            header,
            LinearLayout.LayoutParams(-1, dp(72))
        )

        // Khu vực danh sách có thể cuộn
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
        }

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Tiêu đề từng khu vực
        fun addSectionHeader(
            title: String,
            count: Int,
            color: Int,
            rightText: String? = null
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(
                text(title, 18f, color, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            row.addView(
                text(
                    rightText ?: "$count công việc",
                    14f,
                    if (rightText != null) muted else color
                )
            )

            content.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(12)
                }
            )
        }

        fun updateSelectionActions() {
            title.text = if (selectionMode) "${selectedReminderIds.size} đã chọn" else "Nhắc nhở"
            selectAction.text = if (selectionMode) "Hủy" else "Chọn"
            deleteAllAction.visibility = if (selectionMode) View.GONE else View.VISIBLE
        }

        fun toggleReminderSelection(taskId: String) {
            if (!selectedReminderIds.add(taskId)) selectedReminderIds.remove(taskId)
            refreshReminder?.invoke()
        }

        confirmDeleteReminders = { ids, message ->
            if (ids.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setMessage(message)
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Xóa") { _, _ ->
                        NotificationPreferences.dismissReminders(this, ids)
                        ids.forEach { NotificationManagerCompat.from(this).cancel(it.hashCode()) }
                        repository.removeReminders(ids) { result ->
                            result.onFailure { toast("Không thể hủy một số lịch nhắc.") }
                        }
                        selectedReminderIds.removeAll(ids)
                        selectionMode = false
                        refreshReminder?.invoke()
                    }
                    .show()
            }
        }

        // Thẻ công việc
        fun addReminderCard(task: HomeTask, overdue: Boolean) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(12), dp(13), dp(14), dp(13))
                background = rounded(white, 20, border)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectionMode) toggleReminderSelection(task.id) else showTaskDetail(task)
                }
                setOnLongClickListener {
                    selectionMode = true
                    selectedReminderIds.add(task.id)
                    refreshReminder?.invoke()
                    true
                }
                if (task.id in selectedReminderIds) background = rounded(Color.rgb(235, 239, 255), 20, brand)
            }

            // Nút hoàn thành hình tròn
            val check = CheckBox(this).apply {
                isChecked = if (selectionMode) task.id in selectedReminderIds else task.done
                if (!selectionMode) setButtonDrawable(R.drawable.task_checkbox)
                else buttonTintList = ColorStateList.valueOf(brand)
                if (!selectionMode) buttonTintList = null
                contentDescription = if (selectionMode) {
                    "Chọn thông báo ${task.title}"
                } else if (task.done) {
                    "Bỏ hoàn thành ${task.title}"
                } else {
                    "Hoàn thành ${task.title}"
                }

                setOnClickListener {
                    if (selectionMode) {
                        toggleReminderSelection(task.id)
                        return@setOnClickListener
                    }
                    isEnabled = false

                    repository.toggle(task.id) { result ->
                        if (isFinishing || isDestroyed) return@toggle

                        if (result.isFailure) {
                            toast("Không thể cập nhật công việc.")
                        }

                        repository.load { loadResult ->
                            if (isFinishing || isDestroyed) return@load

                            loadResult.fold(
                                { tasks = it },
                                { toast("Không thể tải lại công việc.") }
                            )

                            render()
                            refreshReminder?.invoke()
                        }
                    }
                }
            }

            card.addView(
                check,
                LinearLayout.LayoutParams(dp(44), dp(40))
            )

            // Nội dung công việc
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(0), dp(2), 0, 0)
            }

            details.addView(
                text(task.title, 15f, ink, true)
            )

            details.addView(
                text(taskMeta(task), 13f, if (overdue) red else muted).apply {
                    setPadding(0, dp(10), 0, dp(9))
                }
            )

            // Nhãn ưu tiên
            val priorityColor: Int
            val priorityBackground: Int
            val priorityText: String

            when (task.priority) {
                3 -> {
                    priorityText = "Cao"
                    priorityColor = Color.rgb(190, 66, 82)
                    priorityBackground = Color.rgb(252, 233, 236)
                }
                1 -> {
                    priorityText = "Thấp"
                    priorityColor = Color.rgb(57, 133, 99)
                    priorityBackground = Color.rgb(231, 244, 237)
                }
                else -> {
                    priorityText = "Trung bình"
                    priorityColor = Color.rgb(157, 111, 35)
                    priorityBackground = Color.rgb(255, 243, 220)
                }
            }

            val badges = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            badges.addView(
                text(priorityText, 11f, priorityColor).apply {
                    background = rounded(priorityBackground, 7)
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                }
            )

            if (overdue) {
                badges.addView(
                    text("Quá hạn", 11f, red).apply {
                        setPadding(dp(8), dp(3), 0, dp(3))
                    }
                )
            }

            details.addView(
                badges,
                LinearLayout.LayoutParams(-2, -2)
            )

            card.addView(
                details,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            content.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(12)
                }
            )
        }

        // Tạo lại nội dung khi trạng thái công việc thay đổi
        fun buildReminderContent() {
            content.removeAllViews()

            val dismissed = NotificationPreferences.dismissedReminders(this)
            val overdueTasks = tasks
                .filter {
                    !it.done && it.dueAt != null && it.dueAt < now && it.id !in dismissed
                }
                .sortedBy { it.dueAt }

            val upcomingTasks = tasks
                .filter {
                    !it.done && it.id !in dismissed &&
                            it.dueAt != null &&
                            it.dueAt >= now &&
                            Instant.ofEpochMilli(it.dueAt)
                                .atZone(zone)
                                .toLocalDate() == today
                }
                .sortedBy { it.dueAt }

            visibleReminderIds = (overdueTasks + upcomingTasks).map { it.id }.toSet()
            selectedReminderIds.retainAll(visibleReminderIds)
            updateSelectionActions()

            if (selectionMode) {
                val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14)) }
                actions.addView(text("Chọn mục cần xóa", 13f, muted), LinearLayout.LayoutParams(0, -2, 1f))
                val deleteSelected = text("Xóa đã chọn", 14f, red, true).apply {
                    isEnabled = selectedReminderIds.isNotEmpty()
                    alpha = if (isEnabled) 1f else 0.45f
                    setOnClickListener {
                        val ids = selectedReminderIds.toSet()
                        if (ids.isNotEmpty()) confirmDeleteReminders?.invoke(ids, "Xóa ${ids.size} thông báo đã chọn?")
                    }
                }
                actions.addView(deleteSelected)
                content.addView(actions)
            }

            // Quá hạn
            addSectionHeader(
                "Quá hạn",
                overdueTasks.size,
                red
            )

            if (overdueTasks.isEmpty()) {
                content.addView(
                    text("Không có công việc quá hạn", 13f, muted).apply {
                        setPadding(0, dp(8), 0, dp(22))
                    }
                )
            } else {
                overdueTasks.forEach {
                    addReminderCard(it, true)
                }
            }

            // Sắp đến hạn
            val upcomingHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            upcomingHeader.addView(
                text("Sắp đến hạn", 18f, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            upcomingHeader.addView(
                text("Hôm nay · ${upcomingTasks.size}", 14f, muted)
            )

            content.addView(
                upcomingHeader,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(14)
                }
            )

            if (upcomingTasks.isEmpty()) {
                content.addView(
                    text("Không có công việc sắp đến hạn hôm nay", 13f, muted)
                        .apply {
                            setPadding(0, dp(8), 0, dp(20))
                        }
                )
            } else {
                upcomingTasks.forEach {
                    addReminderCard(it, false)
                }
            }
        }

        // Gán hàm cập nhật danh sách
        refreshReminder = {
            buildReminderContent()
        }

        buildReminderContent()

        addScreenNavigation(root, selectedNavigation)
        setContentView(root)
    }

    private fun showProfileEditScreen() {
        profileEditorOpen = true
        val bg = Color.rgb(246, 247, 251)
        val white = Color.WHITE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(34), dp(20), dp(12)) }
        header.addView(TextView(this).apply {
            text = "×"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(ink)
            isClickable = true; setOnClickListener { profileEditorOpen = false; showPersonalScreen() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("Chỉnh sửa hồ sơ", 22f, ink, true).apply { setPadding(dp(8), 0, 0, 0) })
        root.addView(header)

        val user = auth.user
        val currentName = user?.displayName?.takeIf { it.isNotBlank() } ?: GuestProfilePreferences.name(this) ?: "Khách"
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(26), dp(38), dp(26), dp(20)) }
        val avatarFrame = FrameLayout(this)
        val avatarInitials = label(
            currentName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.takeLast(2).joinToString("") { it.first().uppercase() }.take(2),
            28f, Color.WHITE, true
        ).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(brand) } }
        avatarFrame.addView(avatarInitials, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        val avatarImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            clipToOutline = true
            GuestProfilePreferences.avatar(this@HomeActivity)?.let { saved ->
                runCatching { setImageURI(android.net.Uri.parse(saved)) }
            }
        }
        profileAvatarImage = avatarImage
        avatarFrame.addView(avatarImage, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        val camera = TextView(this).apply {
            text = "📷"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(brand)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(1), Color.rgb(218, 222, 234)) }
            contentDescription = "Đổi ảnh đại diện"
            isClickable = true; isFocusable = true
            setOnClickListener { profileAvatarPicker.launch(arrayOf("image/*")) }
        }
        avatarFrame.addView(camera, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(4); bottomMargin = dp(4) })
        body.addView(avatarFrame, LinearLayout.LayoutParams(-1, dp(128)))
        body.addView(label("Đổi ảnh đại diện", 14f, brand, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(32)); isClickable = true
            setOnClickListener { profileAvatarPicker.launch(arrayOf("image/*")) }
        })

        body.addView(label("Họ và tên", 14f, ink, true))
        val nameInput = EditText(this).apply {
            setSingleLine(true); setText(currentName); textSize = 16f; setTextColor(ink)
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(white, 14, Color.rgb(214, 219, 231))
            filters = arrayOf(InputFilter.LengthFilter(100))
        }
        body.addView(nameInput, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8); bottomMargin = dp(24) })

        body.addView(label("Email", 14f, ink, true))
        val emailRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rounded(white, 14, Color.rgb(214, 219, 231)); setPadding(dp(14), 0, dp(14), 0)
        }
        val emailInput = EditText(this).apply {
            setSingleLine(true); setText(user?.email ?: "Chưa đăng nhập"); textSize = 16f; setTextColor(ink)
            isFocusable = false; isClickable = false; isCursorVisible = false; background = null; setPadding(0, dp(12), 0, dp(12))
        }
        emailRow.addView(emailInput, LinearLayout.LayoutParams(0, dp(56), 1f))
        emailRow.addView(label("✉", 20f, muted))
        body.addView(emailRow, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        body.addView(label("Email dùng để đăng nhập và khôi phục tài khoản.", 12f, muted).apply { setPadding(0, dp(10), 0, 0) })

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val save = Button(this).apply {
            text = "Lưu thay đổi"; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(brand, 14)
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) { nameInput.error = "Nhập họ và tên"; return@setOnClickListener }
                isEnabled = false
                if (user == null) {
                    GuestProfilePreferences.saveName(this@HomeActivity, name)
                    profileEditorOpen = false
                    render()
                    showPersonalScreen()
                    toast("Đã lưu hồ sơ trên thiết bị.")
                } else {
                    auth.updateDisplayName(name) { result ->
                        if (isFinishing || isDestroyed) return@updateDisplayName
                        result.fold({
                            profileEditorOpen = false
                            render()
                            showPersonalScreen()
                            toast("Đã cập nhật hồ sơ.")
                        }, { error ->
                            isEnabled = true
                            Toast.makeText(this@HomeActivity, error.message ?: "Không thể cập nhật hồ sơ.", Toast.LENGTH_LONG).show()
                        })
                    }
                }
            }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(dp(26), dp(8), dp(26), dp(20)) })
        setContentView(root)
    }

    private fun showTrashScreen() {
        trashPageOpen = true
        val bg = Color.rgb(246, 247, 251)
        val danger = Color.rgb(194, 65, 79)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val back = TextView(this).apply {
            text = "‹"; textSize = 36f; setTextColor(ink); gravity = Gravity.CENTER
            setOnClickListener { trashPageOpen = false; showPersonalScreen() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        val heading = TextView(this).apply { text = "Thùng rác"; textSize = 22f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD) }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = TextView(this).apply {
            text = "Dọn sạch"; textSize = 14f; setTextColor(danger); gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), 0, dp(10))
        }
        header.addView(clear)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(64)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        fun refresh() {
            if (!trashPageOpen || isFinishing || isDestroyed) return
            repository.trash { result ->
                if (!trashPageOpen || isFinishing || isDestroyed) return@trash
                content.removeAllViews()
                result.fold({ deleted ->
                    clear.isEnabled = deleted.isNotEmpty()
                    clear.alpha = if (deleted.isEmpty()) 0.45f else 1f
                    if (deleted.isEmpty()) {
                        content.addView(TextView(this).apply {
                            text = "Thùng rác đang trống"; textSize = 15f; setTextColor(muted); gravity = Gravity.CENTER
                            setPadding(dp(12), dp(72), dp(12), dp(24))
                        }, LinearLayout.LayoutParams(-1, -2))
                    } else {
                        content.addView(TextView(this).apply {
                            text = "Task đã xóa vẫn được giữ trên thiết bị cho đến khi bạn khôi phục hoặc xóa vĩnh viễn."
                            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(14))
                        })
                        deleted.forEach { item ->
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(12))
                                background = rounded(Color.WHITE, 17, Color.rgb(218, 222, 234))
                            }
                            card.addView(TextView(this).apply {
                                text = item.title; textSize = 16f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD)
                            })
                            card.addView(TextView(this).apply {
                                val deletedDate = java.time.Instant.ofEpochMilli(item.deletedAt).atZone(zone)
                                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                                text = "${item.category} · Đã xóa $deletedDate"; textSize = 12f; setTextColor(muted)
                                setPadding(0, dp(5), 0, dp(11))
                            })
                            val actions = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                            val restore = TextView(this).apply {
                                text = "Khôi phục"; textSize = 13f; setTextColor(brand); gravity = Gravity.CENTER
                                setPadding(dp(12), dp(8), dp(12), dp(8)); isClickable = true; isFocusable = true
                                setOnClickListener {
                                    repository.restoreTask(item.id) { restored ->
                                        restored.onSuccess {
                                            trashPageOpen = false
                                            selectedNavigation = 0
                                            selectedDate = null
                                            category = item.category
                                            Toast.makeText(this@HomeActivity, "Đã khôi phục task.", Toast.LENGTH_SHORT).show()
                                            reload()
                                        }
                                            .onFailure { Toast.makeText(this@HomeActivity, it.message ?: "Không thể khôi phục task.", Toast.LENGTH_LONG).show() }
                                    }
                                }
                            }
                            val permanentlyDelete = TextView(this).apply {
                                text = "Xóa vĩnh viễn"; textSize = 13f; setTextColor(danger); gravity = Gravity.CENTER
                                setPadding(dp(12), dp(8), dp(4), dp(8)); isClickable = true; isFocusable = true
                                setOnClickListener {
                                    AlertDialog.Builder(this@HomeActivity).setTitle("Xóa vĩnh viễn task?")
                                        .setMessage("Task sẽ bị xóa khỏi thiết bị. Dấu xóa được giữ trong hàng đợi đồng bộ để gửi lên máy chủ khi đồng bộ được triển khai.")
                                        .setNegativeButton("Hủy", null)
                                        .setPositiveButton("Xóa vĩnh viễn") { _, _ ->
                                            repository.permanentlyDeleteTask(item.id) { removed ->
                                                removed.onSuccess { Toast.makeText(this@HomeActivity, "Đã xóa vĩnh viễn task.", Toast.LENGTH_SHORT).show(); refresh() }
                                                    .onFailure { Toast.makeText(this@HomeActivity, it.message ?: "Không thể xóa task.", Toast.LENGTH_LONG).show() }
                                            }
                                        }.show()
                                }
                            }
                            actions.addView(restore)
                            actions.addView(permanentlyDelete)
                            card.addView(actions, LinearLayout.LayoutParams(-1, -2))
                            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                        }
                    }
                }, { error ->
                    clear.isEnabled = false
                    content.addView(TextView(this).apply {
                        text = error.message ?: "Không thể mở thùng rác."; textSize = 14f; setTextColor(danger)
                    })
                })
            }
        }
        clear.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Dọn sạch thùng rác?")
                .setMessage("Tất cả task trong thùng rác sẽ bị xóa vĩnh viễn trên thiết bị.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Dọn sạch") { _, _ ->
                    repository.permanentlyDeleteAll { result ->
                        result.onSuccess { refresh() }
                            .onFailure { Toast.makeText(this, it.message ?: "Không thể dọn thùng rác.", Toast.LENGTH_LONG).show() }
                    }
                }.show()
        }
        refresh()
    }

    private fun showPersonalScreen() {
        selectedNavigation = 3
        calendarRefresh = null
        val bg = Color.rgb(246, 247, 251)
        val white = Color.WHITE
        val blue = Color.rgb(80, 100, 238)
        val ink = Color.rgb(27, 31, 59)
        val muted = Color.rgb(119, 125, 139)
        val green = Color.rgb(54, 139, 101)
        val red = Color.rgb(195, 76, 88)
        val line = Color.rgb(240, 242, 247)

        fun text(
            value: String,
            size: Float,
            color: Int,
            bold: Boolean = false
        ) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun card(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(white, 20)
                setPadding(dp(18), dp(10), dp(6), dp(10))
            }
        }

        fun divider(): View {
            return View(this).apply {
                setBackgroundColor(line)
            }
        }

        fun settingRow(
            icon: String,
            title: String,
            subtitle: String? = null,
            isSwitch: Boolean = false,
            checked: Boolean = true,
            danger: Boolean = false,
            onSwitchChanged: ((Boolean) -> Unit)? = null
        ): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(0), dp(12), dp(12), dp(12))
                minimumHeight = dp(64)
            }

            val iconBox = TextView(this).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(if (danger) red else blue)
                background = rounded(
                    if (danger) Color.rgb(253, 237, 239)
                    else Color.rgb(235, 239, 255),
                    12
                )
            }

            row.addView(
                iconBox,
                LinearLayout.LayoutParams(dp(40), dp(40))
            )

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(6), 0)
            }

            content.addView(
                text(title, 14f, if (danger) red else ink)
            )

            if (subtitle != null) {
                content.addView(
                    text(subtitle, 11f, muted).apply {
                        setPadding(0, dp(3), 0, 0)
                    }
                )
            }

            row.addView(
                content,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            if (isSwitch) {
                val switch = Switch(this).apply {
                    isChecked = checked
                    thumbTintList = ColorStateList.valueOf(white)
                    trackTintList = ColorStateList.valueOf(
                        if (checked) blue else Color.LTGRAY
                    )

                    setOnCheckedChangeListener { _, isChecked ->
                        trackTintList = ColorStateList.valueOf(
                            if (isChecked) blue else Color.LTGRAY
                        )
                        onSwitchChanged?.invoke(isChecked)
                    }
                }

                row.addView(switch)
            } else {
                row.addView(
                    text("›", 28f, Color.rgb(153, 160, 180)).apply {
                        gravity = Gravity.CENTER
                    }
                )
            }

            return row
        }

        val user = auth.user
        val profileName = user?.displayName?.takeIf { it.isNotBlank() } ?: GuestProfilePreferences.name(this) ?: "Khách"
        val completed = tasks.count { it.done }
        val doing = tasks.count { !it.done && it.dueAt?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate() < LocalDate.now(zone)
        } != true }
        val overdue = tasks.count {
            !it.done && it.dueAt != null &&
                    Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() < LocalDate.now(zone)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Nội dung có thể cuộn
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(14))
        }

        // Tiêu đề
        content.addView(
            text("Cá nhân", 24f, ink, true).apply {
                setPadding(0, dp(18), 0, dp(42))
            }
        )

        // Avatar
        val avatar = TextView(this).apply {
            val name = profileName
            text = name.trim().split(" ")
                .filter { it.isNotEmpty() }
                .takeLast(2)
                .joinToString("") { it.first().uppercase() }
                .take(2)

            textSize = 28f
            setTextColor(white)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(blue, 100)
        }

        content.addView(
            avatar,
            LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER
            }
        )

        content.addView(
            text(profileName, 24f, ink, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(4))
            }
        )

        content.addView(
            text(user?.email ?: "", 14f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(28))
            }
        )

        // Thống kê công việc
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(white, 20)
            setPadding(dp(8), dp(20), dp(8), dp(18))
        }

        fun addStat(number: Int, title: String, color: Int) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            item.addView(
                text(number.toString(), 27f, color, true).apply {
                    gravity = Gravity.CENTER
                }
            )

            item.addView(
                text(title, 12f, muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(7), 0, 0)
                }
            )

            stats.addView(
                item,
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

        addStat(completed, "Hoàn thành", green)
        addStat(doing, "Đang làm", blue)
        addStat(overdue, "Quá hạn", red)

        content.addView(
            stats,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(28)
            }
        )

        // Chỉnh sửa hồ sơ và nhắc nhở
        val accountCard = card()

        accountCard.addView(
            settingRow("♙", "Chỉnh sửa hồ sơ").apply {
                isClickable = true; isFocusable = true
                setOnClickListener { showProfileEditScreen() }
            }
        )

        accountCard.addView(
            divider(),
            LinearLayout.LayoutParams(-1, dp(1))
        )

        accountCard.addView(
            settingRow("♧", "Nhắc nhở của tôi").apply {
                isClickable = true; isFocusable = true
                setOnClickListener { openNotificationPage { showPersonalScreen() } }
            }
        )

        accountCard.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        accountCard.addView(
            settingRow("▤", "Thùng rác").apply {
                isClickable = true; isFocusable = true
                setOnClickListener { showTrashScreen() }
            }
        )

        content.addView(
            accountCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(20)
            }
        )

        // Cài đặt
        val settingsCard = card()

        settingsCard.addView(
            settingRow(
                "♧",
                "Bật thông báo",
                isSwitch = true,
                checked = NotificationPreferences.isEnabled(this),
                onSwitchChanged = { enabled ->

                    NotificationPreferences.setEnabled(this, enabled)

                    GuestTaskRepository.get(this).setNotificationsEnabled(enabled) { result ->

                        result.onFailure { error ->
                            Toast.makeText(
                                this,
                                "Không thể cập nhật lịch nhắc: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    }
                }
            )
        )

        settingsCard.addView(
            divider(),
            LinearLayout.LayoutParams(-1, dp(1))
        )

        settingsCard.addView(
            settingRow(
                "♡",
                "Đồng bộ đa thiết bị",
                subtitle = "Chưa triển khai. Dấu xóa được giữ trong hàng đợi trên thiết bị."
            )
        )

        content.addView(
            settingsCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(20)
            }
        )

        // Đăng xuất
        val logoutCard = card()

        logoutCard.addView(
            settingRow(
                "⇥",
                if (user != null) "Đăng xuất" else "Đăng nhập",
                danger = true
            ).apply {
                setOnClickListener {
                    if (user != null) {
                        // Hiện hộp thoại xác nhận
                        AlertDialog.Builder(this@HomeActivity)
                            .setTitle("Đăng xuất")
                            .setMessage("Bạn có chắc chắn muốn đăng xuất khỏi tài khoản không?")
                            .setNegativeButton("Hủy", null)
                            .setPositiveButton("Đăng xuất") { _, _ ->

                                // Đăng xuất tài khoản
                                auth.signOut()

                                // Chuyển về màn hình đăng nhập
                                val intent = Intent(
                                    this@HomeActivity,
                                    LoginActivity::class.java
                                ).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                }

                                startActivity(intent)
                                finish()
                            }
                            .show()

                    } else {
                        // Nếu chưa đăng nhập thì chuyển đến màn hình đăng nhập
                        startActivity(
                            Intent(
                                this@HomeActivity,
                                LoginActivity::class.java
                            )
                        )
                    }
                }
            }
        )

        // Thêm thẻ đăng xuất vào giao diện
        content.addView(
            logoutCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
        )

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        addScreenNavigation(root, 3)
        setContentView(root)
    }

}
