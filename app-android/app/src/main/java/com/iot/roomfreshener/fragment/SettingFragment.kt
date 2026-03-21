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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.iot.roomfreshener.R
import com.iot.roomfreshener.data.model.ModeConnect
import com.iot.roomfreshener.data.model.WifiConfigPayload
import com.iot.roomfreshener.ui.setting.SettingViewModel
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.time.ZoneId

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
    private lateinit var etAppDuration: TextInputEditText
    private lateinit var etHwDuration: TextInputEditText
    private lateinit var btnSubmitDurations: MaterialButton
    private lateinit var inputModeLayout: TextInputLayout
    private lateinit var inputSsidApLayout: TextInputLayout
    private lateinit var inputPasswordApLayout: TextInputLayout
    private lateinit var inputSsidLayout: TextInputLayout
    private lateinit var inputPasswordLayout: TextInputLayout
    private lateinit var overlayLoading: View
    private lateinit var tvSystemInfo: TextView

    private var selectedMode: ModeConnect = ModeConnect.WEBSOCKET
    private var isPrefilled = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_setting, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupModeDropdown()
        
        val currentAppMs = viewModel.getAppButtonDurationMs()
        etAppDuration.setText((currentAppMs / 1000).toString())

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
        etAppDuration = root.findViewById(R.id.etAppDuration)
        etHwDuration = root.findViewById(R.id.etHwDuration)
        btnSubmitDurations = root.findViewById(R.id.btnSubmitDurations)
        inputModeLayout = root.findViewById(R.id.inputModeLayout)
        inputSsidApLayout = root.findViewById(R.id.inputSsidApLayout)
        inputPasswordApLayout = root.findViewById(R.id.inputPasswordApLayout)
        inputSsidLayout = root.findViewById(R.id.inputSsidLayout)
        inputPasswordLayout = root.findViewById(R.id.inputPasswordLayout)
        overlayLoading = root.findViewById(R.id.overlayLoading)
        tvSystemInfo = root.findViewById(R.id.tvSystemInfo)
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
        btnSubmitDurations.setOnClickListener {
            val appVal = etAppDuration.text?.toString()?.toLongOrNull()
            val hwVal = etHwDuration.text?.toString()?.toLongOrNull()
            if (appVal != null && hwVal != null) {
                viewModel.submitSystemSettings(appVal, hwVal)
            } else {
                Snackbar.make(requireView(), "Vui lòng nhập thời gian hợp lệ", Snackbar.LENGTH_SHORT).show()
            }
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.deviceTimeSeconds.collect { timestamp ->
                        renderDeviceTime(timestamp)
                    }
                }
                launch {
                    viewModel.systemInfoSnapshot.collect { info ->
                        if (info != null) {
                            tvSystemInfo.text = info.toFormattedString()
                            if (!etHwDuration.hasFocus() && etHwDuration.text.isNullOrBlank()) {
                                etHwDuration.setText((info.hwButtonDurationMs / 1000).toString())
                            }
                            if (!isPrefilled) {
                                isPrefilled = true
                                if (etSsidAp.text.isNullOrBlank()) etSsidAp.setText(info.configSsidAp)
                                if (etSsid.text.isNullOrBlank()) etSsid.setText(info.configSsid)
                                val mode = if (info.configMode == 1) ModeConnect.MQTT else ModeConnect.WEBSOCKET
                                selectedMode = mode
                                etMode.setText(modeLabel(mode), false)
                            }
                        } else {
                            tvSystemInfo.text = "Đang tải..."
                        }
                    }
                }
                launch {
                    viewModel.isProcessing.collect { isProcessing ->
                        overlayLoading.visibility = if (isProcessing) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.messages.collect { message ->
                        if (message.isNotBlank()) {
                            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    while (isActive) {
                        viewModel.refreshDeviceTime()
                        viewModel.refreshSystemInfo()
                        delay(5000L)
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

        val summary = buildConfigSummary(payload)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Xác nhận gửi cấu hình")
            .setMessage(summary)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Gửi") { _, _ ->
                viewModel.submitWifiConfig(payload)
            }
            .show()
    }

    private fun buildConfigSummary(payload: WifiConfigPayload): String {
        return buildString {
            append("Mode: ")
            append(modeLabel(payload.mode))
            append("\nSSID WiFi: ")
            append(payload.ssid.ifBlank { "(trống)" })
            append("\nMật khẩu WiFi: ")
            append(maskValue(payload.password))
            append("\nSSID AP: ")
            append(payload.ssidAp.ifBlank { "(trống)" })
            append("\nMật khẩu AP: ")
            append(maskValue(payload.passwordAp))
        }
    }

    private fun maskValue(value: String): String {
        if (value.isBlank()) return "(trống)"
        return "*".repeat(value.length.coerceAtMost(12))
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
            ModeConnect.MQTT -> getString(R.string.mode_mqtt)
        }
    }

    companion object {
        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss - dd/MM/yyyy")

        fun newInstance() = SettingFragment()
    }
}
