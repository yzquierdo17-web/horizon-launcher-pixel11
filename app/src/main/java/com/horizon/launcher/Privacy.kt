package com.horizon.launcher

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Asks for fingerprint, face or the screen lock (PIN / pattern / password) before showing
 * something private. If the phone has no screen lock at all, there's nothing to check against,
 * so it lets you in and says so.
 */
fun unlockWithBiometrics(context: Context, title: String, onSuccess: () -> Unit) {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isDeviceSecure) {
        android.widget.Toast.makeText(context, "Set a screen lock to protect hidden apps", android.widget.Toast.LENGTH_LONG).show()
        onSuccess()
        return
    }
    val builder = BiometricPrompt.Builder(context)
        .setTitle(title)
        .setSubtitle("Use your fingerprint, face or screen lock")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    runCatching {
        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "Couldn't ask for your fingerprint or screen lock", android.widget.Toast.LENGTH_SHORT).show()
    }
}
