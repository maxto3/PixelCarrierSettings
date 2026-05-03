package com.github.maxto3.pixelims

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.github.maxto3.pixelims.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass

class CarrierConfigRootService : RootService() {

    companion object {
        private const val TAG = "CarrierConfigRootSvc"
    }

    private var appUid: Int = -1

    override fun onCreate() {
        super.onCreate()
        appUid = packageManager.getApplicationInfo(packageName, 0).uid
        Log.i(TAG, "CarrierConfigRootService created (UID: ${android.os.Process.myUid()}, PID: ${android.os.Process.myPid()})")
        try {
            HiddenApiBypass.setHiddenApiExemptions("L")
            Log.i(TAG, "HiddenApiBypass exemptions set")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set HiddenApiBypass exemptions", e)
        }
    }

    override fun onBind(intent: android.content.Intent): IBinder {
        return object : ICarrierConfigRootService.Stub() {

            private fun checkCaller() {
                require(Binder.getCallingUid() == appUid) {
                    "IPC call rejected: unauthorized UID ${Binder.getCallingUid()}"
                }
            }

            override fun getImsProvisioningInt(subId: Int, key: Int): Int {
                checkCaller()
                return executeImsProvisioningCmd(subId, key)
            }

            override fun setImsProvisioningInt(subId: Int, key: Int, value: Int) {
                checkCaller()
                executeImsProvisioningCmd(subId, key, value)
            }

            override fun overrideCarrierConfig(subId: Int, overrides: PersistableBundle?, persistent: Boolean): Boolean {
                checkCaller()
                return executeOverrideConfigCmd(subId, overrides, persistent)
            }

            override fun getCarrierConfig(subId: Int): PersistableBundle {
                checkCaller()
                return executeGetConfigCmd(subId)
            }

            override fun resetIms(subId: Int) {
                checkCaller()
                executeResetImsCmd(subId)
            }

            override fun getActiveSubscriptions(): List<String> {
                checkCaller()
                return executeGetSubscriptionsCmd()
            }
        }
    }

    private fun getServiceManager(): Class<*> {
        return Class.forName("android.os.ServiceManager")
    }

    private fun getService(name: String): IBinder? {
        val sm = getServiceManager()
        val method = sm.getDeclaredMethod("getService", String::class.java)
        return method.invoke(null, name) as? IBinder
    }

    private fun getTelephony(): Any? {
        val stub = getService("phone") ?: return null

        val candidateNames = listOf(
            "com.android.internal.telephony.ITelephony\$Stub",
            "com.android.internal.telephony.ITelephony",
            "com.android.internal.telephony.Telephony\$Stub",
        )
        for (name in candidateNames) {
            try {
                val cls = Class.forName(name)
                val asInterface = cls.declaredMethods.firstOrNull {
                    it.name == "asInterface" && it.parameterTypes.size == 1
                }
                if (asInterface != null) {
                    Log.d(TAG, "ITelephony resolved via $name")
                    return asInterface.invoke(null, stub)
                }
            } catch (_: ReflectiveOperationException) {
                Log.d(TAG, "ITelephony candidate $name not found, trying next")
            }
        }

        Log.e(TAG, "Failed to get ITelephony via any known path")
        return null
    }

    private fun getCarrierConfigLoader(): Any? {
        val stub = getService("carrier_config") ?: return null

        // Attempt 1: find asInterface by method name (any parameter count)
        try {
            val stubClass = Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
            Log.d(TAG, "Attempt 1: Stub class = ${stubClass.name}")
            if (BuildConfig.DEBUG) {
                // Dump all declared methods to understand class structure
                for (m in stubClass.declaredMethods) {
                    Log.i(TAG, "  method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()}) -> ${m.returnType.simpleName}")
                }
                for (f in stubClass.declaredFields) {
                    Log.i(TAG, "  field: ${f.name} : ${f.type.simpleName} = ${f.get(null)}")
                }
            }
            // Search for any method named "asInterface"
            val asInterfaceMethod = stubClass.declaredMethods.firstOrNull { it.name == "asInterface" && it.parameterTypes.size == 1 }
            if (asInterfaceMethod != null) {
                Log.d(TAG, "  found asInterface: ${asInterfaceMethod}")
                val result = asInterfaceMethod.invoke(null, stub)
                Log.d(TAG, "  asInterface returned: ${result?.javaClass?.name}")
                return result
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 1 failed: ${e.message}")
        }

        // Attempt 2: try the proxy class itself (ICarrierConfigLoader without $Stub)
        try {
            val ifaceClass = Class.forName("com.android.internal.telephony.ICarrierConfigLoader")
            Log.d(TAG, "Attempt 2: interface class = ${ifaceClass.name}")
            // Check if this class has Stub inner class
            for (inner in ifaceClass.declaredClasses) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "  inner class: ${inner.simpleName} (${inner.name})")
                }
                val asInterfaceMethod = inner.declaredMethods.firstOrNull { it.name == "asInterface" && it.parameterTypes.size == 1 }
                if (asInterfaceMethod != null) {
                    Log.d(TAG, "  found asInterface in ${inner.name}: ${asInterfaceMethod}")
                    val result = asInterfaceMethod.invoke(null, stub)
                    Log.d(TAG, "  asInterface returned: ${result?.javaClass?.name}")
                    return result
                }
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 2 failed: ${e.message}")
        }

        // Attempt 3: queryLocalInterface from the binder proxy directly
        try {
            val descriptor = "com.android.internal.telephony.ICarrierConfigLoader"
            val localInterface = stub.javaClass.getMethod("queryLocalInterface", String::class.java).invoke(stub, descriptor)
            if (localInterface != null) {
                Log.d(TAG, "Attempt 3: queryLocalInterface returned ${localInterface.javaClass.name}")
                return localInterface
            }
            Log.d(TAG, "Attempt 3: queryLocalInterface returned null (expected for cross-process)")
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 3 failed: ${e.message}")
        }

        // Attempt 4: search for any class with "ICarrierConfig" in name and Stub inner
        try {
            // Broad search - look through all loaded classes (expensive, only as last resort)
            Log.d(TAG, "Attempt 4: searching for Stub class indirectly")
            // Try the transitive closure of classes in the same package
            val pkg = "com.android.internal.telephony"
            // Check if there's a known inner class pattern
            val candidateNames = listOf(
                "$pkg.ICarrierConfigLoader\$Stub",
                "$pkg.CarrierConfigLoader\$Stub",
                "$pkg.CarrierConfigManager\$Stub",
            )
            for (name in candidateNames) {
                try {
                    val cls = Class.forName(name)
                    val asInterface = cls.declaredMethods.firstOrNull { m -> m.name == "asInterface" && m.parameterTypes.size == 1 }
                    if (asInterface != null) {
                        Log.d(TAG, "  found via $name: ${asInterface}")
                        return asInterface.invoke(null, stub)
                    }
                } catch (_: ReflectiveOperationException) {}
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 4 failed: ${e.message}")
        }

        Log.e(TAG, "All attempts to get CarrierConfigLoader failed")
        return null
    }

    private fun getISub(): Any? {
        val stub = getService("isub") ?: return null

        val candidateNames = listOf(
            "com.android.internal.telephony.ISub\$Stub",
            "com.android.internal.telephony.ISub",
            "com.android.internal.telephony.Sub\$Stub",
        )
        for (name in candidateNames) {
            try {
                val cls = Class.forName(name)
                val asInterface = cls.declaredMethods.firstOrNull {
                    it.name == "asInterface" && it.parameterTypes.size == 1
                }
                if (asInterface != null) {
                    Log.d(TAG, "ISub resolved via $name")
                    return asInterface.invoke(null, stub)
                }
            } catch (_: ReflectiveOperationException) {
                Log.d(TAG, "ISub candidate $name not found, trying next")
            }
        }

        Log.e(TAG, "Failed to get ISub via any known path")
        return null
    }

    private fun executeImsProvisioningCmd(subId: Int, key: Int, value: Int? = null): Int {
        try {
            val telephony = getTelephony() ?: return -1
            if (value != null) {
                val setMethod = telephony.javaClass.getDeclaredMethod(
                    "setImsProvisioningInt",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                setMethod.invoke(telephony, subId, key, value)
            }
            val getMethod = telephony.javaClass.getDeclaredMethod(
                "getImsProvisioningInt",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            return getMethod.invoke(telephony, subId, key) as? Int ?: -1
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Error executing IMS provisioning command", e)
            return -1
        }
    }

    private fun executeResetImsCmd(subId: Int) {
        try {
            val isub = getISub() ?: return
            val getSlotIndex = isub.javaClass.getDeclaredMethod("getSlotIndex", Int::class.javaPrimitiveType)
            val slotIndex = getSlotIndex.invoke(isub, subId) as? Int ?: return

            val telephony = getTelephony() ?: return
            val resetImsMethod = telephony.javaClass.getDeclaredMethod("resetIms", Int::class.javaPrimitiveType)
            resetImsMethod.invoke(telephony, slotIndex)
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Error resetting IMS", e)
        }
    }

    private fun executeOverrideConfigCmd(subId: Int, overrides: PersistableBundle?, persistent: Boolean): Boolean {
        return try {
            val ccl = getCarrierConfigLoader() ?: return false
            val method = ccl.javaClass.getDeclaredMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
                Boolean::class.javaPrimitiveType
            )
            // Log each key-value pair being sent for diagnostics
            if (overrides != null) {
                Log.d(TAG, "Override subId=$subId persistent=$persistent keys=${overrides.keySet()}")
                if (BuildConfig.DEBUG) {
                    for (key in overrides.keySet()) {
                        val value = overrides.getAny(key)
                        Log.i(TAG, "  key=$key value=$value type=${value?.javaClass?.simpleName}")
                    }
                }
            } else {
                Log.d(TAG, "Override subId=$subId persistent=$persistent overrides=null (reset)")
            }
            // AIDL declares void overrideConfig(...) -> invoke() returns null on success
            // Treat the absence of exceptions as success
            method.invoke(ccl, subId, overrides, persistent)
            Log.i(TAG, "overrideConfig invoked successfully (void return)")
            true
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Error overriding carrier config", e)
            false
        }
    }

    private fun executeGetConfigCmd(subId: Int): PersistableBundle {
        return try {
            val ccl = getCarrierConfigLoader() ?: return PersistableBundle()
            val method = ccl.javaClass.getDeclaredMethod(
                "getConfigForSubId",
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            method.invoke(ccl, subId, "com.github.maxto3.pixelims", null) as? PersistableBundle
                ?: PersistableBundle()
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Error getting carrier config", e)
            PersistableBundle()
        }
    }

    private fun executeGetSubscriptionsCmd(): List<String> {
        try {
            val isub = getISub() ?: return emptyList()
            val method = isub.javaClass.getDeclaredMethod(
                "getActiveSubscriptionInfoList",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )

            @Suppress("UNCHECKED_CAST")
            val subscriptions = method.invoke(isub, "com.github.maxto3.pixelims", null, false) as? List<Any>
                ?: return emptyList()

            return subscriptions.map { info ->
                val getSubId = info.javaClass.getMethod("getSubscriptionId")
                val getDisplay = info.javaClass.getMethod("getDisplayName")
                val id = getSubId.invoke(info) as? Int ?: return@map ""
                val name = getDisplay.invoke(info) as? CharSequence ?: ""
                "$id:$name"
            }.filter { it.isNotEmpty() }
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Error getting subscriptions", e)
            return emptyList()
        }
    }
}
