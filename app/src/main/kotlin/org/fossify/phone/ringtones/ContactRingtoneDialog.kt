package org.fossify.phone.ringtones

import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.viewBinding
import org.fossify.phone.R
import org.fossify.phone.databinding.DialogContactRingtoneBinding

/** Contact audio tools are opened directly from Fossify contacts and favorites. */
internal class ContactRingtoneDialog(
    private val controller: RingtoneController,
    private val contact: AddressBookContact
) {
    private enum class Preview { RINGTONE, RECORDING }

    private val model get() = controller.model
    private val audio get() = controller.audio
    private val binding by controller.host.viewBinding(DialogContactRingtoneBinding::inflate)
    private var preview: Preview? = null
    private var dialog: AlertDialog? = null
    private var uploadId: String? = null
    private var preparingUpload = false
    private var uploadFailed = false

    init {
        binding.ringtoneName.text = contact.ringtoneName
        binding.record.setOnClickListener { requestRecording() }
        binding.playRingtone.setOnClickListener {
            togglePreview(Preview.RINGTONE) { audio.playContact(contact) }
        }
        binding.playRecording.setOnClickListener {
            togglePreview(Preview.RECORDING) {
                val file = model.savedRecordingFile(contact)
                if (file.isFile) {
                    audio.play(Uri.fromFile(file), controller.getString(R.string.contact_audio_preview_description, contact.name))
                } else {
                    model.status.value = controller.getString(R.string.contact_audio_missing)
                }
            }
        }
        binding.upload.setOnClickListener {
            val previous = model.items.value.orEmpty().firstOrNull { it.id == uploadId }
            if (previous?.stage == "failed") {
                model.retry(previous)
                preparingUpload = true
            } else {
                preparingUpload = true
                uploadFailed = false
                uploadId = null
                model.uploadRecording { id ->
                    uploadId = id
                    preparingUpload = false
                    uploadFailed = id == null
                    refresh()
                }
            }
            refresh()
        }
    }

    private fun togglePreview(target: Preview, play: () -> Unit) {
        val stop = preview == target && audio.isActive
        controller.stopAudio()
        if (stop) {
            model.status.value = controller.getString(R.string.contact_audio_stopped)
        } else {
            preview = target
            play()
        }
        refresh()
    }

    fun show() {
        val builder = controller.host.getAlertDialogBuilder().setPositiveButton(R.string.ok, null)
        controller.host.setupDialogStuff(binding.root, builder, titleText = contact.name) {
            dialog = it
            it.setOnDismissListener {
                controller.stopAudio()
                model.stopRecording()
            }
            refresh()
        }
    }

    fun dismiss() = dialog?.dismiss()

    fun refresh() {
        val recording = model.recording.value == true
        val stopping = model.stoppingRecording.value == true
        val hasRecording = model.savedRecordingFile(contact).isFile
        val signedIn = FirebaseAuth.getInstance().currentUser != null
        val ringtonePlaying = audio.isActive && preview == Preview.RINGTONE
        val recordingPlaying = audio.isActive && preview == Preview.RECORDING
        if (recording) {
            uploadId = null
            uploadFailed = false
        }
        val uploadItem = model.items.value.orEmpty().firstOrNull { it.id == uploadId }
        if (preparingUpload && uploadItem?.stage == "uploading") preparingUpload = false
        val uploading = preparingUpload || (uploadId != null && uploadItem == null) || uploadItem?.stage == "uploading"
        val uploaded = uploadItem?.stage == "uploaded"
        val failed = uploadFailed || uploadItem?.stage == "failed"
        binding.apply {
            uploadProgress.visibility = if (uploading) View.VISIBLE else View.GONE
            upload.setIconResource(when {
                uploaded -> org.fossify.commons.R.drawable.ic_check_vector
                failed -> android.R.drawable.ic_dialog_alert
                else -> 0
            })
            upload.contentDescription = controller.getString(when {
                uploading -> R.string.contact_audio_upload_in_progress
                uploaded -> R.string.contact_audio_upload_complete
                failed -> R.string.contact_audio_upload_failed
                else -> R.string.contact_audio_upload
            })
            record.setText(when {
                stopping -> R.string.contact_audio_saving
                recording -> R.string.contact_audio_stop_recording
                hasRecording -> R.string.contact_audio_record_again
                else -> R.string.contact_audio_record
            })
            val summary = when {
                stopping -> R.string.contact_audio_saving
                recording -> R.string.contact_audio_recording_hint
                !hasRecording -> R.string.contact_audio_no_sample
                !signedIn -> R.string.contact_audio_upload_sign_in
                else -> null
            }
            recordingSummary.visibility = if (summary == null) View.GONE else View.VISIBLE
            summary?.let(recordingSummary::setText)
            playRingtone.setText(if (ringtonePlaying) R.string.contact_audio_stop_ringtone else R.string.contact_audio_play_ringtone)
            playRecording.setText(if (recordingPlaying) R.string.contact_audio_stop_playback else R.string.contact_audio_play_recording)
            record.isEnabled = !stopping && !uploading
            playRingtone.isEnabled = !recording && !stopping
            playRecording.isEnabled = hasRecording && !recording && !stopping
            upload.isEnabled = hasRecording && !recording && !stopping && signedIn && !uploading
            updateActionColor(playRingtone, ringtonePlaying)
            updateActionColor(playRecording, recordingPlaying)
            updateActionColor(record, recording)
            updateActionColor(upload, uploaded)
            upload.iconTint = upload.textColors
            uploadProgress.indeterminateTintList = ColorStateList.valueOf(controller.getProperPrimaryColor())
        }
    }

    private fun updateActionColor(button: MaterialButton, active: Boolean = false) {
        val textColor = controller.getProperTextColor()
        val primaryColor = controller.getProperPrimaryColor()
        button.isSelected = active
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 97),
                if (active) primaryColor else textColor)
        ))
    }

    private fun requestRecording() {
        controller.stopAudio()
        if (model.recording.value == true) {
            model.stopRecording()
            return
        }
        if (model.recordingFile(contact).exists()) {
            ConfirmationDialog(
                activity = controller.host,
                message = controller.getString(R.string.contact_audio_replace, contact.name),
                positive = R.string.contact_audio_confirm_record,
                negative = R.string.cancel
            ) {
                controller.runAction(ScreenAction.RECORD)
            }
        } else {
            controller.runAction(ScreenAction.RECORD)
        }
    }
}
