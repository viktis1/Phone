package org.fossify.phone.ringtones

import android.content.Context
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getContrastColor
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
                AlertDialog.Builder(activity).setTitle(title)
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
internal fun Context.text(value: String, size: Int) = TextView(this).apply {
    text = value; textSize = size.toFloat(); setTextColor(this@text.getProperTextColor()); setPadding(0, dp(3), 0, dp(3))
}
internal fun Context.button(value: String, action: () -> Unit): Button =
    actionButton(value, com.google.android.material.R.attr.materialButtonStyle, action)
internal fun Context.secondaryButton(value: String, action: () -> Unit): Button =
    actionButton(value, com.google.android.material.R.attr.materialButtonOutlinedStyle, action)

private fun Context.actionButton(value: String, style: Int, action: () -> Unit) =
    MaterialButton(this, null, style).apply {
        text = value; isAllCaps = false; cornerRadius = dp(8)
        val primary = this@actionButton.getProperPrimaryColor()
        if (style == com.google.android.material.R.attr.materialButtonStyle) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            setTextColor(primary.getContrastColor())
        } else {
            setTextColor(primary)
            strokeColor = android.content.res.ColorStateList.valueOf(primary)
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
