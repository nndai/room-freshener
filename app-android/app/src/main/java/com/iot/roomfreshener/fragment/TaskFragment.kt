package com.iot.roomfreshener.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.iot.roomfreshener.R
import com.iot.roomfreshener.adapter.TaskAdapter
import com.iot.roomfreshener.adapter.TaskItem
import com.iot.roomfreshener.dialogs.EditTaskDialog
import com.iot.roomfreshener.ui.task.TaskUiEvent
import com.iot.roomfreshener.ui.task.TaskViewModel
import kotlinx.coroutines.launch

class TaskFragment : Fragment() {

    private val viewModel: TaskViewModel by viewModels()

    private lateinit var adapter: TaskAdapter
    private lateinit var placeholder: TextView
    private lateinit var overlayLoading: View
    private var emptyMessage: CharSequence = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_task, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.task_list)
        val fab = view.findViewById<FloatingActionButton>(R.id.task_fab)
        placeholder = view.findViewById(R.id.task_placeholder)
        overlayLoading = view.findViewById(R.id.overlayLoading)
        emptyMessage = placeholder.text

        adapter = TaskAdapter(
            onItemClick = { showTaskDialog(it) },
            onToggle = { item, enabled -> viewModel.toggleTask(item, enabled) }
        )
        recycler.adapter = adapter

        fab.setOnClickListener { showTaskDialog(null) }

        collectUiState()
        collectEvents()
    }

    private fun collectUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.uiState.collect { state ->
                        adapter.submitList(state.items)
                        overlayLoading.isVisible = state.isProcessing
                        if (state.items.isEmpty() && state.isLoading) {
                            placeholder.text = getString(R.string.task_loading_placeholder)
                            placeholder.isVisible = true
                        } else {
                            placeholder.text = emptyMessage
                            placeholder.isVisible = state.items.isEmpty()
                        }
                    }
                }
            }
        }
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is TaskUiEvent.ShowMessage -> showSnackbar(event.message)
                        }
                    }
                }
                launch {
                    viewModel.refresh()
                }
            }
        }
    }

    private fun showTaskDialog(task: TaskItem?) {
        EditTaskDialog(
            requireActivity(),
            task,
            onSave = { updated -> viewModel.saveTask(updated) },
            onDelete = { deleted -> viewModel.deleteTask(deleted) }
        ).show()
    }

    private fun showSnackbar(message: String) {
        view?.let { root ->
            Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    companion object {
        fun newInstance() = TaskFragment()
    }
}
