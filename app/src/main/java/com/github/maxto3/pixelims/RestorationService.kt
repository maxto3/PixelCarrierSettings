package com.github.maxto3.pixelims

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service to handle carrier config restoration.
 * Using a foreground Service is required on Android 8.0+ for long-running tasks
 * that are started from a BroadcastReceiver, and prevents the system from killing
 * the process during restoration.
 */
class RestorationService : Service() {

    companion object {
        private const val TAG = "RestorationService"
        private const val EXTRA_SUB_IDS = "subIds"
        private const val SERVICE_BIND_TIMEOUT_SECONDS = 25L
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "carrier_config_restoration"
        private const val KEY_VOIMS_OPT_IN_STATUS = 68
        private const val PROVISIONING_VALUE_ENABLED = 1
        private const val WFC_MODE_WIFI_ONLY = 0
        private const val WFC_MODE_CELLULAR_PREFERRED = 1
        private const val WFC_MODE_WIFI_PREFERRED = 2

        fun start(context: Context, subIds: List<Int>) {
            val intent = Intent(context, RestorationService::class.java).apply {
                putIntegerArrayListExtra(EXTRA_SUB_IDS, ArrayList(subIds))
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val subIds = intent?.getIntegerArrayListExtra(EXTRA_SUB_IDS) ?: emptyList<Int>()
        if (subIds.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Immediately promote to foreground service to avoid being killed
        startForeground(NOTIFICATION_ID, buildNotification())

        Log.d(TAG, "Starting restoration for subIds: $subIds")

        executor.execute {
            try {
                performRestoration(subIds)
            } finally {
                Log.d(TAG, "Restoration task finished")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun performRestoration(subIds: List<Int>) {
        // Bind to the root service on the main thread (required by libsu)
        val latch = CountDownLatch(1)
        var carrierService: ICarrierConfigRootService? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.d(TAG, "CarrierConfigRootService connected!")
                carrierService = ICarrierConfigRootService.Stub.asInterface(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                carrierService = null
            }
        }

        val handler = Handler(Looper.getMainLooper())
        val serviceIntent = Intent(this, CarrierConfigRootService::class.java)

        handler.post {
            try {
                Log.d(TAG, "Binding to RootService...")
                RootService.bind(serviceIntent, serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Bind failed", e)
                latch.countDown()
            }
        }

        if (latch.await(SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val svc = carrierService
            if (svc != null) {
                for (subId in subIds) {
                    applyToSubId(svc, subId)
                    ensureImsHealth(svc, subId)
                }
            } else {
                Log.w(TAG, "CarrierConfigRootService was null after binding")
            }
            handler.post {
                try {
                    RootService.unbind(serviceConnection)
                } catch (e: Exception) {
                    Log.w(TAG, "Unbind failed (may be expected)", e)
                }
            }
        } else {
            Log.w(TAG, "Timed out waiting for root service binding (root may not be available)")
        }
    }

    private fun applyToSubId(svc: ICarrierConfigRootService, subId: Int) {
        val overrides = CarrierConfigPersistence.loadOverrides(this, subId) ?: return
        try {
            // Primary check: skip if the same overrides were already applied in this
            // process lifetime (checksum-based, works regardless of getCarrierConfig availability).
            if (!CarrierConfigPersistence.shouldRestore(subId, overrides)) {
                Log.d(TAG, "Overrides unchanged for subId=$subId - skipping")
                return
            }

            // Secondary check: try to compare with current system config (best-effort).
            // This catches cases where the system already has the desired values even
            // though our checksum says we haven't applied them yet.
            val currentConfig = try {
                svc.getCarrierConfig(subId)
            } catch (e: Exception) {
                Log.d(TAG, "getCarrierConfig unavailable for subId=$subId - relying on checksum only")
                null
            }

            if (currentConfig != null) {
                var allMatch = true
                for (key in overrides.keySet()) {
                    if (!CarrierConfigPersistence.bundleValuesEqual(overrides, currentConfig, key)) {
                        Log.d(TAG, "Key '$key' differs from current config for subId=$subId")
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    Log.d(TAG, "All overrides already match current config for subId=$subId - skipping")
                    CarrierConfigPersistence.markAsRestored(subId, overrides)
                    return
                }
            }

            Log.d(TAG, "Applying overrides for subId $subId")
            val success = svc.overrideCarrierConfig(subId, overrides, false)
            if (success) {
                CarrierConfigPersistence.markAsRestored(subId, overrides)
                Log.d(TAG, "Success. Resetting IMS for $subId")
                try {
                    svc.resetIms(subId)
                } catch (e: Exception) {
                    Log.w(TAG, "IMS reset failed for $subId (non-fatal)", e)
                }
            } else {
                Log.w(TAG, "Failed to apply overrides for subId=$subId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying to $subId", e)
        }
    }

    /**
     * Keeps the IMS-critical state healthy on boot: VoIMS opt-in (key 68), the APN split
     * and the Wi-Fi-preferred fallback guard.
     */
    private fun ensureImsHealth(svc: ICarrierConfigRootService, subId: Int) {
        try {
            if (svc.getImsProvisioningInt(subId, KEY_VOIMS_OPT_IN_STATUS)
                != PROVISIONING_VALUE_ENABLED
            ) {
                svc.setImsProvisioningInt(
                    subId,
                    KEY_VOIMS_OPT_IN_STATUS,
                    PROVISIONING_VALUE_ENABLED
                )
                Log.i(TAG, "VoIMS opt-in enabled for subId=$subId")
            }
            // voims=1 disables the carrier-default fallback of the user switch.
            svc.ensureAdvancedCalling(subId)
        } catch (e: Exception) {
            Log.w(TAG, "VoIMS opt-in check failed for subId=$subId", e)
        }

        try {
            val mode = svc.getWfcMode()
            if (mode == WFC_MODE_WIFI_PREFERRED || mode == WFC_MODE_WIFI_ONLY) {
                if (!svc.isImsRegistered(subId)) {
                    svc.setWfcMode(WFC_MODE_CELLULAR_PREFERRED)
                    Log.i(TAG, "WFC mode $mode fell back to cellular for subId=$subId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WFC guard failed for subId=$subId", e)
        }

        try {
            if (svc.sanitizeApnImsType()) {
                Log.i(TAG, "APN ims type sanitized")
            }
        } catch (e: Exception) {
            Log.w(TAG, "APN sanitize failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.restoration_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.restoration_notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // PendingIntent that opens the app when notification is tapped
        val openAppIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.restoration_notification_title))
            .setContentText(getString(R.string.restoration_notification_text))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }
}
