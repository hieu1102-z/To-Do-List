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
import com.finalexam.to_do_list.util.TaskFilter
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
import com.finalexam.to_do_list.notification.NotificationHelper
import android.widget.Toast
import com.finalexam.to_do_list.notification.NotificationPreferences

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

            // Đánh dấu thông báo đã đọc
            NotificationPreferences.setUnreadNotification(this, false)

            // Ẩn chấm đỏ
            updateNotificationBadge()

            // Mở trang nhắc nhở
            showReminderScreen()
        }
        binding.empty.setOnClickListener { if (failed) reload() }
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
        val date = selectedDate ?: LocalDate.now(zone)
        DatePickerDialog(this, { _, year, month, day ->
            selectedDate = LocalDate.of(year, month + 1, day); selectedNavigation = 1; render()
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun selectCategory() {
        val categories = arrayOf("Tất cả danh mục", "to do", "Cá nhân", "Học tập", "Công việc", "Mua sắm")
        AlertDialog.Builder(this).setTitle("Danh mục trên thiết bị")
            .setSingleChoiceItems(categories, categories.indexOf(category ?: categories[0])) { dialog, which ->
                category = if (which == 0) null else categories[which]; selectedNavigation = 2; render(); dialog.dismiss()
            }.setNeutralButton("Chia sẻ danh sách") { _, _ -> requireSharingAccount() }
            .setNegativeButton("Đóng", null).show()
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
            onChanged = { reload() })
    }

    private fun editTask(task: HomeTask?) {
        if (task == null) {
            startActivity(Intent(this, AddTaskActivity::class.java))
            return
        }
        EditTaskDialog.show(this, task, repository,
            onDelete = { dialog -> confirmDelete(task) { dialog.dismiss() } },
            onSaved = { reload() })
    }
    private fun confirmDelete(task: HomeTask, onDeleted: () -> Unit = {}) {
        DeleteTaskDialog.show(this, task, repository) {
            onDeleted()
            reload()
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


    private fun showReminderScreen() {
        val bg = Color.rgb(246, 247, 251)
        val white = Color.WHITE
        val ink = Color.rgb(27, 31, 59)
        val muted = Color.rgb(119, 125, 139)
        val red = Color.rgb(204, 69, 82)
        val border = Color.rgb(216, 221, 233)

        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()

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

        val dialog = AlertDialog.Builder(this).create()

        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(12), dp(4))
            setOnClickListener { dialog.dismiss() }
        }

        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))

        header.addView(
            text("Nhắc nhở", 23f, ink, true),
            LinearLayout.LayoutParams(-1, -2)
        )

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

        var refreshReminder: (() -> Unit)? = null

        // Thẻ công việc
        fun addReminderCard(task: HomeTask, overdue: Boolean) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(12), dp(13), dp(14), dp(13))
                background = rounded(white, 20, border)
                isClickable = true
                isFocusable = true
                setOnClickListener { showTaskDetail(task) }
            }

            // Nút hoàn thành hình tròn
            val check = CheckBox(this).apply {
                isChecked = task.done
                setButtonDrawable(R.drawable.task_checkbox)
                buttonTintList = null
                contentDescription = if (task.done) {
                    "Bỏ hoàn thành ${task.title}"
                } else {
                    "Hoàn thành ${task.title}"
                }

                setOnClickListener {
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

            val overdueTasks = tasks
                .filter {
                    !it.done && it.dueAt != null && it.dueAt < now
                }
                .sortedBy { it.dueAt }

            val upcomingTasks = tasks
                .filter {
                    !it.done &&
                            it.dueAt != null &&
                            it.dueAt >= now &&
                            Instant.ofEpochMilli(it.dueAt)
                                .atZone(zone)
                                .toLocalDate() == today
                }
                .sortedBy { it.dueAt }

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

        dialog.setView(root)
        dialog.show()

        dialog.window?.apply {
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            setStatusBarColor(bg)
            setNavigationBarColor(white)

            decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                        android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun showPersonalScreen() {
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
            val name = user?.displayName?.takeIf { it.isNotBlank() } ?: "Người dùng"
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
            text(user?.displayName ?: "Khách", 24f, ink, true).apply {
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
            settingRow("♙", "Chỉnh sửa hồ sơ")
        )

        accountCard.addView(
            divider(),
            LinearLayout.LayoutParams(-1, dp(1))
        )

        accountCard.addView(
            settingRow("♧", "Nhắc nhở của tôi")
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
                subtitle = "Đã đồng bộ và vừa xong",
                isSwitch = true
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

        // Thanh điều hướng dưới cùng
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(white)
            setPadding(0, dp(8), 0, dp(8))
        }

        // Khởi tạo dialog trước để các sự kiện điều hướng có thể sử dụng
        val dialog = AlertDialog.Builder(this).create()

        val navItems = listOf(
            "Trang chủ" to R.drawable.ic_home,
            "Lịch" to R.drawable.ic_calendar,
            "Danh mục" to R.drawable.ic_grid,
            "Cá nhân" to R.drawable.ic_person
        )

        navItems.forEachIndexed { index, item ->
            val selected = index == 3

            val navItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            val icon = ImageView(this).apply {
                setImageResource(item.second)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (selected) blue else muted
                )
            }

            navItem.addView(
                icon,
                LinearLayout.LayoutParams(dp(25), dp(25))
            )

            navItem.addView(
                text(
                    item.first,
                    11f,
                    if (selected) blue else muted
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(5), 0, 0)
                }
            )

            navItem.setOnClickListener {
                when (index) {
                    0 -> {
                        dialog.dismiss()
                        selectedNavigation = 0
                        category = null
                        selectedDate = null
                        render()
                    }
                    1 -> {
                        dialog.dismiss()
                        selectCalendarDate()
                    }
                    2 -> {
                        dialog.dismiss()
                        selectCategory()
                    }
                    3 -> {
                        // Đang ở mục Cá nhân
                    }
                }
            }

            navigation.addView(
                navItem,
                LinearLayout.LayoutParams(0, dp(56), 1f)
            )
        }

        root.addView(
            navigation,
            LinearLayout.LayoutParams(-1, dp(72))
        )

        dialog.setView(root)
        dialog.show()

        dialog.window?.apply {
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

}
