package com.simplevideodownloader.lock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.PreferenceManager
import java.security.MessageDigest

/**
 * PIN de 4 dígitos para entrar a la app (se activa en Ajustes). Se pide al abrir la app y cada vez que se
 * vuelve a ella después de haberla dejado en segundo plano. Solo se guarda un hash del PIN.
 */
object AppLock {
    private const val PREF_PIN_HASH = "app_pin_hash"
    const val PIN_LENGTH = 4

    @Volatile var unlocked = false
    private var started = 0

    fun isEnabled(context: Context) = prefs(context).getString(PREF_PIN_HASH, null) != null

    fun check(context: Context, pin: String) = prefs(context).getString(PREF_PIN_HASH, null) == hash(pin)

    /** Guarda un PIN nuevo, o lo quita con null. */
    fun setPin(context: Context, pin: String?) {
        prefs(context).edit().apply {
            if (pin == null) remove(PREF_PIN_HASH) else putString(PREF_PIN_HASH, hash(pin))
        }.apply()
        unlocked = true
    }

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
                if (started <= 0 && !activity.isChangingConfigurations) unlocked = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("svd-pin:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
