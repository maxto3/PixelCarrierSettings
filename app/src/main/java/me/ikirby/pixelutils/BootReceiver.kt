package me.ikirby.pixelutils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Triggers auto-apply of saved carrier config overrides after device boot completes.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PixelCS.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received: ${intent.action}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        ConfigStateManager.init(context)
        val enabled = ConfigStateManager.isAutoApplyEnabled
        Log.d(TAG, "Auto-apply enabled=$enabled")

        if (!enabled) return
        AutoApplyWorker.enqueue(context)
        Log.i(TAG, "AutoApplyWorker enqueued")
    }
}
