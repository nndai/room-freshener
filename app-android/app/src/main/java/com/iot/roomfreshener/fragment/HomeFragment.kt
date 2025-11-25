package com.iot.roomfreshener.fragment

import android.os.Bundle
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
import com.iot.roomfreshener.R
import com.iot.roomfreshener.data.model.HomeSnapshot
import com.iot.roomfreshener.data.model.SprayMoment
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
    private lateinit var btnSprayNow: MaterialButton

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
        btnSprayNow = root.findViewById(R.id.btnSprayNow)
    }

    private fun observeUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.homeSnapshot.collect { snapshot ->
                        renderSnapshot(snapshot)
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
            0 -> "Tự động"
            1 -> "Thủ công"
            else -> "Khác"
        }
    }

    companion object {
        private const val REQUEST_INTERVAL_MS = 5_000L
        private const val DEFAULT_MODE_TEXT = "--"

        fun newInstance() = HomeFragment()
    }
}
