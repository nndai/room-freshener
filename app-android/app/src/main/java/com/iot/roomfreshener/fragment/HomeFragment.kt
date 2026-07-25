package com.iot.roomfreshener.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iot.roomfreshener.R
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.google.android.material.snackbar.Snackbar
import com.iot.roomfreshener.data.model.SprayMoment
import com.google.android.material.textfield.TextInputEditText
import com.iot.roomfreshener.ui.home.HomeViewModel
import java.time.Duration
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by activityViewModels()

    private lateinit var tvTemperature: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvLastSprayTime: TextView
    private lateinit var tvLastSprayAgo: TextView
    private lateinit var tvNextSprayTime: TextView
    private lateinit var tvNextSprayCountdown: TextView
    private lateinit var tvSprayMode: TextView
    private lateinit var tvTotalSprays: TextView
    private lateinit var tvCyclesPerDay: TextView
    private lateinit var tvSprayActiveStatus: TextView
    private lateinit var btnSprayNow: MaterialButton
    private lateinit var btnStopSpray: MaterialButton
    private lateinit var btnSprayMode: MaterialButton
    private lateinit var overlayLoading: View
    private lateinit var tvLatency: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        observeUi()
        btnSprayNow.setOnClickListener { viewModel.sprayNow() }
        btnStopSpray.setOnClickListener { viewModel.stopSpray() }
        btnSprayMode.setOnClickListener { showDurationDialog() }
    }

    private fun bindViews(root: View) {
        tvTemperature = root.findViewById(R.id.tvTemperature)
        tvHumidity = root.findViewById(R.id.tvHumidity)
        tvLastSprayTime = root.findViewById(R.id.tvLastSprayTime)
        tvLastSprayAgo = root.findViewById(R.id.tvLastSprayAgo)
        tvNextSprayTime = root.findViewById(R.id.tvNextSprayTime)
        tvNextSprayCountdown = root.findViewById(R.id.tvNextSprayCountdown)
        tvSprayMode = root.findViewById(R.id.tvSprayMode)
        tvTotalSprays = root.findViewById(R.id.tvTotalSprays)
        tvCyclesPerDay = root.findViewById(R.id.tvCyclesPerDay)
        tvSprayActiveStatus = root.findViewById(R.id.tvSprayActiveStatus)
        btnSprayNow = root.findViewById(R.id.btnSprayNow)
        btnStopSpray = root.findViewById(R.id.btnStopSpray)
        btnSprayMode = root.findViewById(R.id.btnSprayMode)
        overlayLoading = root.findViewById(R.id.overlayLoading)
        tvLatency = root.findViewById(R.id.tvLatency)
    }

    private fun observeUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.homeSnapshot.collect { snapshot: HomeSnapshot? ->
                        renderSnapshot(snapshot)
                    }
                }
                launch {
                    viewModel.isProcessing.collect { isProcessing: Boolean ->
                        overlayLoading.visibility = if (isProcessing) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.latencyMs.collect { latency: Long? ->
                        tvLatency.text = if (latency != null) "Ping: ${latency}ms" else "Ping: --"
                    }
                }
                launch {
                    viewModel.messages.collect { message: String ->
                        if (message.isNotBlank()) {
                            Snackbar.make(requireView(), message as CharSequence, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    viewModel.activeTasksCount.collect { count: Int ->
                        tvCyclesPerDay.text = "Chu kỳ: $count lần/ngày"
                    }
                }
                launch {
                    viewModel.sprayDurationMs.collect { durationMs ->
                        val seconds = durationMs / 1000L
                        btnSprayMode.text = "Chế độ\n${seconds}s"
                    }
                }
                launch {
                    while (isActive) {
                        viewModel.refreshHome()
                        delay(REQUEST_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private fun renderSnapshot(snapshot: HomeSnapshot?) {
        tvTemperature.text = snapshot?.temperature?.let { formatTemperature(it) } ?: "--°C"
        tvHumidity.text = snapshot?.humidity?.let { formatHumidity(it) } ?: "--%"

        val last = snapshot?.lastSpray
        tvLastSprayTime.text = formatTimeOrFallback(last)
        tvLastSprayAgo.text = last?.let { formatAgo(it) } ?: "--"
        tvSprayMode.text = last?.reason?.let { mapReason(it) } ?: DEFAULT_MODE_TEXT

        val next = snapshot?.nextSpray
        tvNextSprayTime.text = formatTimeOrFallback(next)
        tvNextSprayCountdown.text = next?.let { formatCountdown(it) } ?: "--"

        tvTotalSprays.text = snapshot?.totalSprayCount?.toString() ?: "--"
        renderSprayActive(snapshot?.sprayActive)
    }

    private fun renderSprayActive(isActive: Boolean?) {
        when (isActive) {
            true -> {
                tvSprayActiveStatus.text = "ĐANG BẬT"
                tvSprayActiveStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
            }
            false -> {
                tvSprayActiveStatus.text = "ĐANG TẮT"
                tvSprayActiveStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            }
            null -> {
                tvSprayActiveStatus.text = "--"
                tvSprayActiveStatus.setTextColor(resources.getColor(android.R.color.darker_gray, null))
            }
        }
    }

    private fun formatTemperature(value: Double): String {
        return String.format(Locale.getDefault(), "%.1f°C", value)
    }

    private fun formatHumidity(value: Double): String {
        return String.format(Locale.getDefault(), "%.0f%%", value)
    }

    private fun formatTimeOrFallback(moment: SprayMoment?): String {
        return if (moment?.hasValidTime == true && moment.hour != null && moment.minute != null) {
            String.format(Locale.getDefault(), "%02d:%02d", moment.hour, moment.minute)
        } else {
            "--:--"
        }
    }

    private fun formatAgo(moment: SprayMoment): String {
        return formatDelta(moment, past = true)
    }

    private fun formatCountdown(moment: SprayMoment): String {
        return formatDelta(moment, past = false)
    }

    private fun formatDelta(moment: SprayMoment, past: Boolean): String {
        if (!moment.hasValidTime || moment.hour == null || moment.minute == null) {
            return "--"
        }
        val now = LocalTime.now()
        val target = LocalTime.of(moment.hour, moment.minute)
        var duration = if (past) {
            Duration.between(target, now)
        } else {
            Duration.between(now, target)
        }
        if (duration.isNegative) {
            duration = duration.plusHours(24)
        }
        val hours = duration.toHours()
        val minutes = duration.minusHours(hours).toMinutes()
        val template = if (past) "Cách đây %02d giờ %02d phút" else "Còn %02d giờ %02d phút"
        return String.format(Locale.getDefault(), template, hours, minutes)
    }

    private fun mapReason(reason: Int): String {
        return when (reason) {
            0 -> "Auto Task"
            1 -> "Hardware Button"
            2 -> "App Button"
            else -> "Other"
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showDurationDialog() {
        val input = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Nhập giây (1-4tỷ)"
            setText((viewModel.sprayDurationMs.value / 1000L).toString())
            setSelection(text?.length ?: 0)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Chế độ thời gian phun")
            .setMessage("Thiết lập thời gian cho nút bật phun sương")
            .setView(input, 50, 20, 50, 0)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                val seconds = input.text?.toString()?.trim()?.toLongOrNull()
                if (seconds == null || seconds !in 1L..4000000000) {
                    Snackbar.make(requireView(), "Vui lòng nhập số giây từ 1 đến 4 tỷ", Snackbar.LENGTH_SHORT).show()
                } else {
                    viewModel.setSprayDurationSeconds(seconds)
                }
            }
            .show()
    }

    companion object {
        private const val REQUEST_INTERVAL_MS = 5_000L
        private const val DEFAULT_MODE_TEXT = "--"

        fun newInstance() = HomeFragment()
    }
}
