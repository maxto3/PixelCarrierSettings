package me.ikirby.pixelutils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.IActivityManager
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.CarrierConfigManager
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Shared utility for applying carrier config overrides with shell-level permissions
 * via Shizuku. Used by both the UI-triggered [InstrumentationHelper] flow and the
 * background [AutoApplyWorker].
 */
object CarrierConfigHelper {

    /**
     * Apply (or reset) a carrier config override for the given subscription.
     * **Must be called from within an instrumentation context** (i.e. inside
     * [InstrumentationHelper]) — otherwise use [applyViaInstrumentation].
     *
     * @param context  Application or activity context.
     * @param subId    Subscription ID to override.
     * @param overrides [PersistableBundle] of key-value pairs, or `null` to reset.
     */
    @SuppressLint("MissingPermission")
    fun applyOverrideConfig(context: Context, subId: Int, overrides: PersistableBundle?) {
        val ams = IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
        )
        ams.startDelegateShellPermissionIdentity(Os.getuid(), null)
        try {
            val ccm = context.applicationContext
                .getSystemService(CarrierConfigManager::class.java)
            ccm.overrideConfig(subId, overrides, /* persistent = */ false)
        } finally {
            ams.stopDelegateShellPermissionIdentity()
        }
    }

    /**
     * Apply carrier config overrides from a non-instrumentation context (e.g. a
     * [androidx.work.Worker]). Launches [InstrumentationHelper] via Shizuku to gain
     * shell-level permissions. Batches all overrides for one subId into a single
     * instrumentation launch.
     *
     * @param context       Application or activity context (used for package name).
     * @param subId         Subscription ID to override.
     * @param overridesList List of [PersistableBundle] overrides to apply.
     */
    fun applyViaInstrumentation(context: Context, subId: Int, overridesList: List<PersistableBundle>) {
        if (overridesList.isEmpty()) return
        val subIds = IntArray(overridesList.size) { subId }
        val args = Bundle().apply {
            putIntArray("subIds", subIds)
            putParcelableArrayList("overridesList", ArrayList(overridesList))
        }
        val ams = IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
        )
        ams.startInstrumentation(
            ComponentName(context.applicationContext, InstrumentationHelper::class.java),
            null,
            ActivityManager.INSTR_FLAG_NO_RESTART,
            args,
            null,
            UiAutomationConnection(),
            0,
            null
        )
    }
}
