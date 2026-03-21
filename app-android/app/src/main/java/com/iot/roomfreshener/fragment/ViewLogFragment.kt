package com.iot.roomfreshener.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iot.roomfreshener.R
import com.iot.roomfreshener.ui.log.ViewLogViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ViewLogFragment : Fragment() {

    private val viewModel: ViewLogViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_view_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvContent = view.findViewById<TextView>(R.id.tvLogContent)
        val btnSyncLog = view.findViewById<View>(R.id.btnSyncLog)

        btnSyncLog.setOnClickListener {
            viewModel.startSync(silent = false)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.logsText.collectLatest { text ->
                        tvContent.text = text
                    }
                }
                launch {
                    while (isActive) {
                        viewModel.startSync(silent = true)
                        delay(5_000L)
                    }
                }
            }
        }
    }

    companion object {
        fun newInstance() = ViewLogFragment()
    }
}
