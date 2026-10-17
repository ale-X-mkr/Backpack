package com.backpack.app.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.backpack.app.Prefs
import com.backpack.app.R
import com.backpack.app.Settings

/**
 * 1.9.0 (Alex): an optional lock (Settings > Advanced) so nobody else sees
 * your private things: My places (Go Home too), recent searches, My trips
 * and Settings ask for your fingerprint / face / phone PIN, at least once
 * per session (again after 5 minutes in the background). The map,
 * navigation, alerts and sights never wait for it. Android's own unlock
 * screen (androidx.biometric): no Google services.
 */
object AppLock {

    private const val RELOCK_MS = 5 * 60_000L
    private var unlocked = false
    private var backgroundSinceMs = 0L

    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30)
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun enabled(c: Context) = Prefs.settings(c).getBoolean(Settings.KEY_APP_LOCK, false)

    /** True when nothing needs unlocking (lock off, or unlocked this session). */
    fun isOpen(c: Context) = !enabled(c) || unlocked

    /** The phone can lock (a fingerprint / face enrolled, or a screen lock). */
    fun available(c: Context): Boolean {
        val m = BiometricManager.from(c)
        if (m.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) return true
        // Android 10 and older: the screen lock alone, through the same screen.
        return Build.VERSION.SDK_INT < 30 && (c.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isDeviceSecure == true
    }

    fun onBackground() { backgroundSinceMs = System.currentTimeMillis() }

    fun onForeground() {
        if (backgroundSinceMs > 0L && System.currentTimeMillis() - backgroundSinceMs > RELOCK_MS) unlocked = false
        backgroundSinceMs = 0L
    }

    fun relock() { unlocked = false }

    fun markUnlocked() { unlocked = true }

    /** Runs [then] at once when open, else after a successful unlock (nothing on cancel). */
    fun unlock(a: FragmentActivity, then: () -> Unit) {
        if (isOpen(a)) { then(); return }
        if (!available(a)) { unlocked = true; then(); return } // the phone has no lock at all any more
        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked = true
                then()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(a.getString(R.string.lock_title))
            .setSubtitle(a.getString(R.string.lock_subtitle))
        if (Build.VERSION.SDK_INT >= 30) info.setAllowedAuthenticators(authenticators)
        else {
            @Suppress("DEPRECATION")
            info.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(info.build())
    }
}
