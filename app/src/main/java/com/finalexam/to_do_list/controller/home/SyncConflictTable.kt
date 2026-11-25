package com.finalexam.to_do_list.controller.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.finalexam.to_do_list.R
import kotlin.math.roundToInt

internal fun syncConflictTable(
    context: Context,
    fields: List<SyncConflictField>,
    selectable: Set<String> = emptySet(),
    onSelectionChanged: (Set<String>) -> Unit = {}
): View? {
    if (fields.isEmpty()) return null

    val ink = ContextCompat.getColor(context, R.color.ink)
    val surface = ContextCompat.getColor(context, R.color.surface)
    val brand = ContextCompat.getColor(context, R.color.brand)
    val highlight = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(brand, 28), surface)
    val current = context.getString(R.string.sync_conflict_current)
    val draft = context.getString(R.string.sync_conflict_draft)
    val selection = mutableSetOf<String>()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun text(value: String, size: Float, bold: Boolean) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ink)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun pair(left: TextView, right: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(8) })
    }

    fun header(value: String) = text(value, 13f, bold = true).apply {
        setPadding(dp(8), 0, dp(8), 0)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    fun cell(value: String, column: String, field: String, highlighted: Boolean = false) =
        text(value, 14f, bold = highlighted).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (highlighted) highlight else surface)
            }
            contentDescription = context.getString(R.string.sync_conflict_cell_description, column, field, value)
        }

    fun fieldHeading(field: SyncConflictField): TextView {
        val key = field.key
        if (key != null && key in selectable) {
            return CheckBox(context).apply {
                text = field.label
                setTextColor(ink)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = dp(48)
                buttonTintList = ColorStateList.valueOf(brand)
                contentDescription = context.getString(R.string.sync_conflict_select_field, field.label)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selection.add(key) else selection.remove(key)
                    onSelectionChanged(selection.toSet())
                }
            }
        }
        return text(field.label, 13f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
            ViewCompat.setAccessibilityHeading(this, true)
            if (field.changed) {
                contentDescription = context.getString(R.string.sync_conflict_different_field, field.label)
            }
        }
    }

    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(pair(header(current), header(draft)), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        for (field in fields) {
            addView(fieldHeading(field), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(pair(cell(field.current, current, field.label), cell(field.draft, draft, field.label, field.changed)),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }
}
