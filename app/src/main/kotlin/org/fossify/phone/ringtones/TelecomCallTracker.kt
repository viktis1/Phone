package org.fossify.phone.ringtones

import android.content.Context
import android.telecom.Call
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.fossify.phone.extensions.getStateCompat
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One snapshot per Telecom call, including missed calls and calls waiting on another call. */
class TelecomCallTracker(context: Context) {
    private val app = context.applicationContext
    private val tracked = mutableMapOf<Call, Session>()

    private inner class Session(val call: Call) : Call.Callback() {
        val id = UUID.randomUUID().toString()
        val received = call.details.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        var incoming = call.getStateCompat() == Call.STATE_RINGING
        var number = call.details.handle?.schemeSpecificPart.orEmpty()
        var completed = false

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            number = details.handle?.schemeSpecificPart?.ifBlank { number } ?: number
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                incoming = incoming || details.callDirection == Call.Details.DIRECTION_INCOMING
            }
        }

        override fun onStateChanged(call: Call, state: Int) {
            incoming = incoming || state == Call.STATE_RINGING
            onDetailsChanged(call, call.details)
            if (state == Call.STATE_DISCONNECTED) finish()
        }

        fun finish() {
            if (!incoming || completed || call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) return
            completed = true
            val request = OneTimeWorkRequestBuilder<IncomingCallWorker>()
                .setInputData(workDataOf("id" to id, "number" to number, "received" to received))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                "incoming-call:$id", ExistingWorkPolicy.KEEP, request
            )
            Log.i("GooferCall", "Completed incoming call queued: $id")
        }
    }

    fun onCallAdded(call: Call) {
        if (tracked.containsKey(call)) return
        val session = Session(call)
        tracked[call] = session
        call.registerCallback(session)
        session.onStateChanged(call, call.getStateCompat())
    }

    fun onCallRemoved(call: Call) {
        tracked.remove(call)?.let {
            it.onDetailsChanged(call, call.details)
            it.finish()
            call.unregisterCallback(it)
        }
    }

    fun close() {
        tracked.forEach { (call, session) -> call.unregisterCallback(session) }
        tracked.clear()
    }
}
