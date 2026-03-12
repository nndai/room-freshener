package com.iot.roomfreshener

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.iot.roomfreshener.adapter.TabItem
import com.iot.roomfreshener.adapter.ViewPagerAdapter
import com.iot.roomfreshener.data.di.DeviceRepositoryProvider
import com.iot.roomfreshener.data.remote.DeviceConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.iot.roomfreshener.fragment.HomeFragment
import com.iot.roomfreshener.fragment.SettingFragment
import com.iot.roomfreshener.fragment.TaskFragment
import com.iot.roomfreshener.fragment.ViewLogFragment
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val tabs = listOf(
        TabItem(
            title = "Trang chủ",
            description = "Hiển thị nhanh trạng thái phòng và hương thơm.",
            iconDefaultRes = R.drawable.ic_house_vector,
            iconFilledRes = R.drawable.ic_house_heart_filled_vector,
            fragmentFactory = { HomeFragment.newInstance() }
        ),
        TabItem(
            title = "Nhiệm vụ",
            description = "Lên lịch phun hoặc nhiệm vụ tự động.",
            iconDefaultRes = R.drawable.ic_task_vector,
            iconFilledRes = R.drawable.ic_task_filled_vector,
            fragmentFactory = { TaskFragment.newInstance() }
        ),
        TabItem(
            title = "Nhật ký",
            description = "Theo dõi lịch sử phun và cảnh báo.",
            iconDefaultRes = R.drawable.ic_note_vector,
            iconFilledRes = R.drawable.ic_note_filled_vector,
            fragmentFactory = { ViewLogFragment.newInstance() }
        ),
        TabItem(
            title = "Cài đặt",
            description = "Điều chỉnh hương liệu, cường độ, người dùng.",
            iconDefaultRes = R.drawable.ic_gear_vector,
            iconFilledRes = R.drawable.ic_gear_filled_vector,
            fragmentFactory = { SettingFragment.newInstance() }
        )
    )

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var globalSpinner: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }


        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.main_tabs_holder)

        viewPager.adapter = ViewPagerAdapter(this, tabs)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val config = tabs[position]
            tab.text = config.title
            tab.customView = createTabView(config)
            tab.contentDescription = "${config.title}. ${config.description}"
        }.attach()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) = updateTabViewState(tab, true)
            override fun onTabUnselected(tab: TabLayout.Tab?) = updateTabViewState(tab, false)
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
        for (i in 0 until tabLayout.tabCount) {
            updateTabViewState(tabLayout.getTabAt(i), i == tabLayout.selectedTabPosition)
        }

        globalSpinner = findViewById(R.id.globalSpinner)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val repo = DeviceRepositoryProvider.provide()
                repo.connectionState.collectLatest { state ->
                    globalSpinner.visibility = if (state is DeviceConnectionState.Connecting) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun createTabView(config: TabItem): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 8.dp())
            val iconView = ImageView(context)
            val labelView = TextView(context).apply {
                text = config.title
                textSize = 12f
                gravity = Gravity.CENTER
            }
            addView(iconView)
            addView(labelView)
            tag = TabViewHolder(iconView, labelView, config)
            applyTabVisualState(this, false)
        }

    private fun updateTabViewState(tab: TabLayout.Tab?, isSelected: Boolean) {
        applyTabVisualState(tab?.customView, isSelected)
    }

    private fun applyTabVisualState(customView: View?, isSelected: Boolean) {
        val holder = customView?.tag as? TabViewHolder ?: return
        val colorRes = if (isSelected) android.R.color.black else android.R.color.darker_gray
        val tint = ContextCompat.getColor(this, colorRes)
        holder.iconView.setImageResource(
            if (isSelected) holder.config.iconFilledRes else holder.config.iconDefaultRes
        )
        holder.iconView.imageTintList = ColorStateList.valueOf(tint)
        holder.labelView.setTextColor(tint)
        customView.alpha = if (isSelected) 1f else 0.7f
    }

    private data class TabViewHolder(
        val iconView: ImageView,
        val labelView: TextView,
        val config: TabItem
    )

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
}
