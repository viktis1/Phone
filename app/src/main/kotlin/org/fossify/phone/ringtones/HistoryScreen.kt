package org.fossify.phone.ringtones

import org.fossify.phone.R

import android.Manifest
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.phone.databinding.ContentRingtoneHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val HISTORY_PREVIEW_LIMIT = 3

/** Inspect saved generation/upload results, download, listen, retry, or assign. */
internal class HistoryScreen(activity: RingtoneController) : TabScreen(activity) {
    var query: String = ""
    override fun create(): View {
        val binding = ContentRingtoneHistoryBinding.inflate(activity.host.layoutInflater)
        val sections = binding.sections
        refresh = {
            val calls = model.callHistory.value.orEmpty().filter { it.caller.contains(query, true) }
            val items = model.items.value.orEmpty().filter { it.contactName.contains(query, true) || it.voiceName.contains(query, true) }
            sections.removeAllViews()
            sections.addView(previewSection(
                getString(R.string.ringtone_incoming_calls),
                if (calls.isEmpty()) getString(R.string.ringtone_no_calls)
                else getString(R.string.ringtone_calls_summary, calls.size, minOf(HISTORY_PREVIEW_LIMIT, calls.size)),
                calls.take(HISTORY_PREVIEW_LIMIT).map(::callRow),
                getString(R.string.ringtone_all_calls, calls.size), ::showAllCalls,
                if (canGenerateFromCalls()) null else secondaryButton(getString(R.string.ringtone_enable_calls)) {
                    activity.runAction(ScreenAction.CALL_HISTORY)
                }
            ))
            sections.addView(previewSection(
                getString(R.string.ringtone_activity),
                if (items.isEmpty()) getString(R.string.ringtone_no_activity)
                else getString(R.string.ringtone_activity_summary, items.size, items.count { it.active }, items.count { it.stage == "failed" }),
                items.take(HISTORY_PREVIEW_LIMIT).map { item ->
                    itemRow(item, compact = true).apply {
                        isFocusable = true
                        setOnClickListener { showItem(item) }
                    }
                },
                getString(R.string.ringtone_all_activity, items.size), ::showAllItems
            ))
        }
        refresh()
        applyRingtoneTheme(binding.root)
        return binding.root
    }

    private fun canGenerateFromCalls() = activity.isDefaultPhone() && listOfNotNull(
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { android.os.Build.VERSION.SDK_INT < 29 }
    ).all(activity::hasPermission)

    private fun previewSection(
        title: String, summary: String, rows: List<View>,
        action: String, onClick: () -> Unit, setup: View? = null
    ) = column().apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.activity_margin)
        }
        addView(text(title, 16).apply {
            setTypeface(typeface, Typeface.BOLD)
            val margin = resources.getDimensionPixelSize(R.dimen.normal_margin)
            setPadding(0, margin, 0, margin)
        })
        setup?.let(::addView)
        addView(text(summary, 14))
        addView(column().apply {
            dividerDrawable = historyDivider()
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            rows.forEach { addView(it) }
        })
        addView(secondaryButton(action, onClick).apply { isEnabled = rows.isNotEmpty() })
        addView(android.view.LayoutInflater.from(this@HistoryScreen).inflate(R.layout.divider, this, false))
        applyRingtoneTheme(this)
    }

    private fun historyDivider() = GradientDrawable().apply {
        setColor(ColorUtils.setAlphaComponent(
            getProperTextColor(), 45))
        setSize(1, dp(1).coerceAtLeast(1))
    }

    private fun timestamp(value: Long) =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

    private fun compactText(value: String, size: Int) = text(value, size).apply {
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
    }

    private fun callRow(entry: CallHistoryEntry) = column().apply {
        setPadding(dp(8), dp(4), dp(8), 0)
        addView(compactText(entry.caller, 16))
        addView(compactText(timestamp(entry.received), 12).apply { setPadding(0, dp(1), 0, 0) })
        val queued = entry.detail.startsWith("Automatic ringtone generation queued")
        val status = getString(if (queued) R.string.ringtone_queued
            else if (entry.detail.startsWith("Waiting")) R.string.ringtone_preparing else R.string.ringtone_not_queued)
        addView(compactText("$status  ›", 14).apply {
            minHeight = dp(32)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = "$status. Show call details for ${entry.caller}"
            val feedback = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, feedback, true)
            setBackgroundResource(feedback.resourceId)
            setOnClickListener { showCall(entry) }
            setTypeface(typeface, Typeface.BOLD)
            tag = RINGTONE_ACCENT_TEXT
            setTextColor(getProperPrimaryColor())
        })
    }

    private fun showCall(initial: CallHistoryEntry) {
        val body = column(20)
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = activity.host.getAlertDialogBuilder().setTitle(initial.caller).setView(scroll)
            .setPositiveButton("Close", null).create()
        dialog.observeUntilDismissed(activity, model.callHistory) {
            val entry = model.callHistory.value.orEmpty().firstOrNull { it.id == initial.id } ?: initial
            body.removeAllViews()
            body.addView(text(timestamp(entry.received), 13))
            body.addView(text(entry.detail, 16).apply { setTextIsSelectable(true) })
        }
        dialog.showPhoneDialog()
    }

    private fun itemRow(item: RingtoneItem, compact: Boolean = false) = column(8).apply {
        addView(text(item.contactName, 16))
        addView(text(item.voiceName + " · " + timestamp(item.created), 12))
        addView(text(item.label, 15).apply {
            setTypeface(typeface, Typeface.BOLD)
            tag = RINGTONE_ACCENT_TEXT
            setTextColor(getProperPrimaryColor())
        })
        if (item.detail.isNotBlank()) addView(text(item.detail, 14).apply {
            if (compact) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        })
        if (item.active) addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
        addView(text(getString(R.string.ringtone_open_details) + "  ›", 14))
    }

    private fun showAllCalls() = showHistoryList(
        "All incoming calls", model.callHistory, ::callRow)

    private fun showAllItems() = showHistoryList(
        "All ringtone activity", model.items, { itemRow(it) }, ::showItem)

    private fun <T> showHistoryList(
        title: String,
        source: LiveData<List<T>>,
        render: (T) -> View,
        onClick: ((T) -> Unit)? = null
    ) {
        val adapter = RowsAdapter<T>(render = render)
        val list = ListView(this).apply {
            divider = historyDivider()
            dividerHeight = dp(1).coerceAtLeast(1)
            setFooterDividersEnabled(false)
            setHeaderDividersEnabled(false)
            this.adapter = adapter
            if (onClick != null) setOnItemClickListener { _, _, position, _ ->
                adapter.items.getOrNull(position)?.let(onClick)
            }
        }
        val dialog = activity.host.getAlertDialogBuilder().setTitle(title).setView(list)
            .setPositiveButton("Close", null).create()
        dialog.observeUntilDismissed(activity, source) { adapter.items = source.value.orEmpty() }
        dialog.showPhoneDialog()
    }

    private fun showItem(initial: RingtoneItem) {
        val body = column(20)
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = activity.host.getAlertDialogBuilder().setTitle(initial.contactName).setView(scroll)
            .setPositiveButton("Close", null).create()
        fun render(item: RingtoneItem) {
            body.removeAllViews()
            body.addView(text(item.voiceName, 18))
            if (item.clipName.isNotBlank()) body.addView(text(item.clipName.substringAfterLast('/'), 14))
            body.addView(text(item.label, 20))
            if (item.detail.isNotBlank() && item.generationStatus == null) {
                body.addView(text(item.detail, 14))
            }
            item.generationStatus?.let { generation ->
                val fields = column().apply {
                    dividerDrawable = historyDivider()
                    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                }
                for ((key, value) in generation.responseFields) {
                    fields.addView(column(8).apply {
                        addView(text(key.replace('_', ' ').replaceFirstChar { it.titlecase() }, 14).apply {
                            setTypeface(typeface, Typeface.BOLD)
                        })
                        addView(text(value, 15).apply { setTextIsSelectable(true) })
                    })
                }
                body.addView(fields)
            }
            if (item.active) body.addView(ProgressBar(this))
            when (item.stage) {
                "ready" -> body.addView(button("Download ringtone") { model.download(item) })
                "downloaded", "assigned" -> {
                    body.addView(button("Listen") { playItem(item) })
                    body.addView(button("Stop audio") { activity.stopAudio() })
                    body.addView(button("Assign to ${item.contactName}") { requestAssignment(item) })
                    body.addView(button("Choose assignment contact") { chooseAssignmentContact(item) })
                    body.addView(button("Download again") { activity.stopAudio(); model.download(item) })
                }
                "failed" -> {
                    if (item.resumeStage.isNotBlank()) body.addView(button("Retry") {
                        if (item.resumeStage == "assigning") requestAssignment(item) else model.retry(item)
                    })
                    if (item.resumeStage == "assigning") {
                        body.addView(button("Choose assignment contact") { chooseAssignmentContact(item) })
                        body.addView(button("Listen") { playItem(item) })
                        body.addView(button("Download again") { activity.stopAudio(); model.download(item) })
                    }
                    if (item.clipId.isNotBlank()) body.addView(button("Generate again") { activity.requestGeneration(item.id); dialog.dismiss() })
                    body.addView(text("Your other ringtones and selections are saved.", 14))
                }
                "uploaded" -> body.addView(button("Refresh voices") { model.loadVoices(); dialog.dismiss(); activity.navigate(R.id.section_voices) })
            }
            body.addView(text("Job ${item.id}" + if (item.route.isBlank()) "" else "\nSubmitted via ${item.route}", 12).apply { setTextIsSelectable(true) })
        }
        dialog.observeUntilDismissed(activity, model.items, onDismiss = { activity.stopAudio() }) {
            render(model.items.value.orEmpty().firstOrNull { it.id == initial.id } ?: initial)
        }
        dialog.showPhoneDialog()
    }

    private fun requestAssignment(item: RingtoneItem) {
        if (item.contactUri.isBlank()) { chooseAssignmentContact(item); return }
        activity.runAction(ScreenAction.ASSIGN, item.id)
    }

    fun chooseAssignmentContact(item: RingtoneItem) {
        if (!activity.hasPermission(Manifest.permission.READ_CONTACTS)) {
            activity.runAction(ScreenAction.ASSIGNMENT_CONTACT, item.id); return
        }
        chooseContact("Assign this ringtone to…") { model.setAssignmentContact(item, it) }
    }

    private fun playItem(item: RingtoneItem) {
        if (model.recording.value == true) return
        activity.stopAudio()
        activity.previewTask = activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { RingtoneLibrary.get(activity).audioFile(activity, item) }
            if (file.isFile) audio.play(Uri.fromFile(file), "ringtone for ${item.contactName}")
            else model.status.value = "Local audio is missing. Tap Download again."
        }
    }
}
