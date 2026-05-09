package me.ikirby.pixelutils

import android.app.Instrumentation
import android.os.Bundle
import android.os.PersistableBundle

class InstrumentationHelper : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments == null) {
            finish(0, null)
            return
        }

        // Batch mode: "subIds" (int[]) + "overridesList" (ArrayList<PersistableBundle>)
        val subIds = arguments.getIntArray("subIds")
        if (subIds != null) {
            val overridesList = arguments.getParcelableArrayList<PersistableBundle>("overridesList")
            if (overridesList != null && subIds.size == overridesList.size) {
                for (i in subIds.indices) {
                    CarrierConfigHelper.applyOverrideConfig(
                        context, subIds[i], overridesList[i]
                    )
                }
            }
            finish(0, null)
            return
        }

        // Single mode (backward-compatible with ConfigOverridesActivity)
        val subId = arguments.getInt("subId", 0)
        val overrides = arguments.getParcelable("overrides", PersistableBundle::class.java)
        CarrierConfigHelper.applyOverrideConfig(context, subId, overrides)
        finish(0, null)
    }
}
