package org.fossify.phone.ringtones

import android.content.Context
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.applyFontToTextView
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.views.MyTextView
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.widget.EditText
import android.widget.ProgressBar
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout
import org.fossify.phone.R
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton

/** Only shared UI mechanics live here; each tab owns its views and interactions. */
internal abstract class TabScreen(protected val activity: RingtoneController) : ContextWrapper(activity) {
    protected val model get() = activity.model
    protected val audio get() = activity.audio
    var refresh: () -> Unit = {}
    abstract fun create(): View

    protected fun chooseContact(title: String, selected: (AddressBookContact) -> Unit) {
        activity.lifecycleScope.launch {
            try {
                val contacts = model.refreshContacts()
                if (activity.currentScreen !== this@TabScreen || contacts.isEmpty()) return@launch
                activity.host.getAlertDialogBuilder().setTitle(title)
                    .setItems(contacts.map { it.name }.toTypedArray()) { _, index -> selected(contacts[index]) }
                    .setNegativeButton("Cancel", null).showPhoneDialog()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.status.value = "Cannot load contacts: ${e.message}"
            }
        }
    }
}

internal class RowsAdapter<T>(
    private val enabled: (T) -> Boolean = { true },
    private val render: (T) -> View
) : BaseAdapter() {
    var items: List<T> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = enabled(items[position])
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?) = render(items[position])
}

internal fun AlertDialog.observeUntilDismissed(
    owner: LifecycleOwner, vararg sources: LiveData<*>,
    onDismiss: () -> Unit = {}, render: () -> Unit
) {
    val observer = Observer<Any?> { render() }
    sources.forEach { it.observe(owner, observer) }
    setOnDismissListener {
        sources.forEach { it.removeObserver(observer) }
        onDismiss()
    }
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.column(padding: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}
internal fun Context.text(value: String, size: Int) = MyTextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_PX, this@text.getTextSize() * size / 16f)
    val margin = resources.getDimensionPixelSize(R.dimen.small_margin)
    setPadding(0, margin, 0, margin)
    this@text.applyFontToTextView(this)
    setTextColor(this@text.getProperTextColor())
}
internal fun Context.button(value: String, action: () -> Unit): Button =
    actionButton(value, com.google.android.material.R.attr.materialButtonStyle, action)
internal fun Context.secondaryButton(value: String, action: () -> Unit): Button =
    actionButton(value, 0, action)

private fun Context.actionButton(value: String, style: Int, action: () -> Unit) =
    (if (style == 0) android.view.LayoutInflater.from(this)
        .inflate(R.layout.item_ringtone_action, null, false) as MaterialButton
    else MaterialButton(this)).apply {
        text = value
        isAllCaps = false
        if (style != 0) tag = RINGTONE_PRIMARY_ACTION
        minHeight = resources.getDimensionPixelSize(R.dimen.list_touch_target_min)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        this@actionButton.applyRingtoneTheme(this)
    }

internal const val RINGTONE_SETTING_ROW = "ringtone_setting_row"
internal const val RINGTONE_PRIMARY_ACTION = "ringtone_primary_action"
internal const val RINGTONE_ERROR_TEXT = "ringtone_error_text"
internal const val RINGTONE_ACCENT_TEXT = "ringtone_accent_text"

/** Use Phone's custom colours rather than the base Material palette. */
internal fun Context.applyRingtoneTheme(root: View) {
    val primary = getProperPrimaryColor()
    val foreground = getProperTextColor()
    val background = getProperBackgroundColor()
    fun colors(enabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(ColorUtils.setAlphaComponent(foreground, 97), enabled)
    )
    fun style(view: View) {
        when (view) {
            is MaterialButton -> {
                val filled = view.tag == RINGTONE_PRIMARY_ACTION
                view.letterSpacing = 0f
                view.setTextColor(colors(if (filled) primary.getContrastColor() else primary))
                view.iconTint = view.textColors
                view.backgroundTintList = if (filled) ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(ColorUtils.blendARGB(background, foreground, 0.12f), primary)
                ) else ColorStateList.valueOf(Color.TRANSPARENT)
                view.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 40))
                applyFontToTextView(view)
            }
            is TextView -> {
                view.setTextColor(colors(when (view.tag) {
                    RINGTONE_ACCENT_TEXT -> primary
                    RINGTONE_ERROR_TEXT -> foreground
                    else -> foreground
                }))
                view.setHintTextColor(ColorUtils.setAlphaComponent(foreground, 153))
                if (view.tag == RINGTONE_SETTING_ROW) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getTextSize())
                if (view is EditText) {
                    androidx.core.view.ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(primary))
                }
                applyFontToTextView(view)
            }
            is ProgressBar -> {
                view.indeterminateTintList = ColorStateList.valueOf(primary)
                view.progressTintList = ColorStateList.valueOf(primary)
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
        if (view is TabLayout) {
            view.setTabTextColors(foreground, primary)
            view.setSelectedTabIndicatorColor(primary)
            view.tabRippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 40))
            view.setBackgroundColor(background)
        }
    }
    style(root)
}
