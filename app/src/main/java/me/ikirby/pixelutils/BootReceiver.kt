package me.ikirby.pixelutils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives BOOT_COMPLETED broadcast and re-applies persisted carrier config overrides.
 *
 * The overrides are applied in a root-privileged daemon process via CarrierConfigRootService.
 * This receiver waits for the AIDL service to be bound, then applies each saved override
 * for all subscription IDs that have persisted config.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val SERVICE_BIND_TIMEOUT_SECONDS = 30L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — checking for persisted carrier config overrides")

        // Collect all subIds that have saved overrides by scanning the SharedPreferences keys
        val subIds = getSubIdsWithOverrides(context)
        if (subIds.isEmpty()) {
            Log.i(TAG, "No persisted overrides found, nothing to restore")
            return
        }

        Log.i(TAG, "Found persisted overrides for subIds: $subIds")

        // Bind to the root service and apply overrides
        val latch = CountDownLatch(1)
        var carrierService: ICarrierConfigRootService? = null

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                carrierService = ICarrierConfigRootService.Stub.asInterface(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                carrierService = null
            }
        }

        try {
            val serviceIntent = Intent(context, CarrierConfigRootService::class.java)
            RootService.bind(serviceIntent, serviceConnection)

            if (!latch.await(SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for CarrierConfigRootService")
                return
            }

            val svc = carrierService
            if (svc == null) {
                Log.w(TAG, "Failed to bind CarrierConfigRootService")
                return
            }

            for (subId in subIds) {
                applyPersistedOverrides(context, svc, subId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring carrier config overrides", e)
        } finally {
            RootService.unbind(serviceConnection)
        }
    }

    /**
     * Scans SharedPreferences for all subIds that have persisted overrides.
     */
    private fun getSubIdsWithOverrides(context: Context): List<Int> {
        val prefs = context.getSharedPreferences("carrier_config_persistence", Context.MODE_PRIVATE)
        val subIds = mutableSetOf<Int>()
        val prefix = "override_"
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix) && key.endsWith("_keys") && key != "${prefix}keys") {
                // Key format: override_{subId}_keys
                val subIdStr = key.removePrefix(prefix).removeSuffix("_keys")
                val subId = subIdStr.toIntOrNull()
                if (subId != null) {
                    subIds.add(subId)
                }
            }
        }
        return subIds.toList().sorted()
    }

    /**
     * Loads and applies persisted overrides for a single subscription.
     */
    private fun applyPersistedOverrides(
        context: Context,
        svc: ICarrierConfigRootService,
        subId: Int
    ) {
        val overrides = CarrierConfigPersistence.loadOverrides(context, subId)
        if (overrides == null) {
            Log.w(TAG, "No overrides found for subId=$subId (key list existed but bundle was empty)")
            return
        }

        try {
            // Use persistent=false here because:
            // 1. The persistence is handled by SharedPreferences (this BootReceiver)
            // 2. The Android system API's persistent=true is unreliable per earlier testing
            val success = svc.overrideCarrierConfig(subId, overrides, false)
            if (success) {
                Log.i(TAG, "Successfully restored overrides for subId=$subId")
            } else {
                Log.w(TAG, "Failed to restore overrides for subId=$subId (overrideCarrierConfig returned false)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception restoring overrides for subId=$subId", e)
        }
    }
}