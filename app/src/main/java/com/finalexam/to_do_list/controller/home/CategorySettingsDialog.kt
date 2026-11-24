package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.databinding.DialogCategorySettingsBinding
import com.finalexam.to_do_list.model.repository.AccountSession
import com.finalexam.to_do_list.model.repository.UnifiedTaskRepository
import com.finalexam.to_do_list.model.repository.WorkspaceCategory

internal class CategorySettingsDialog(
    private val activity: ComponentActivity,
    private val repository: UnifiedTaskRepository,
    private val dialogs: WorkspaceDialogs,
    private val sharing: SharingDialogs
) {
    fun show(category: WorkspaceCategory, changed: () -> Unit, deleted: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val session = AccountSession.current(activity)?.takeIf { it.uid == category.ref.workspace.uid } ?: return
        val binding = DialogCategorySettingsBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).create()
        dialog.setView(binding.root, 0, 0, 0, 0)

        val canEdit = category.role == "OWNER"
        var iconKey = category.icon
        var colorHex = category.color
        var busy = false

        fun isCurrent() = dialog.isShowing && !activity.isFinishing && !activity.isDestroyed &&
            AccountSession.current(activity) === session

        fun showError(message: String?) {
            binding.error.text = message
            binding.error.isVisible = message != null
        }

        fun updateActions() {
            val dirty = binding.nameInput.text.toString().trim() != category.name ||
                iconKey != category.icon || colorHex != category.color
            binding.save.isVisible = canEdit && dirty
            binding.save.isEnabled = !busy
            binding.close.isEnabled = !busy
            binding.delete.isEnabled = !busy
            binding.rename.isEnabled = !busy
            binding.nameInput.isEnabled = !busy
            binding.iconChoice.isEnabled = !busy
            binding.palette.alpha = if (busy) 0.65f else 1f
            dialog.setCancelable(!busy)
        }

        fun renderAppearance() {
            val icon = CategoryIcon.fromKey(iconKey)
            val iconName = activity.getString(icon.label)
            val iconImage = ContextCompat.getDrawable(activity, icon.drawable)?.mutate()?.apply {
                setTint(categoryAccent(colorHex))
            }
            val arrow = if (canEdit) ContextCompat.getDrawable(activity, R.drawable.ic_dropdown) else null
            binding.iconChoice.setCompoundDrawablesRelativeWithIntrinsicBounds(iconImage, null, arrow, null)
            binding.iconChoice.text = iconName
            binding.iconChoice.contentDescription = if (canEdit) activity.getString(R.string.category_settings_pick_icon, iconName) else null
            binding.selectedColor.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(categoryAccent(colorHex))
                setStroke(dp(1), ContextCompat.getColor(activity, R.color.muted))
            }
            val shownHex = colorHex ?: CategoryColor.BLUE.hex
            val color = CategoryColor.entries.firstOrNull { it.hex.equals(shownHex, ignoreCase = true) }
            binding.selectedColor.contentDescription = activity.getString(R.string.category_settings_color_selected,
                color?.let { activity.getString(it.label) } ?: shownHex)
            updateActions()
        }

        binding.heading.text = category.name
        ViewCompat.setAccessibilityHeading(binding.heading, true)
        binding.nameInput.setText(category.name)
        binding.nameInput.doAfterTextChanged { updateActions() }
        binding.rename.isVisible = canEdit
        binding.delete.isVisible = canEdit
        binding.palette.isVisible = canEdit
        if (canEdit) {
            binding.palette.addView(categoryColorPalette(activity, colorHex) { color ->
                if (busy || !isCurrent()) return@categoryColorPalette false
                colorHex = color.hex
                renderAppearance()
                true
            })
            binding.iconChoice.isFocusable = true
            binding.iconChoice.setOnClickListener {
                val options = CategoryIcon.entries
                val popup = PopupMenu(activity, binding.iconChoice)
                if (Build.VERSION.SDK_INT >= 29) popup.setForceShowIcon(true)
                val menuIconColor = ContextCompat.getColor(activity, R.color.ink)
                options.forEachIndexed { index, icon ->
                    val drawable = ContextCompat.getDrawable(activity, icon.drawable)?.mutate()?.apply {
                        setTint(menuIconColor)
                    }
                    popup.menu.add(0, index, index, icon.label).icon = drawable
                }
                popup.menu.setGroupCheckable(0, true, true)
                popup.menu.getItem(CategoryIcon.fromKey(iconKey).ordinal).isChecked = true
                popup.setOnMenuItemClickListener { item ->
                    iconKey = options[item.itemId].takeUnless { it == CategoryIcon.PLUS }?.key
                    renderAppearance()
                    true
                }
                popup.show()
            }
        }

        binding.rename.setOnClickListener {
            binding.heading.isVisible = false
            binding.rename.isVisible = false
            binding.nameInput.isVisible = true
            binding.nameInput.requestFocus()
            binding.nameInput.setSelection(binding.nameInput.length())
            dialog.window?.let { WindowCompat.getInsetsController(it, binding.nameInput).show(WindowInsetsCompat.Type.ime()) }
        }
        binding.nameInput.setOnEditorActionListener { _, action, _ ->
            action == EditorInfo.IME_ACTION_DONE && binding.save.isVisible && binding.save.isEnabled && binding.save.performClick()
        }
        binding.save.setOnClickListener {
            val title = binding.nameInput.text.toString().trim()
            if (title.isBlank()) {
                showError(activity.getString(R.string.category_settings_name_required))
                return@setOnClickListener
            }
            busy = true
            showError(null)
            updateActions()
            repository.updateCategory(category, title, colorHex, iconKey) { result ->
                if (!isCurrent()) return@updateCategory
                busy = false
                result.onSuccess {
                    dialog.dismiss()
                    changed()
                }.onFailure { error ->
                    showError(error.message ?: activity.getString(R.string.category_settings_save_error))
                    updateActions()
                }
            }
        }
        binding.delete.setOnClickListener {
            dialogs.confirm(activity.getString(R.string.category_settings_delete_title),
                activity.getString(R.string.category_settings_delete_body, category.name),
                WorkspaceDialogs.Action(activity.getString(R.string.category_settings_delete), WorkspaceDialogs.Tone.DESTRUCTIVE) {
                    if (!isCurrent()) return@Action
                    busy = true
                    showError(null)
                    updateActions()
                    repository.deleteCategory(category) { result ->
                        if (!isCurrent()) return@deleteCategory
                        busy = false
                        result.onSuccess {
                            dialog.dismiss()
                            deleted()
                        }.onFailure { error ->
                            showError(error.message ?: activity.getString(R.string.category_settings_delete_error))
                            updateActions()
                        }
                    }
                })
        }
        binding.close.setOnClickListener { dialog.dismiss() }
        renderAppearance()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            val width = (activity.resources.displayMetrics.widthPixels - dp(40)).coerceIn(1, dp(440))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.show()
        sharing.bindMembers(category, binding.memberSection, binding.share, ::isCurrent, changed, dialog::dismiss)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
