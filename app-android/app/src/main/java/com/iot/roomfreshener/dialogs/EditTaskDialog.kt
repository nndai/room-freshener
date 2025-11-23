package com.iot.roomfreshener.dialogs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.iot.roomfreshener.R
import com.iot.roomfreshener.adapter.TaskItem
import com.shawnlin.numberpicker.NumberPicker

class EditTaskDialog(
    private val activity: FragmentActivity,
    private val task: TaskItem?,
    private val onSave: (TaskItem) -> Unit
) {

    private val view = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_task, null, false)
    private val dialog = MaterialAlertDialogBuilder(activity).setView(view).create()

    private val hourPicker: NumberPicker = view.findViewById(R.id.number_picker_hour)
    private val minutePicker: NumberPicker = view.findViewById(R.id.number_picker_minute)
    private val durationInput: TextInputEditText = view.findViewById(R.id.inputNumber)

    private val daysHolder: LinearLayout = view.findViewById(R.id.task_days_holder)
    private val dayLetters = activity.resources.getStringArray(R.array.task_day_letters).toList()
    private val selectedDays = task?.repeatDays
        ?.mapNotNull { dayLetters.indexOf(it).takeIf { idx -> idx >= 0 } }
        ?.toMutableSet() ?: mutableSetOf()

    init {
        setupInitialValues()
        inflateDayViews()
        view.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val updated = TaskItem(
                id = task?.id ?: System.currentTimeMillis(),
                hour = hourPicker.value,
                minute = minutePicker.value,
                durationSeconds = durationInput.text?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: 30,
                repeatDays = selectedDays.sorted().map { dayLetters[it] },
                enabled = task?.enabled ?: true
            )
            onSave(updated)
            dialog.dismiss()
        }
    }

    fun show() = dialog.show()

    private fun setupInitialValues() {
        hourPicker.value = task?.hour ?: 7
        minutePicker.value = task?.minute ?: 0
        durationInput.setText((task?.durationSeconds ?: 30).toString())
    }

    private fun inflateDayViews() {
        daysHolder.removeAllViews()
        dayLetters.indices.forEach { index ->
            val dayView = activity.layoutInflater.inflate(R.layout.task_day, daysHolder, false) as TextView
            dayView.text = dayLetters[index]
            applyDayState(dayView, selectedDays.contains(index))
            dayView.setOnClickListener {
                if (!selectedDays.add(index)) {
                    selectedDays.remove(index)
                }
                applyDayState(dayView, selectedDays.contains(index))
            }
            daysHolder.addView(dayView)
        }
    }

    private fun applyDayState(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(19f)
            setStroke(dp(1f).toInt(), Color.parseColor("#C5CAD3"))
            setColor(if (selected) Color.parseColor("#114D85") else Color.TRANSPARENT)
        }
        val textColor = if (selected) Color.WHITE else Color.parseColor("#5F6368")
        view.setTextColor(textColor)
    }

    private fun dp(value: Float): Float =
        value * activity.resources.displayMetrics.density
}
