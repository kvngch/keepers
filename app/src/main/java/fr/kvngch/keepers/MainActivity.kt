package fr.kvngch.keepers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import fr.kvngch.keepers.ui.KeepersTheme
import fr.kvngch.keepers.ui.LockScreen
import fr.kvngch.keepers.ui.MainScreen

class MainActivity : FragmentActivity() {

    private val vm: MainViewModel by viewModels()
    private val locked = mutableStateOf(true)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Coffre-fort : pas de capture d'ecran, contenu masque dans les recents
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                DueWorker.CHANNEL, "Échéances", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        DueWorker.schedule(this)
        val startAction = intent.getStringExtra("keepers_action")
        handleShare(intent)
        setContent {
            KeepersTheme {
                if (locked.value) {
                    LockScreen(onUnlock = ::authenticate)
                } else {
                    MainScreen(vm, startAction)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Grace d'une minute : les allers-retours internes (camera, selecteur de
        // fichiers, biometrie) ne reverrouillent pas le coffre
        if (locked.value || SystemClock.elapsedRealtime() - stoppedAt > 60_000) {
            locked.value = true
            authenticate()
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            // Aucun verrou configure sur l'appareil : pas de blocage possible
            locked.value = false
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked.value = false
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Keepers verrouillé")
                .setSubtitle("Authentifiez-vous pour ouvrir le coffre")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun handleShare(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> vm.importFile(stream)
                    !text.isNullOrBlank() ->
                        vm.addNote(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "", text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )?.let { vm.importFiles(it) }
        }
    }
}
