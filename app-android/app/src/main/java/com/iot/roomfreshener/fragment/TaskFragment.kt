package com.iot.roomfreshener.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.iot.roomfreshener.R
import com.iot.roomfreshener.adapter.TaskAdapter
import com.iot.roomfreshener.adapter.TaskItem
import com.iot.roomfreshener.dialogs.EditTaskDialog

class TaskFragment : Fragment() {

    private val tasks = mutableListOf(
        TaskItem(
            id = 1,
            hour = 7,
            minute = 0,
            durationSeconds = 30,
            repeatDays = listOf("T2", "T4", "T6"),
            enabled = true
        ),
        TaskItem(
            id = 2,
            hour = 20,
            minute = 30,
            durationSeconds = 45,
            repeatDays = listOf("T3", "T5"),
            enabled = false
        )
    )

    private lateinit var adapter: TaskAdapter
    private lateinit var placeholder: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_task, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.task_list)
        val fab = view.findViewById<FloatingActionButton>(R.id.task_fab)
        placeholder = view.findViewById(R.id.task_placeholder)

        adapter = TaskAdapter(
            items = tasks,
            onItemClick = { showTaskDialog(it) },
            onToggle = { item, enabled ->
                item.enabled = enabled
                adapter.notifyItemChanged(tasks.indexOf(item))
            }
        )
        recycler.adapter = adapter
        updatePlaceholder()

        fab.setOnClickListener { showTaskDialog(null) }
    }

    private fun showTaskDialog(task: TaskItem?) {
        EditTaskDialog(requireActivity(), task) { updated ->
            val index = tasks.indexOfFirst { it.id == updated.id }
            if (index == -1) {
                tasks.add(updated)
                adapter.notifyItemInserted(tasks.lastIndex)
            } else {
                tasks[index] = updated
                adapter.notifyItemChanged(index)
            }
            updatePlaceholder()
        }.show()
    }

    private fun updatePlaceholder() {
        placeholder.isVisible = tasks.isEmpty()
    }

    companion object {
        fun newInstance() = TaskFragment()
    }
}
