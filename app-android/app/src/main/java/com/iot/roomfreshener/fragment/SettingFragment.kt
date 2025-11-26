package com.iot.roomfreshener.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.iot.roomfreshener.R
import com.iot.roomfreshener.data.model.ModeConnect
import com.iot.roomfreshener.data.model.WifiConfigPayload
import com.iot.roomfreshener.ui.setting.SettingViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class SettingFragment : Fragment() {

    private val viewModel: SettingViewModel by activityViewModels()

    private lateinit var tvEspTimestamp: TextView
    private lateinit var btnSyncTime: MaterialButton
    private lateinit var btnSubmitConfig: MaterialButton
    private lateinit var etMode: MaterialAutoCompleteTextView
    private lateinit var etSsidAp: TextInputEditText
    private lateinit var etPasswordAp: TextInputEditText
    private lateinit var etSsid: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var inputModeLayout: TextInputLayout
    private lateinit var inputSsidApLayout: TextInputLayout
    private lateinit var inputPasswordApLayout: TextInputLayout
    private lateinit var inputSsidLayout: TextInputLayout
    private lateinit var inputPasswordLayout: TextInputLayout

    private var selectedMode: ModeConnect = ModeConnect.WEBSOCKET

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_setting, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupModeDropdown()
        observeState()
        bindInteractions()
    }

    private fun bindViews(root: View) {
        tvEspTimestamp = root.findViewById(R.id.tvEspTimestamp)
        btnSyncTime = root.findViewById(R.id.btnSyncTime)
        btnSubmitConfig = root.findViewById(R.id.btnSubmitConfig)
        etMode = root.findViewById(R.id.etMode)
        etSsidAp = root.findViewById(R.id.etSsidAp)
        etPasswordAp = root.findViewById(R.id.etPasswordAp)
        etSsid = root.findViewById(R.id.etSsid)
        etPassword = root.findViewById(R.id.etPassword)
        inputModeLayout = root.findViewById(R.id.inputModeLayout)
        inputSsidApLayout = root.findViewById(R.id.inputSsidApLayout)
        inputPasswordApLayout = root.findViewById(R.id.inputPasswordApLayout)
        inputSsidLayout = root.findViewById(R.id.inputSsidLayout)
        inputPasswordLayout = root.findViewById(R.id.inputPasswordLayout)
    }

    private fun bindInteractions() {
        btnSyncTime.setOnClickListener {
            viewModel.syncDeviceTimeWithPhone()
        }
        btnSyncTime.setOnLongClickListener {
            viewModel.refreshDeviceTime()
            true
        }
        btnSubmitConfig.setOnClickListener {
            submitConfig()
        }
    }

    private fun setupModeDropdown() {
        val modes = ModeConnect.values()
        val labels = modes.map { modeLabel(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
        etMode.setAdapter(adapter)
        val defaultMode = viewModel.defaultMode()
        selectedMode = defaultMode
        etMode.setText(modeLabel(defaultMode), false)
        etMode.setOnItemClickListener { _, _, position, _ ->
            if (position in modes.indices) {
                selectedMode = modes[position]
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.deviceTimeSeconds.collect { timestamp ->
                        renderDeviceTime(timestamp)
                    }
                }
                launch {
                    viewModel.messages.collect { message ->
                        if (message.isNotBlank()) {
                            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun renderDeviceTime(timestamp: Long?) {
        tvEspTimestamp.text = timestamp?.let { formatTimestamp(it) } ?: "--:--:--"
    }

    private fun formatTimestamp(timestamp: Long): String {
        return Instant.ofEpochSecond(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(TIMESTAMP_FORMATTER)
    }

    private fun submitConfig() {
        clearErrors()
        val payload = WifiConfigPayload(
            mode = selectedMode,
            ssidAp = etSsidAp.text?.toString()?.trim().orEmpty(),
            passwordAp = etPasswordAp.text?.toString()?.trim().orEmpty(),
            ssid = etSsid.text?.toString()?.trim().orEmpty(),
            password = etPassword.text?.toString()?.trim().orEmpty()
        )
        if (!validateForm()) {
            return
        }
        viewModel.submitWifiConfig(payload)
    }

    private fun validateForm(): Boolean {
        var valid = true
        if (etMode.text.isNullOrBlank()) {
            inputModeLayout.error = getString(R.string.setting_validation_mode)
            valid = false
        }
        return valid
    }

    private fun clearErrors() {
        inputModeLayout.error = null
        inputSsidApLayout.error = null
        inputPasswordApLayout.error = null
        inputSsidLayout.error = null
        inputPasswordLayout.error = null
    }

    private fun modeLabel(mode: ModeConnect): String {
        return when (mode) {
            ModeConnect.WEBSOCKET -> getString(R.string.mode_websocket)
            ModeConnect.BLYNK -> getString(R.string.mode_blynk)
            ModeConnect.MQTT -> getString(R.string.mode_mqtt)
        }
    }

    companion object {
        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss - dd/MM/yyyy")

        fun newInstance() = SettingFragment()
    }
}
