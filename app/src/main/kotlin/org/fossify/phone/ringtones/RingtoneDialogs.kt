package org.fossify.phone.ringtones

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor

/** Match the custom colours used by Fossify's dialogs in every ringtone flow. */
internal fun AlertDialog.showPhoneDialog(): AlertDialog = apply {
    show()
    val colour = context.getProperTextColor()
    fun style(view: View) {
        if (view is TextView) view.setTextColor(colour)
        if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
    }
    window?.decorView?.let(::style)
    window?.setBackgroundDrawable(GradientDrawable().apply {
        cornerRadius = context.dp(16).toFloat()
        setColor(context.getProperBackgroundColor())
    })
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
        getButton(it)?.setTextColor(context.getProperPrimaryColor())
    }
}

internal fun AlertDialog.Builder.showPhoneDialog() = create().showPhoneDialog()
