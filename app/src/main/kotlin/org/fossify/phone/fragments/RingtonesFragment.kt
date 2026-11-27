package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import org.fossify.commons.views.MyRecyclerView
import org.fossify.phone.activities.MainActivity

/** Ringtone generation and its library live inside the Phone pager. */
class RingtonesFragment(context: Context, attrs: AttributeSet) :
    MyViewPagerFragment<MyViewPagerFragment.InnerBinding>(context, attrs) {
    override fun setupFragment() {
        innerBinding = object : InnerBinding {
            override val fragmentList: MyRecyclerView? = null
            override val recentsList: MyRecyclerView? = null
        }
        val controller = (activity as MainActivity).ringtones
        addView(controller.createView(), RelativeLayout.LayoutParams(-1, -1))
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        (activity as? MainActivity)?.ringtones?.updateColors()
    }
    override fun onSearchClosed() = Unit
    override fun onSearchQueryChanged(text: String) {
        (activity as? MainActivity)?.ringtones?.search(text)
    }
}
