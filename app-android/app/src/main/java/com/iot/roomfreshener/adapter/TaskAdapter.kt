package com.iot.roomfreshener.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.iot.roomfreshener.R
import java.util.Locale

data class TaskItem(
    val id: Long,
    var hour: Int,
    var minute: Int,
    var durationSeconds: Int,
    var repeatDays: List<String>,
    var enabled: Boolean
)

class TaskAdapter(
    private val onItemClick: (TaskItem) -> Unit,
    private val onToggle: (TaskItem, Boolean) -> Unit
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    private val items = mutableListOf<TaskItem>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder =
        TaskViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task, parent, false)
        )

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<TaskItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val time: TextView = view.findViewById(R.id.task_time)
        private val days: TextView = view.findViewById(R.id.task_days)
        private val duration: TextView = view.findViewById(R.id.task_duration)
        private val toggle: MaterialSwitch = view.findViewById(R.id.task_switch)

        fun bind(item: TaskItem) {
            time.text = String.format(Locale.getDefault(), "%02d:%02d", item.hour, item.minute)
            days.text = if (item.repeatDays.size == 7) {
                "Hàng ngày"
            } else {
                item.repeatDays.joinToString(", ").ifEmpty { "Không lặp" }
            }
            duration.text = "Thời gian phun: ${item.durationSeconds} giây"
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = item.enabled
            toggle.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }
            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
