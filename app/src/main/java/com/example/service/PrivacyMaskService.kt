package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.audio.MaskState
import com.example.audio.PrivacyMaskPlayer
import com.example.core.GuardPreferences

/**
 * Dedicated Foreground Service for the continuous privacy masking audio layer.
 *
 * Operational Contract:
 * - Runs independently of MainActivity and VolumeGuardAccessibilityService.
 * - Continuous background audio playback via [PrivacyMaskPlayer] (static looped AudioTrack).
 * - Declared with foregroundServiceType="mediaPlayback".
 * - Promoted to foreground with low-importance, non-intrusive notification.
 * - Monitored by a 200 ms watchdog checking AudioTrack health and persistent Guard state.
 * - Automatically stops and releases resources when Guard is OFF.
 */
class PrivacyMaskService : Service() {

    private lateinit var player: PrivacyMaskPlayer
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            // Re-verify persisted Guard preference
            val isGuardEnabled = GuardPreferences.getInstance(this@PrivacyMaskService).isGuardEnabled
            if (!isGuardEnabled) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Watchdog detected Guard OFF in preferences -> stopping service")
                }
                stopMaskingAndSelf()
                return
            }

            // Check track playback health and recover if stalled
            player.checkHealthAndRecover()

            if (isServiceRunning) {
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onCreate()")
        }
        player = PrivacyMaskPlayer(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val isGuardEnabled = GuardPreferences.getInstance(this).isGuardEnabled

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStartCommand(action=$action, guardEnabled=$isGuardEnabled, startId=$startId)")
        }

        if (action == ACTION_STOP || !isGuardEnabled) {
            stopMaskingAndSelf()
            return START_NOT_STICKY
        }

        // Action is START and Guard is ON: promote to foreground and begin playback
        promoteToForeground()
        isServiceRunning = true

        val started = player.start()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Mask player start result: $started (state=${player.state})")
        }

        startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onDestroy()")
        }
        isServiceRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        player.release()
    }

    private fun promoteToForeground() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "startForeground failed; continuing service without foreground guarantee", e)
            }
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopMaskingAndSelf() {
        isServiceRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        player.stop()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error stopping foreground", e)
            }
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Volume Guard Privacy Mask",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active privacy audio masking while Guard is ON"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Volume Guard Privacy Mask")
            .setContentText("Active while Guard is ON")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PrivacyMaskService"
        private const val CHANNEL_ID = "channel_privacy_mask"
        private const val NOTIFICATION_ID = 2001
        private const val WATCHDOG_INTERVAL_MS = 200L

        const val ACTION_START = "com.example.action.START_MASK"
        const val ACTION_STOP = "com.example.action.STOP_MASK"

        /**
         * Starts the dedicated privacy mask service.
         */
        fun start(context: Context) {
            val intent = Intent(context, PrivacyMaskService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Failed to start PrivacyMaskService foreground service", e)
                }
            }
        }

        /**
         * Stops the dedicated privacy mask service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, PrivacyMaskService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                try {
                    context.stopService(intent)
                } catch (_: Throwable) {}
            }
        }
    }
}
