package me.ikirby.pixelutils

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Background service to handle carrier config restoration.
 * Using a Service is more robust than a BroadcastReceiver for long-running root tasks.
 */
class RestorationService : Service() {

    companion object {
        private const val TAG = "RestorationService"
        private const val EXTRA_SUB_IDS = "subIds"
        private const val SERVICE_BIND_TIMEOUT_SECONDS = 25L

        fun start(context: Context, subIds: List<Int>) {
            val intent = Intent(context, RestorationService::class.java).apply {
                putIntegerArrayListExtra(EXTRA_SUB_IDS, ArrayList(subIds))
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val subIds = intent?.getIntegerArrayListExtra(EXTRA_SUB_IDS) ?: emptyList<Int>()
        if (subIds.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting restoration for subIds: $subIds")

        thread {
            try {
                performRestoration(subIds)
            } finally {
                Log.i(TAG, "Restoration task finished")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun performRestoration(subIds: List<Int>) {
        // 1. Ensure root shell is available
        try {
            com.topjohnwu.superuser.Shell.getShell()
        } catch (e: Exception) {
            Log.e(TAG, "Root shell not available", e)
            return
        }

        // 2. Try to bind and apply
        val latch = CountDownLatch(1)
        var carrierService: ICarrierConfigRootService? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(TAG, "CarrierConfigRootService connected!")
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
                Log.i(TAG, "Binding to RootService...")
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
                }
            }
            handler.post { try { RootService.unbind(serviceConnection) } catch (_: Exception) {} }
        } else {
            Log.w(TAG, "Timed out waiting for root service binding")
        }
    }

    private fun applyToSubId(svc: ICarrierConfigRootService, subId: Int) {
        val overrides = CarrierConfigPersistence.loadOverrides(this, subId) ?: return
        try {
            Log.i(TAG, "Applying overrides for subId $subId")
            if (svc.overrideCarrierConfig(subId, overrides, false)) {
                Log.i(TAG, "Success. Resetting IMS for $subId")
                svc.resetIms(subId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying to $subId", e)
        }
    }
}
