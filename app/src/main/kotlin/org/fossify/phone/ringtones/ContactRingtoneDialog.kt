package org.fossify.phone.ringtones

import android.net.Uri
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.fossify.phone.R

/** Contact audio tools are opened directly from Fossify contacts and favorites. */
internal class ContactRingtoneDialog(
    private val controller: RingtoneController,
    private val contact: AddressBookContact
) {
    private val model get() = controller.model
    private val audio get() = controller.audio
    private val body = controller.column(16)
    private val status = controller.text("", 14)
    private val record = controller.button("Record voice sample", ::requestRecording)
    private val listenRingtone = controller.secondaryButton("Listen to current ringtone") {
        controller.stopAudio()
        audio.playContact(contact)
    }
    private val listenRecording = controller.secondaryButton("Listen to voice recording") {
        controller.stopAudio()
        val file = model.savedRecordingFile(contact)
        if (file.isFile) audio.play(Uri.fromFile(file), "recording for ${contact.name}")
        else model.status.value = "Record a voice sample first, or refresh your cloud voices."
    }
    private val upload = controller.secondaryButton("Upload voice recording") { model.uploadRecording() }
    private val generate = controller.button("Generate ringtone for ${contact.name}") {
        if (model.loaded.value == true) model.selectGenerationContact(contact)
        else Toast.makeText(controller.host, "Sign in to generate a ringtone.", Toast.LENGTH_LONG).show()
        dismiss()
        controller.navigate(R.id.section_voices)
    }
    private val dialog = AlertDialog.Builder(controller.host)
        .setTitle(contact.name)
        .setView(ScrollView(controller.host).apply { addView(body) })
        .setPositiveButton("Close", null)
        .create()

    init {
        body.addView(controller.text(contact.ringtoneName, 14))
        body.addView(status)
        body.addView(listenRingtone)
        body.addView(record)
        body.addView(listenRecording)
        body.addView(controller.secondaryButton("Stop audio") { controller.stopAudio() })
        body.addView(upload)
        body.addView(generate)
        dialog.setOnDismissListener { controller.stopAudio(); model.stopRecording() }
    }

    fun show() {
        refresh()
        dialog.showPhoneDialog()
    }

    fun dismiss() = dialog.dismiss()

    fun refresh() {
        val recording = model.recording.value == true
        val stopping = model.stoppingRecording.value == true
        status.text = model.status.value
        record.text = if (recording) "Stop and save recording" else "Record voice sample"
        record.isEnabled = !stopping
        listenRingtone.isEnabled = !recording && !stopping
        listenRecording.isEnabled = !recording && !stopping
        upload.isEnabled = !recording && !stopping &&
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
        generate.isEnabled = !recording && !stopping
    }

    private fun requestRecording() {
        controller.stopAudio()
        if (model.recording.value == true) {
            model.stopRecording()
            return
        }
        val file = model.recordingFile(contact)
        if (file.exists()) AlertDialog.Builder(controller.host)
            .setMessage("Replace the saved voice recording for ${contact.name}?")
            .setPositiveButton("Record") { _, _ -> controller.runAction(ScreenAction.RECORD) }
            .setNegativeButton(R.string.cancel, null).showPhoneDialog()
        else controller.runAction(ScreenAction.RECORD)
    }
}
