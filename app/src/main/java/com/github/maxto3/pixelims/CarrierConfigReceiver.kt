package com.github.maxto3.pixelims

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.CarrierConfigManager
import android.util.Log

/**
 * Receives system broadcasts and delegates carrier config restoration
 * to [RestorationService] to avoid being killed by the system.
 *
 * On boot events, restoration is attempted immediately and then again
 * after a 60-second delay to handle complex boot sequences (SIM PIN,
 * system password) where SystemUI / IMS may not be ready yet.
 */
class CarrierConfigReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarrierConfigReceiver"
        private const val DELAYED_RESTORE_MS = 60_000L
        private const val DEBOUNCE_WINDOW_MS = 10_000L

        // Track last restoration time per subId to prevent rapid re-triggering.
        // BroadcastReceiver.onReceive() always runs on the main thread, so a regular Map is safe.
        private val lastRestoreTimes = mutableMapOf<Int, Long>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                val subId = intent.getIntExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, -1)
                if (subId != -1) {
                    val now = System.currentTimeMillis()
                    val lastTime = lastRestoreTimes[subId] ?: 0L
                    if (now - lastTime < DEBOUNCE_WINDOW_MS) {
                        Log.d(TAG, "Skipping config changed for subId $subId - within debounce window (${now - lastTime}ms < ${DEBOUNCE_WINDOW_MS}ms)")
                        return
                    }
                    lastRestoreTimes[subId] = now
                    Log.d(TAG, "Config changed for subId $subId - starting service")
                    RestorationService.start(context, listOf(subId))
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val subIds = CarrierConfigPersistence.getSubIdsWithOverrides(context)
                if (subIds.isNotEmpty()) {
                    Log.d(TAG, "Boot event ($action) - starting initial restoration")
                    RestorationService.start(context, subIds)

                    // Stage 2: delay 60s and retry in case SystemUI / IMS wasn't ready yet.
                    // Using Handler.postDelayed is preferred over AlarmManager here because
                    // the boot receiver holds a wakelock and the process is already alive.
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Stage 2: Delayed restoration triggered ($DELAYED_RESTORE_MS ms)")
                        RestorationService.start(context, subIds)
                    }, DELAYED_RESTORE_MS)
                }
            }
        }
    }
}
