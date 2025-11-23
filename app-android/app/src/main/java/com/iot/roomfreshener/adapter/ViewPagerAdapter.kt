package com.iot.roomfreshener.adapter

import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

data class TabItem(
    val title: String,
    val description: String,
    @DrawableRes val iconDefaultRes: Int,
    @DrawableRes val iconFilledRes: Int,
    val fragmentFactory: () -> Fragment
)

class ViewPagerAdapter(
    activity: FragmentActivity,
    private val tabs: List<TabItem>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment = tabs[position].fragmentFactory()
}
