package me.ikirby.pixelutils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Listens for SIM state changes. When the SIM becomes LOADED (e.g. after the user
 * enters the SIM PIN), immediately triggers auto-apply instead of waiting for the
 * WorkManager retry backoff.
 */
class SimStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PixelCS.SimStateRcvr"
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SIM_STATE_CHANGED) return
        val simState = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE, TelephonyManager.SIM_STATE_UNKNOWN)
        Log.d(TAG, "SIM state changed: $simState (LOADED=${TelephonyManager.SIM_STATE_LOADED})")

        if (simState != TelephonyManager.SIM_STATE_LOADED) return

        ConfigStateManager.init(context)
        val enabled = ConfigStateManager.isAutoApplyEnabled
        Log.d(TAG, "Auto-apply enabled=$enabled")

        if (!enabled) return
        AutoApplyWorker.enqueue(context)
        Log.i(TAG, "AutoApplyWorker enqueued (SIM loaded)")
    }
}
