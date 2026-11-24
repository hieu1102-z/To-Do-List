package com.finalexam.to_do_list.controller.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.finalexam.to_do_list.R

internal enum class CategoryIcon(val key: String, val drawable: Int, val label: Int) {
    PLUS("plus", R.drawable.ic_category_plus, R.string.category_icon_plus),
    FOLDER("folder", R.drawable.ic_detail_folder, R.string.category_icon_folder),
    CHECK("check", R.drawable.ic_check, R.string.category_icon_check),
    STAR("star", R.drawable.ic_category_star, R.string.category_icon_star),
    BOOK("book", R.drawable.ic_category_book, R.string.category_icon_book),
    WORK("work", R.drawable.ic_category_work, R.string.category_icon_work),
    SHOPPING("shopping", R.drawable.ic_category_shopping, R.string.category_icon_shopping),
    HEART("heart", R.drawable.ic_category_heart, R.string.category_icon_heart);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: PLUS
    }
}

internal enum class CategoryColor(val hex: String, val label: Int) {
    BLUE("#5064EE", R.string.category_color_blue),
    TEAL("#087F8C", R.string.category_color_teal),
    GREEN("#287853", R.string.category_color_green),
    PURPLE("#7951B8", R.string.category_color_purple),
    ORANGE("#A95513", R.string.category_color_orange),
    RED("#BA4B56", R.string.category_color_red),
    PINK("#B14680", R.string.category_color_pink),
    GRAY("#5B6270", R.string.category_color_gray)
}

internal data class CategoryAppearance(
    val icon: CategoryIcon = CategoryIcon.PLUS,
    val color: CategoryColor = CategoryColor.BLUE
)

internal fun categoryAccent(colorHex: String?): Int {
    val blue = Color.parseColor(CategoryColor.BLUE.hex)
    return colorHex?.let { runCatching { Color.parseColor(it) }.getOrDefault(blue) } ?: blue
}

internal fun ImageView.renderCategoryIcon(icon: CategoryIcon, colorHex: String?, corner: Int, border: Int? = null) {
    val accent = categoryAccent(colorHex)
    setImageResource(icon.drawable)
    imageTintList = ColorStateList.valueOf(accent)
    background = GradientDrawable().apply {
        setColor(ColorUtils.blendARGB(accent, Color.WHITE, 0.88f))
        cornerRadius = context.dp(corner).toFloat()
        border?.let { setStroke(context.dp(2), it) }
    }
}

internal fun showCategoryAppearancePicker(
    context: Context,
    dialogs: WorkspaceDialogs,
    initial: CategoryAppearance,
    onApply: (CategoryAppearance) -> Unit
) {
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val ink = ContextCompat.getColor(context, R.color.ink)
    var selected = initial
    fun heading(@StringRes title: Int, top: Int) = TextView(context).apply {
        setText(title)
        setTextColor(ink)
        setPadding(0, context.dp(top), 0, context.dp(6))
    }

    content.addView(heading(R.string.category_appearance_icon, 0))
    val iconButtons = content.addOptions(CategoryIcon.entries) { it.label }
    content.addView(heading(R.string.category_appearance_color, 12))
    fun renderIcons() {
        iconButtons.forEach { (icon, button) ->
            button.isSelected = icon == selected.icon
            button.renderCategoryIcon(icon, selected.color.hex, 10, ink.takeIf { button.isSelected })
        }
    }
    iconButtons.forEach { (icon, button) ->
        button.setOnClickListener {
            selected = selected.copy(icon = icon)
            renderIcons()
        }
    }
    content.addView(categoryColorPalette(context, selected.color.hex) { color ->
        selected = selected.copy(color = color)
        renderIcons()
        true
    })
    renderIcons()
    dialogs.customChoice(context.getString(R.string.category_appearance_title), content,
        WorkspaceDialogs.Action(context.getString(R.string.category_appearance_apply)) { onApply(selected) })
}

internal fun categoryColorPalette(context: Context, initial: String?, select: (CategoryColor) -> Boolean): LinearLayout {
    val palette = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val ink = ContextCompat.getColor(context, R.color.ink)
    val swatches = palette.addOptions(CategoryColor.entries) { it.label }
    fun render(selected: String?) {
        val hex = selected ?: CategoryColor.BLUE.hex
        swatches.forEach { (color, button) ->
            button.isSelected = color.hex.equals(hex, ignoreCase = true)
            button.background = GradientDrawable().apply {
                cornerRadius = context.dp(10).toFloat()
                setColor(Color.parseColor(color.hex))
                if (button.isSelected) setStroke(context.dp(2), ink)
            }
            button.setImageResource(if (button.isSelected) R.drawable.ic_check else 0)
            button.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }
    swatches.forEach { (color, button) ->
        button.setOnClickListener { if (select(color)) render(color.hex) }
    }
    render(initial)
    return palette
}

private fun <T> LinearLayout.addOptions(
    choices: List<T>,
    label: (T) -> Int
): Map<T, ImageButton> = buildMap {
    choices.chunked(4).forEach { group ->
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        addView(row)
        group.forEach { value ->
            val button = ImageButton(context).apply {
                contentDescription = context.getString(label(value))
                scaleType = ImageView.ScaleType.FIT_CENTER
                val padding = context.dp(12)
                setPadding(padding, padding, padding, padding)
            }
            row.addView(button, LinearLayout.LayoutParams(0, context.dp(48), 1f).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            })
            put(value, button)
        }
    }
}

private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
