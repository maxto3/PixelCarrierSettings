package me.ikirby.pixelutils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.CarrierConfigManager
import android.util.Log

/**
 * Receives system broadcasts to re-apply persisted carrier config overrides.
 *
 * Instead of doing work itself, it delegates to RestorationService to avoid being
 * killed by the system during long-running tasks.
 */
class CarrierConfigReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarrierConfigReceiver"
        private const val ACTION_DELAYED_RESTORE = "me.ikirby.pixelutils.DELAYED_RESTORE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received broadcast: $action")

        when (action) {
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                val subId = intent.getIntExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, -1)
                if (subId != -1) {
                    Log.i(TAG, "Config changed for subId $subId - starting service")
                    RestorationService.start(context, listOf(subId))
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val subIds = CarrierConfigPersistence.getSubIdsWithOverrides(context)
                if (subIds.isNotEmpty()) {
                    Log.i(TAG, "Boot event ($action) - starting initial restoration")
                    RestorationService.start(context, subIds)

                    // Schedule Stage 2 (60s delay) via AlarmManager to survive process death
                    scheduleDelayedRestore(context, subIds)
                }
            }
            ACTION_DELAYED_RESTORE -> {
                val subIds = CarrierConfigPersistence.getSubIdsWithOverrides(context)
                if (subIds.isNotEmpty()) {
                    Log.i(TAG, "Stage 2: Delayed restoration triggered via alarm")
                    RestorationService.start(context, subIds)
                }
            }
        }
    }

    private fun scheduleDelayedRestore(context: Context, subIds: List<Int>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, CarrierConfigReceiver::class.java).apply {
            action = ACTION_DELAYED_RESTORE
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + 60000
        try {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            Log.i(TAG, "Scheduled Stage 2 restoration in 60 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule delayed restore", e)
        }
    }
}
