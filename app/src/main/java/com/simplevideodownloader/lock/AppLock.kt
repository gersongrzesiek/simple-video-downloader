package com.simplevideodownloader.lock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.preference.PreferenceManager

/**
 * Bloqueo con huella (se activa en Ajustes). Se pide al abrir la app y cada vez que se vuelve a ella después
 * de haberla dejado en segundo plano. Si la huella falla se puede usar el bloqueo de pantalla del teléfono.
 */
object AppLock {
    const val PREF_ENABLED = "lock_biometric"
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    @Volatile var unlocked = false
    private var started = 0

    fun isEnabled(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, false)

    /** Si el teléfono tiene huella (o un bloqueo de pantalla) configurado para poder usarlo. */
    fun isAvailable(context: Context) =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (!unlocked && activity !is LockActivity && isEnabled(activity)) {
                    activity.startActivity(Intent(activity, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                // Toda la app pasó a segundo plano (y no es solo un giro de pantalla): se vuelve a bloquear.
                if (started <= 0 && !activity.isChangingConfigurations && activity !is LockActivity) unlocked = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
