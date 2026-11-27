package org.fossify.phone.ringtones

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.isBlackAndWhiteTheme
import org.fossify.commons.extensions.isDynamicTheme

/** Use the same backgrounds, fonts and custom palette as Phone's other dialogs. */
internal fun AlertDialog.showPhoneDialog(): AlertDialog = apply {
    show()
    window?.decorView?.let { context.applyRingtoneTheme(it) }
    val background = when {
        context.isBlackAndWhiteTheme() -> androidx.core.content.ContextCompat.getDrawable(context, org.fossify.commons.R.drawable.black_dialog_background)
        context.isDynamicTheme() -> androidx.core.content.ContextCompat.getDrawable(context, org.fossify.commons.R.drawable.dialog_you_background)
        else -> context.resources.getColoredDrawableWithColor(org.fossify.commons.R.drawable.dialog_bg, context.baseConfig.backgroundColor)
    }
    window?.setBackgroundDrawable(background)
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
        getButton(it)?.setTextColor(context.getProperPrimaryColor())
    }
}

internal fun AlertDialog.Builder.showPhoneDialog() = create().showPhoneDialog()
