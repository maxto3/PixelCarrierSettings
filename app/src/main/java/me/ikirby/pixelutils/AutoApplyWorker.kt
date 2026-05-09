package me.ikirby.pixelutils

import android.content.Context
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.android.internal.telephony.ISub
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that applies saved carrier config overrides in the background
 * after device boot. Retries with exponential backoff if Shizuku or the SIM is not
 * yet ready.
 */
class AutoApplyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PixelCS.AutoApply"
        private const val UNIQUE_WORK_NAME = "auto_apply_configs"

        /** Enqueue (or replace) the auto-apply work. Safe to call from any thread. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoApplyWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            Log.d(TAG, "Work enqueued")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork() started")
        ConfigStateManager.init(applicationContext)

        // 1. Load saved configs; nothing to do if none are saved
        val allConfigs = ConfigStateManager.getAllConfigs()
        Log.d(TAG, "Saved configs: ${allConfigs.size} ICCIDs")
        if (allConfigs.isEmpty()) {
            Log.i(TAG, "No saved configs, finishing")
            return Result.success()
        }

        // 2. Ensure hidden API access is available (may not have been called yet)
        HiddenApiBypass.setHiddenApiExemptions("")

        // 3. Obtain ISub binder via Shizuku
        val sub: ISub = try {
            val binder = TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .subscriptionServiceRegisterer
                .get()
            if (binder == null) {
                Log.w(TAG, "Shizuku binder not available, retrying")
                return Result.retry()
            }
            ISub.Stub.asInterface(ShizukuBinderWrapper(binder))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ISub binder", e)
            return Result.retry()
        }

        // 4. Get current subscriptions
        val subscriptions = try {
            sub.getActiveSubscriptionInfoList(null, null, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get subscription list", e)
            return Result.retry()
        }
        Log.d(TAG, "Active subscriptions: ${subscriptions.size}")
        if (subscriptions.isEmpty()) {
            Log.w(TAG, "No active subscriptions, retrying (SIM may need PIN)")
            return Result.retry()
        }

        // 5. Apply saved configs for each SIM matched by ICCID
        var applied = 0
        for (subInfo in subscriptions) {
            val iccid = subInfo.iccId ?: continue
            val savedKeys = allConfigs[iccid] ?: continue
            Log.d(TAG, "Applying ${savedKeys.size} config(s) for ICCID=${iccid.takeLast(4)}")

            val overridesList = savedKeys.mapNotNull { key -> buildOverrides(key) }
            if (overridesList.isEmpty()) continue

            try {
                CarrierConfigHelper.applyViaInstrumentation(
                    applicationContext, subInfo.subscriptionId, overridesList
                )
                applied += overridesList.size
                Log.d(TAG, "Applied ${overridesList.size} config(s) for subId=${subInfo.subscriptionId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply configs for subId=${subInfo.subscriptionId}", e)
            }
        }

        Log.i(TAG, "Finished: applied $applied config(s)")
        return Result.success()
    }

    /** Build a [PersistableBundle] for the given config key. */
    private fun buildOverrides(key: ConfigStateManager.ConfigKey): PersistableBundle? {
        return when (key) {
            ConfigStateManager.ConfigKey.VOLTE -> PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
            }
            ConfigStateManager.ConfigKey.VONR -> PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true)
                putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true)
            }
            ConfigStateManager.ConfigKey.NR_MODE -> PersistableBundle().apply {
                putIntArray(
                    CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    intArrayOf(
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
                    )
                )
            }
            ConfigStateManager.ConfigKey.WFC -> PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
                putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true)
                putBoolean(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, true)
                putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true)
                putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true)
                putInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, 4)
            }
            ConfigStateManager.ConfigKey.SIGNAL_THRESHOLD -> PersistableBundle().apply {
                putIntArray(
                    CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                    intArrayOf(-115, -105, -95, -85)
                )
            }
            ConfigStateManager.ConfigKey.SIGNAL_INFLATE -> PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL, false)
            }
            ConfigStateManager.ConfigKey.SHOW_IMS -> PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, true)
            }
            ConfigStateManager.ConfigKey.SHOW_4G -> PersistableBundle().apply {
                putBoolean(CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL, true)
            }
        }
    }
}
