package com.github.maxto3.pixelims

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.github.maxto3.pixelims.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

class CarrierConfigRootService : RootService() {

    companion object {
        private const val TAG = "CarrierConfigRootSvc"
        private const val WFC_MODE_KEY = "wfc_ims_mode"
        private const val KEY_CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url"
        private const val KEY_CAPTIVE_PORTAL_HTTPS_URL = "captive_portal_https_url"
        private const val CN_HTTP_URL = "http://connectivitycheck.gstatic.cn/generate_204"
        private const val CN_HTTPS_URL = "https://www.google.cn/generate_204"

        @Volatile private var cachedTelephonyStubPair: Pair<Class<*>, Method>? = null
        @Volatile private var cachedIsubStubPair: Pair<Class<*>, Method>? = null
        @Volatile private var cachedCclStubPair: Pair<Class<*>, Method>? = null
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
                val callingUid = Binder.getCallingUid()
                require(callingUid == appUid) {
                    "IPC call rejected: unauthorized UID $callingUid"
                }
                val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
                if (packages.isNotEmpty()) {
                    val signatureMatch = packageManager.checkSignatures(packageName, packages[0])
                    require(signatureMatch == PackageManager.SIGNATURE_MATCH) {
                        "IPC call rejected: signature mismatch for UID $callingUid"
                    }
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

            override fun isImsRegistered(subId: Int): Boolean {
                checkCaller()
                return executeIsImsRegisteredCmd(subId)
            }

            override fun getWfcMode(): Int {
                checkCaller()
                return executeGetWfcModeCmd()
            }

            override fun setWfcMode(mode: Int): Boolean {
                checkCaller()
                return executeSetWfcModeCmd(mode)
            }

            override fun sanitizeApnImsType(): Boolean {
                checkCaller()
                return executeSanitizeApnImsCmd()
            }

            override fun getCaptivePortalConfig(): String {
                checkCaller()
                val http = execSettings("get", "global", KEY_CAPTIVE_PORTAL_HTTP_URL)
                    .takeUnless { it.isBlank() || it == "null" }.orEmpty()
                val https = execSettings("get", "global", KEY_CAPTIVE_PORTAL_HTTPS_URL)
                    .takeUnless { it.isBlank() || it == "null" }.orEmpty()
                return "$http|$https"
            }

            override fun applyCaptivePortalCnUrls(): Boolean {
                checkCaller()
                return try {
                    execSettings("put", "global", KEY_CAPTIVE_PORTAL_HTTP_URL, CN_HTTP_URL)
                    execSettings("put", "global", KEY_CAPTIVE_PORTAL_HTTPS_URL, CN_HTTPS_URL)
                    getCaptivePortalConfig() == "$CN_HTTP_URL|$CN_HTTPS_URL"
                } catch (e: Throwable) {
                    Log.e(TAG, "Error applying captive portal URLs", e)
                    false
                }
            }

            override fun restoreCaptivePortalDefaultUrls(): Boolean {
                checkCaller()
                return try {
                    execSettings("delete", "global", KEY_CAPTIVE_PORTAL_HTTP_URL)
                    execSettings("delete", "global", KEY_CAPTIVE_PORTAL_HTTPS_URL)
                    val current = getCaptivePortalConfig()
                    current == "|"
                } catch (e: Throwable) {
                    Log.e(TAG, "Error restoring captive portal URLs", e)
                    false
                }
            }

            override fun captureLogcat(lines: Int): String {
                checkCaller()
                return try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("/system/bin/logcat", "-d", "-t", lines.coerceIn(100, 20000).toString())
                    )
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    output
                } catch (e: Throwable) {
                    Log.e(TAG, "Error capturing logcat", e)
                    ""
                }
            }

            override fun queryApnList(): String {
                checkCaller()
                return try {
                    execContent(
                        "query",
                        "--uri", "content://telephony/carriers",
                        "--projection", "_id,apn,type"
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Error querying APNs", e)
                    ""
                }
            }

            override fun updateApnType(id: Int, type: String): Boolean {
                checkCaller()
                return try {
                    execContent(
                        "update",
                        "--uri", "content://telephony/carriers/$id",
                        "--bind", "type:s:$type"
                    )
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "Error updating APN type", e)
                    false
                }
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
        val pair = cachedTelephonyStubPair ?: resolveTelephonyStubInfo().also { cachedTelephonyStubPair = it }
        return pair?.let { it.second.invoke(null, stub) }
    }

    private fun resolveTelephonyStubInfo(): Pair<Class<*>, Method>? {
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
                    Log.d(TAG, "ITelephony resolved via $name (cached)")
                    return Pair(cls, asInterface)
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

        // Fast path: use cached Stub class / asInterface method pair
        cachedCclStubPair?.let { (_, method) ->
            try {
                return method.invoke(null, stub)
            } catch (_: Exception) {
                cachedCclStubPair = null // invalidate on failure
            }
        }

        // Attempt 1:4: search for the right Stub class and asInterface method
        val pair = resolveCclStubInfo()
        if (pair != null) {
            cachedCclStubPair = pair
            try {
                return pair.second.invoke(null, stub)
            } catch (e: Exception) {
                Log.w(TAG, "Cached CCL asInterface failed", e)
            }
        }

        // Attempt 3: queryLocalInterface from the binder proxy directly
        try {
            val descriptor = "com.android.internal.telephony.ICarrierConfigLoader"
            val localInterface = stub.javaClass.getMethod("queryLocalInterface", String::class.java).invoke(stub, descriptor)
            if (localInterface != null) {
                Log.d(TAG, "queryLocalInterface returned ${localInterface.javaClass.name}")
                return localInterface
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "queryLocalInterface failed", e)
        }

        Log.e(TAG, "All attempts to get CarrierConfigLoader failed")
        return null
    }

    private fun resolveCclStubInfo(): Pair<Class<*>, Method>? {
        // Attempt 1: ICarrierConfigLoader$Stub directly
        try {
            val stubClass = Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
            Log.d(TAG, "Attempt 1: Stub class = ${stubClass.name}")
            if (BuildConfig.DEBUG) {
                for (m in stubClass.declaredMethods) {
                    Log.i(TAG, "  method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()}) -> ${m.returnType.simpleName}")
                }
                for (f in stubClass.declaredFields) {
                    Log.i(TAG, "  field: ${f.name} : ${f.type.simpleName} = ${f.get(null)}")
                }
            }
            val asInterfaceMethod = stubClass.declaredMethods.firstOrNull { it.name == "asInterface" && it.parameterTypes.size == 1 }
            if (asInterfaceMethod != null) {
                Log.d(TAG, "  found asInterface: $asInterfaceMethod (cached)")
                return Pair(stubClass, asInterfaceMethod)
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 1 failed: ${e.message}")
        }

        // Attempt 2: ICarrierConfigLoader interface → inner Stub
        try {
            val ifaceClass = Class.forName("com.android.internal.telephony.ICarrierConfigLoader")
            for (inner in ifaceClass.declaredClasses) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "  inner class: ${inner.simpleName} (${inner.name})")
                }
                val asInterfaceMethod = inner.declaredMethods.firstOrNull { it.name == "asInterface" && it.parameterTypes.size == 1 }
                if (asInterfaceMethod != null) {
                    Log.d(TAG, "  found asInterface in ${inner.name} (cached)")
                    return Pair(inner, asInterfaceMethod)
                }
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 2 failed: ${e.message}")
        }

        // Attempt 4: broad candidate search (expensive, only as last resort)
        try {
            val pkg = "com.android.internal.telephony"
            val candidateNames = listOf(
                "$pkg.ICarrierConfigLoader\$Stub",
                "$pkg.CarrierConfigLoader\$Stub",
                "$pkg.CarrierConfigManager\$Stub",
            )
            for (name in candidateNames) {
                try {
                    val cls = Class.forName(name)
                    val asInterface = cls.declaredMethods.firstOrNull { it.name == "asInterface" && it.parameterTypes.size == 1 }
                    if (asInterface != null) {
                        Log.d(TAG, "  found via $name (cached)")
                        return Pair(cls, asInterface)
                    }
                } catch (_: ReflectiveOperationException) {}
            }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Attempt 4 failed: ${e.message}")
        }

        return null
    }

    private fun getISub(): Any? {
        val stub = getService("isub") ?: return null
        val pair = cachedIsubStubPair ?: resolveISubStubInfo().also { cachedIsubStubPair = it }
        return pair?.let { it.second.invoke(null, stub) }
    }

    private fun resolveISubStubInfo(): Pair<Class<*>, Method>? {
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
                    Log.d(TAG, "ISub resolved via $name (cached)")
                    return Pair(cls, asInterface)
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
            // Use getMethod instead of getDeclaredMethod: the AIDL Proxy class
            // does not explicitly declare interface methods, but getMethod searches
            // the interface hierarchy where the method is defined.
            val method: java.lang.reflect.Method? = try {
                ccl.javaClass.getMethod(
                    "getConfigForSubId",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java
                )
            } catch (_: NoSuchMethodException) {
                // Fallback: try the resolved Stub class
                cachedCclStubPair?.first?.getMethod(
                    "getConfigForSubId",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java
                )
            }
            method?.invoke(ccl, subId, packageName, null) as? PersistableBundle
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
            val subscriptions = method.invoke(isub, packageName, null, false) as? List<Any>
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

    private fun executeIsImsRegisteredCmd(subId: Int): Boolean {
        return try {
            val telephony = getTelephony() ?: return false
            val method = telephony.javaClass.getMethod(
                "isImsRegistered",
                Int::class.javaPrimitiveType
            )
            method.invoke(telephony, subId) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.e(TAG, "Error reading IMS registration state", e)
            false
        }
    }

    private fun executeGetWfcModeCmd(): Int {
        return try {
            val isub = getISub() ?: return -1
            val subId = executeGetSubscriptionsCmd()
                .firstOrNull()?.substringBefore(":")?.toIntOrNull() ?: return -1
            val method = isub.javaClass.getMethod(
                "getSubscriptionProperty",
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java
            )
            val value = method.invoke(isub, subId, WFC_MODE_KEY, packageName, null) as? String
            value?.toIntOrNull() ?: -1
        } catch (e: Throwable) {
            Log.e(TAG, "Error reading WFC mode", e)
            -1
        }
    }

    private fun executeSetWfcModeCmd(mode: Int): Boolean {
        return try {
            val isub = getISub() ?: return false
            val subId = executeGetSubscriptionsCmd()
                .firstOrNull()?.substringBefore(":")?.toIntOrNull() ?: return false
            val method = isub.javaClass.getMethod(
                "setSubscriptionProperty",
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            method.invoke(isub, subId, WFC_MODE_KEY, mode.toString())
            Log.i(TAG, "WFC mode set to $mode for subId=$subId")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error setting WFC mode", e)
            false
        }
    }

    /**
     * Removes the "ims" type from default APNs when a dedicated IMS APN exists.
     * A polluted default APN makes Android establish the IMS PDN there and the network
     * never returns a P-CSCF address, so IMS registration fails.
     *
     * The root service process has no registered app record, so ContentResolver cannot
     * reach the telephony provider; the content CLI runs in its own app process instead.
     */
    private fun executeSanitizeApnImsCmd(): Boolean {
        return try {
            val output = execContent(
                "query",
                "--uri", "content://telephony/carriers",
                "--projection", "_id,type"
            )
            val rows = output.lineSequence().mapNotNull { line ->
                val id = Regex("_id=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?: return@mapNotNull null
                val raw = line.substringAfter("type=", "")
                id to raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }.toList()

            val hasDedicatedIms = rows.any { (_, types) -> types.size == 1 && types[0] == "ims" }
            if (!hasDedicatedIms) {
                return false
            }

            var changed = false
            for ((id, types) in rows) {
                if (!types.contains("ims") || !types.contains("default")) continue
                val newType = types.filter { it != "ims" }.joinToString(",")
                execContent(
                    "update",
                    "--uri", "content://telephony/carriers/$id",
                    "--bind", "type:s:$newType"
                )
                changed = true
                Log.i(TAG, "Removed ims type from APN id=$id -> $newType")
            }
            changed
        } catch (e: Throwable) {
            Log.e(TAG, "Error sanitizing APN types", e)
            false
        }
    }

    private fun execContent(vararg args: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/content", *args))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun execSettings(vararg args: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/settings", *args))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.trim()
    }
}
