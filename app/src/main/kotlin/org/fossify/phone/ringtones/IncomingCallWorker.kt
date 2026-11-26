package org.fossify.phone.ringtones

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Telecom supplies one completed incoming call; history is saved before any network access. */
class IncomingCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.success()
        val call = IncomingCall(id, inputData.getString("number").orEmpty(), inputData.getLong("received", 0))
        try {
            val calls = IncomingCallHistory.get(applicationContext)
            val library = RingtoneLibrary.get(applicationContext)
            if (calls.recordCompleted(call)) {
                Log.i("GooferCall", "Incoming call saved: $id")
                val auth = FirebaseAuth.getInstance()
                val user = auth.currentUser
                val voices = if (user != null) {
                    val api = RingtoneApi(idToken = {
                        check(auth.currentUser?.uid == user.uid) { "Account changed" }
                        user.getIdToken(false).await().token ?: throw java.io.IOException("Sign in again.")
                    })
                    val response = api.voicesJson()
                    if (auth.currentUser?.uid != user.uid) return@withContext Result.retry()
                    VoiceCache(File(applicationContext.noBackupFilesDir, "voices")).write(user.uid, response)
                    api.parseVoices(response)
                } else emptyList()
                calls.recordCompleted(call) { saved, contact ->
                    planCallGeneration(saved, contact?.uri.orEmpty(), contact?.name.orEmpty(), user?.uid, voices)
                }
            }
            for (snapshot in calls.pending()) {
                val item = library.putIfAbsent(snapshot)
                RingtoneWork.enqueue(applicationContext, item)?.result?.get(5, TimeUnit.SECONDS)
                calls.markDispatched(snapshot.id)
                Log.i("GooferCall", "Automatic ringtone dispatched: ${snapshot.id}")
            }
            Result.success()
        } catch (_: android.database.sqlite.SQLiteException) {
            Result.retry()
        } catch (_: java.io.IOException) {
            Result.retry()
        } catch (_: com.google.firebase.FirebaseNetworkException) {
            Result.retry()
        } catch (_: java.util.concurrent.TimeoutException) {
            Result.retry()
        } catch (_: java.util.concurrent.ExecutionException) {
            Result.retry()
        }
    }
}
