package com.finalexam.to_do_list.controller.home

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.finalexam.to_do_list.R
import com.finalexam.to_do_list.databinding.DialogDeleteTaskBinding
import com.finalexam.to_do_list.model.local.relation.HomeTask
import com.finalexam.to_do_list.model.repository.GuestTaskRepository

/** Confirmation UI based on the supplied reference; the selected task ID is the delete target. */
internal object DeleteTaskDialog {
    fun show(activity: ComponentActivity, task: HomeTask, repository: GuestTaskRepository, onDeleted: () -> Unit) {
        val binding = DialogDeleteTaskBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).create()
        dialog.setView(binding.root, 0, 0, 0, 0)
        binding.message.text = activity.getString(R.string.task_delete_message, task.title)
        ViewCompat.setAccessibilityHeading(binding.heading, true)
        var busy = false
        fun setBusy(value: Boolean) {
            busy = value
            binding.cancel.isEnabled = !value; binding.delete.isEnabled = !value
            binding.delete.alpha = if (value) 0.65f else 1f
            binding.delete.setText(if (value) R.string.task_delete_progress else R.string.task_delete_confirm)
            dialog.setCancelable(!value); dialog.setCanceledOnTouchOutside(!value)
        }
        binding.cancel.setOnClickListener { if (!busy) dialog.dismiss() }
        binding.delete.setOnClickListener {
            if (busy) return@setOnClickListener
            setBusy(true)
            binding.error.visibility = View.GONE
            repository.delete(task.id) { result ->
                if (activity.isFinishing || activity.isDestroyed) return@delete
                if (result.isSuccess) { dialog.dismiss(); onDeleted() }
                else if (dialog.isShowing) { setBusy(false); binding.error.visibility = View.VISIBLE }
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
        dialog.setOnShowListener {
            val display = activity.resources.displayMetrics
            val density = display.density
            val width = minOf(display.widthPixels - (48 * density).toInt(), (440 * density).toInt())
            binding.content.measure(View.MeasureSpec.makeMeasureSpec((width - 52 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            binding.content.layoutParams = binding.content.layoutParams.apply {
                height = minOf(binding.content.measuredHeight, (display.heightPixels * 0.4f).toInt())
            }
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
