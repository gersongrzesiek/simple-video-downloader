package com.simplevideodownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.FilterUpdateWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        AdBlocker.init(this)
        FilterUpdateWorker.schedule(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val DOWNLOAD_CHANNEL = "downloads"

        lateinit var instance: App
            private set

        /** Cliente HTTP compartido (descargas, sondeo de URLs y listas de filtros). */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
