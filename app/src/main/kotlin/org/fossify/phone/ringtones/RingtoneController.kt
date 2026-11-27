package org.fossify.phone.ringtones

import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.databinding.ContentRingtonesBinding
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getAlertDialogBuilder
import java.io.File
import java.lang.ref.WeakReference

internal enum class ScreenAction { CONTACTS, RECIPIENT, RECORD, GENERATE, ASSIGN, ASSIGNMENT_CONTACT, CALL_HISTORY }

/** Ringtone actions share Fossify's activity, navigation, permissions and lifecycle. */
class RingtoneController(val host: MainActivity, savedState: Bundle?) :
    ContextWrapper(host), LifecycleOwner by host, DefaultLifecycleObserver {
    internal val model = ViewModelProvider(host)[RingtoneViewModel::class.java]
    internal val audio = AudioPreview(host, { model.status.value = it }, { contactDialog?.refresh() })
    private lateinit var content: LinearLayout
    private var rootView: View? = null
    private var signInButton: Button? = null
    private var navigation: TabLayout? = null
    private var section = savedState?.getInt("ringtoneSection", R.id.section_voices) ?: R.id.section_voices
    internal var previewFile: File? = null
    internal var previewTask: Job? = null
    internal var currentScreen: TabScreen? = null
        private set
    private val generateTab by lazy { GenerateScreen(this, savedState?.getString("ringtoneVoiceQuery").orEmpty()) }
    private val historyTab by lazy { HistoryScreen(this) }
    private var contactDialog: ContactRingtoneDialog? = null
    private val statusViews = mutableListOf<WeakReference<TextView>>()
    private var pendingPermission = savedState?.getString("ringtonePermission")
    private var pendingAction = savedState?.getString("ringtoneAction")?.let(ScreenAction::valueOf)
    private var pendingItem = savedState?.getString("ringtoneItem")
    private val auth get() = FirebaseAuth.getInstance()
    internal fun isDefaultPhone() =
        getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage == packageName

    private val phoneRoleLauncher = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isDefaultPhone()) runAction(ScreenAction.CALL_HISTORY)
        else model.status.value = "Set Phone as your default phone app to enable automatic call ringtones."
    }
    private val permissionLauncher = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = pendingPermission
        val action = pendingAction
        val id = pendingItem
        pendingPermission = null; pendingAction = null; pendingItem = null
        if (granted && action != null) runAction(action, id)
        else {
            model.status.value = "Access was denied. Your ringtones are still saved."
            if (permission != null && !host.shouldShowRequestPermissionRationale(permission)) {
                host.getAlertDialogBuilder().setMessage("You can enable access in app settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }.setNegativeButton("Later", null).showPhoneDialog()
            }
        }
    }

    init {
        host.lifecycle.addObserver(this)
        listOf(model.contactSelection, model.draft, model.voices, model.voiceRefreshError, model.refreshingVoices,
            model.contacts, model.items, model.callHistory, model.loaded, model.saving, model.recording,
            model.stoppingRecording).forEach { state ->
            state.observe(host) { currentScreen?.refresh?.invoke(); contactDialog?.refresh() }
        }
        model.loaded.observe(host) { updateAccount() }
        model.status.observe(host) { value ->
            contactDialog?.refresh()
            statusViews.removeAll { it.get() == null }
            statusViews.forEach { it.get()?.text = value }
        }
    }

    fun createView(): View {
        val binding = ContentRingtonesBinding.inflate(host.layoutInflater)
        val root = binding.root
        rootView = root
        signInButton = binding.account.apply { setOnClickListener { signIn() } }
        content = binding.content
        navigation = binding.navigation.apply {
            addTab(newTab().setText(R.string.ringtone_generate))
            addTab(newTab().setText(R.string.ringtone_history))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showSection(if (tab.position == 0) R.id.section_voices else R.id.section_ringtones)
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        navigation?.getTabAt(if (section == R.id.section_ringtones) 1 else 0)?.select()
        showSection(section)
        updateAccount()
        updateColors()
        return root
    }

    private fun updateAccount() {
        val user = auth.currentUser
        signInButton?.text = if (user == null) getString(R.string.ringtone_sign_in) else user.email ?: getString(R.string.ringtone_connected)
        signInButton?.isEnabled = true
    }

    internal fun navigate(value: Int) {
        host.showRingtones()
        navigation?.getTabAt(if (value == R.id.section_ringtones) 1 else 0)?.select()
        showSection(value)
    }

    internal fun requestGeneration(id: String? = null) = runAction(ScreenAction.GENERATE, id)

    private fun showSection(value: Int) {
        section = value
        if (!::content.isInitialized) return
        stopAudio()
        currentScreen = if (value == R.id.section_ringtones) historyTab else generateTab
        content.removeAllViews()
        content.addView(currentScreen!!.create(), LinearLayout.LayoutParams(-1, -1))
        applyRingtoneTheme(content)
        if (value == R.id.section_voices) model.retryVoiceRefresh()
    }

    fun showContact(lookupUri: Uri) {
        lifecycleScope.launch {
            try {
                val rows = model.refreshContacts()
                val resolved = withContext(Dispatchers.IO) {
                    android.provider.ContactsContract.Contacts.getLookupUri(contentResolver, lookupUri)
                }
                val contact = rows.firstOrNull {
                    it.lookupUri == lookupUri || it.lookupUri == resolved
                } ?: return@launch
                contactDialog?.dismiss()
                model.selectContact(contact)
                contactDialog = ContactRingtoneDialog(this@RingtoneController, contact).also { it.show() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.status.value = "Cannot open contact ringtones: ${e.message}"
                android.widget.Toast.makeText(host, model.status.value, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    internal fun runAction(action: ScreenAction, id: String? = null) {
        if (action == ScreenAction.CALL_HISTORY && !isDefaultPhone()) {
            val intent = if (Build.VERSION.SDK_INT >= 29) {
                getSystemService(android.app.role.RoleManager::class.java)
                    .createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
            } else Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            phoneRoleLauncher.launch(intent)
            return
        }
        val permissions = when (action) {
            ScreenAction.CALL_HISTORY -> listOfNotNull(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { Build.VERSION.SDK_INT < 29 })
            ScreenAction.RECORD -> listOf(Manifest.permission.RECORD_AUDIO)
            ScreenAction.GENERATE, ScreenAction.ASSIGN -> listOfNotNull(
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { Build.VERSION.SDK_INT < 29 })
            else -> listOf(Manifest.permission.READ_CONTACTS)
        }
        val permission = permissions.firstOrNull { !hasPermission(it) }
        if (permission == null) { perform(action, id); return }
        val launch = {
            pendingPermission = permission; pendingAction = action; pendingItem = id
            permissionLauncher.launch(permission)
        }
        if (host.shouldShowRequestPermissionRationale(permission)) {
            val reason = when (permission) {
                Manifest.permission.READ_PHONE_STATE -> "Phone access lets History record incoming calls after they end."
                Manifest.permission.READ_CALL_LOG -> "Call log access lets History identify the caller. Your installer must allow this permission."
                Manifest.permission.READ_CONTACTS -> "Contacts access lets you choose a person and resolve their saved contact."
                Manifest.permission.WRITE_CONTACTS -> "Allow contact updates to set this person's ringtone."
                Manifest.permission.RECORD_AUDIO -> "Microphone access is used only while recording a voice sample."
                else -> "Storage access is needed on this Android version to publish the ringtone."
            }
            host.getAlertDialogBuilder().setMessage(reason).setPositiveButton("Continue") { _, _ -> launch() }
                .setNegativeButton("Cancel", null).showPhoneDialog()
        } else launch()
    }

    private fun perform(action: ScreenAction, id: String?, restored: RingtoneItem? = null) {
        val item = restored ?: model.items.value.orEmpty().firstOrNull { it.id == id }
        if (id != null && item == null) {
            // Permission results can arrive before the library observer has restored its rows.
            val uid = auth.currentUser?.uid ?: return
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { RingtoneLibrary.get(this@RingtoneController).get(uid, id) }
                if (saved != null && auth.currentUser?.uid == uid) perform(action, id, saved)
                else model.status.value = "Open this ringtone from its original Google account to continue."
            }
            return
        }
        when (action) {
            ScreenAction.CALL_HISTORY -> { model.status.value = "Automatic call ringtones enabled. Incoming calls use the caller’s newest uploaded clip, or a random famous person clip."; currentScreen?.refresh?.invoke() }
            ScreenAction.CONTACTS -> model.loadContacts()
            ScreenAction.RECIPIENT -> generateTab.chooseGenerationContact()
            ScreenAction.GENERATE -> {
                stopAudio()
                if (id == null) model.generate() else item?.let { model.generateAgain(it) }
                navigate(R.id.section_ringtones)
            }
            ScreenAction.RECORD -> model.startRecording()
            ScreenAction.ASSIGNMENT_CONTACT -> item?.let(historyTab::chooseAssignmentContact)
            ScreenAction.ASSIGN -> item?.let { model.assign(it) }
        }
    }

    private fun signIn() {
        if (auth.currentUser != null) {
            host.getAlertDialogBuilder().setTitle("Google account").setMessage(auth.currentUser?.email)
                .setPositiveButton("Sign out") { _, _ ->
                    stopAudio()
                    auth.signOut()
                    updateAccount()
                    lifecycleScope.launch {
                        runCatching { CredentialManager.create(this@RingtoneController).clearCredentialState(ClearCredentialStateRequest()) }
                    }
                }.setNegativeButton("Close", null).showPhoneDialog()
            return
        }
        signInButton?.isEnabled = false
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(getString(R.string.default_web_client_id)).build()
                val credential = CredentialManager.create(host).getCredential(host,
                    GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
                check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            } catch (_: NoCredentialException) {
                model.status.value = "No Google account is available. Add one in device settings, then sign in again."
            } catch (_: GetCredentialCancellationException) {
                // Account picker dismissed.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.status.value = "Sign-in failed: ${e.message}"
            } finally { updateAccount() }
        }
    }


    internal fun stopAudio() {
        previewTask?.cancel(); previewTask = null
        audio.stop()
        previewFile?.delete(); previewFile = null
    }

    internal fun statusHeader(header: LinearLayout) {
        val status = text(model.status.value.orEmpty(), 13).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusViews.add(WeakReference(status))
        header.addView(status)
    }

    fun updateColors() {
        rootView?.let {
            it.setBackgroundColor(getProperBackgroundColor())
            applyRingtoneTheme(it)
        }
        currentScreen?.refresh?.invoke()
    }

    fun onPageChanged(visible: Boolean) {
        if (!visible) {
            stopAudio()
            model.stopRecording()
        }
    }

    fun search(query: String) {
        generateTab.voiceQuery = query
        historyTab.query = query
        currentScreen?.refresh?.invoke()
    }

    fun saveState(state: Bundle) {
        state.putInt("ringtoneSection", section)
        state.putString("ringtoneVoiceQuery", generateTab.voiceQuery)
        state.putString("ringtonePermission", pendingPermission)
        state.putString("ringtoneAction", pendingAction?.name)
        state.putString("ringtoneItem", pendingItem)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (hasPermission(Manifest.permission.READ_CONTACTS)) model.loadContacts()
        currentScreen?.refresh?.invoke()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopAudio()
        model.stopRecording()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        contactDialog?.dismiss()
        stopAudio()
        statusViews.clear()
        host.lifecycle.removeObserver(this)
    }
}
