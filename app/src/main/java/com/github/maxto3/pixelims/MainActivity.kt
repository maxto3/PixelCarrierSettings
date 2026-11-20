package com.github.maxto3.pixelims

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.ipc.RootService
import com.github.maxto3.pixelims.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_VOIMS_OPT_IN_STATUS = 68
        private const val PROVISIONING_VALUE_ENABLED = 1
        private const val PROVISIONING_VALUE_DISABLED = 0
        private const val WFC_MODE_WIFI_ONLY = 0
        private const val WFC_MODE_CELLULAR_PREFERRED = 1
        private const val WFC_MODE_WIFI_PREFERRED = 2
        private const val PERMISSION_REQ_PHONE_STATE = 0
        private const val PERMISSION_REQ_NOTIFICATIONS = 1
        private const val REQUEST_RESTORE = 2
        private const val PREFS_CARRIER_CONFIG = "carrier_config_persistence"
    }

    private lateinit var binding: ActivityMainBinding

    private var subIdPhone0 = 0
    private var subIdPhone1 = 0
    private var carrierService: ICarrierConfigRootService? = null
    private var timeoutRunnable: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            carrierService = ICarrierConfigRootService.Stub.asInterface(service)
            timeoutRunnable?.let { binding.root.removeCallbacks(it) }
            binding.textStatus.visibility = View.GONE
            binding.contentArea.visibility = View.VISIBLE
            carrierService?.let {
                loadSubscriptionStatus()
                ensureImsHealth()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carrierService = null
            binding.textStatus.text = getString(R.string.service_connection_failed)
            binding.textStatus.visibility = View.VISIBLE
            binding.contentArea.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCaptivePortal.setOnClickListener { toggleCaptivePortal() }
        binding.btnExportLogcat.setOnClickListener { exportLogcat() }
        binding.btnBackupConfig.setOnClickListener { backupConfig() }
        binding.btnRestoreConfig.setOnClickListener { pickBackupFile() }
        binding.btnApnEditor.setOnClickListener {
            startActivity(Intent(this, ApnEditorActivity::class.java))
        }

        binding.btnEnableIMS0.setOnClickListener { enableIMSProvisioning(subIdPhone0) }
        binding.btnResetIMS0.setOnClickListener { resetIMS(subIdPhone0) }
        binding.btnConfigOverrides0.setOnClickListener {
            val intent = Intent(this, ConfigOverridesActivity::class.java).apply {
                putExtra("subId", subIdPhone0)
                putExtra("displayName", binding.textNameSub0.text.toString())
            }
            startActivity(intent)
        }
        binding.btnDisableIMS0.setOnClickListener { disableIMSProvisioning(subIdPhone0) }

        binding.btnEnableIMS1.setOnClickListener { enableIMSProvisioning(subIdPhone1) }
        binding.btnResetIMS1.setOnClickListener { resetIMS(subIdPhone1) }
        binding.btnConfigOverrides1.setOnClickListener {
            val intent = Intent(this, ConfigOverridesActivity::class.java).apply {
                putExtra("subId", subIdPhone1)
                putExtra("displayName", binding.textNameSub1.text.toString())
            }
            startActivity(intent)
        }
        binding.btnDisableIMS1.setOnClickListener { disableIMSProvisioning(subIdPhone1) }
    }

    override fun onStart() {
        super.onStart()
        RootShell.refreshRootStatus()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutRunnable?.let { binding.root.removeCallbacks(it) }
        RootService.unbind(serviceConnection)
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(msg: String) {
        AlertDialog.Builder(this).apply {
            setMessage(msg)
            setCancelable(false)
            setPositiveButton(android.R.string.ok) { _, _ -> finish() }
        }.show()
    }

    private fun init() {
        binding.textStatus.text = getString(R.string.initializing)
        binding.textStatus.visibility = View.VISIBLE
        binding.contentArea.visibility = View.GONE

        if (!RootShell.isRootAvailable()) {
            binding.textStatus.text = getString(R.string.root_not_available)
            showDialog(getString(R.string.root_not_available))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQ_NOTIFICATIONS
                )
            }
        }

        val intent = Intent(this, CarrierConfigRootService::class.java)
        RootService.bind(intent, serviceConnection)

        // 5-second timeout: if service still not connected, show error
        timeoutRunnable = Runnable {
            if (carrierService == null) {
                binding.textStatus.text = getString(R.string.service_connection_failed)
                showToast(R.string.init_failed)
            }
        }
        binding.root.postDelayed(timeoutRunnable!!, 5000)
    }

    private fun enableIMSProvisioning(subId: Int) {
        val svc = carrierService ?: return
        try {
            val current = svc.getImsProvisioningInt(
                subId,
                KEY_VOIMS_OPT_IN_STATUS
            )
            if (current == PROVISIONING_VALUE_ENABLED) {
                showToast(R.string.voims_already_enabled)
                return
            }
            svc.setImsProvisioningInt(
                subId,
                KEY_VOIMS_OPT_IN_STATUS,
                PROVISIONING_VALUE_ENABLED
            )
            showToast(R.string.voims_enabled)
        } catch (_: Exception) {
            showToast(R.string.init_failed)
        }
    }

    private fun disableIMSProvisioning(subId: Int) {
        val svc = carrierService ?: return
        try {
            svc.setImsProvisioningInt(
                subId,
                KEY_VOIMS_OPT_IN_STATUS,
                PROVISIONING_VALUE_DISABLED
            )
            showToast(R.string.voims_disabled)
        } catch (_: Exception) {
            showToast(R.string.init_failed)
        }
    }

    private fun resetIMS(subId: Int) {
        val svc = carrierService ?: return
        try {
            svc.resetIms(subId)
            showToast(R.string.ims_reset)
        } catch (_: Exception) {
            showToast(R.string.init_failed)
        }
    }

    /**
     * Toggles the captive portal probe between China endpoints and the system default.
     * The Google connectivity check is unreachable in mainland China and makes Wi-Fi
     * show "no internet", which in turn blocks Wi-Fi calling attempts.
     */
    private fun toggleCaptivePortal() {
        val svc = carrierService ?: return
        Thread {
            val current = try {
                svc.getCaptivePortalConfig()
            } catch (_: Exception) {
                ""
            }
            val isCn = current.startsWith("http://connectivitycheck.gstatic.cn")
            val ok = try {
                if (isCn) svc.restoreCaptivePortalDefaultUrls()
                else svc.applyCaptivePortalCnUrls()
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                showToast(
                    if (!ok) R.string.init_failed
                    else if (isCn) R.string.captive_portal_restored
                    else R.string.captive_portal_applied
                )
            }
        }.start()
    }

    private fun exportLogcat() {
        val svc = carrierService ?: return
        Thread {
            val log = try {
                svc.captureLogcat(3000)
            } catch (_: Exception) {
                ""
            }
            if (log.isBlank()) {
                runOnUiThread { showToast(R.string.export_logcat_failed) }
                return@Thread
            }
            val file = File(cacheDir, "logcat-${System.currentTimeMillis()}.txt")
            file.writeText(log)
            runOnUiThread {
                shareFile(file, "text/plain")
                showToast(R.string.export_logcat_ready)
            }
        }.start()
    }

    private fun backupConfig() {
        val prefs = getSharedPreferences(PREFS_CARRIER_CONFIG, MODE_PRIVATE)
        val entries = JSONObject()
        for ((key, value) in prefs.all) {
            entries.put(key, encodePrefValue(value))
        }
        if (entries.length() == 0) {
            showToast(R.string.backup_config_empty)
            return
        }
        val file = File(cacheDir, "pixelims-backup-${System.currentTimeMillis()}.json")
        file.writeText(entries.toString(2))
        shareFile(file, "application/json")
        showToast(R.string.backup_config_ready)
    }

    private fun pickBackupFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_RESTORE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RESTORE && resultCode == RESULT_OK) {
            data?.data?.let { restoreConfig(it) }
        }
    }

    private fun restoreConfig(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return
            val json = JSONObject(text)
            val prefs = getSharedPreferences(PREFS_CARRIER_CONFIG, MODE_PRIVATE)
            val edit = prefs.edit().clear()
            for (key in json.keys()) {
                val entry = json.getJSONObject(key)
                when (entry.getString("t")) {
                    "bool" -> edit.putBoolean(key, entry.getBoolean("v"))
                    "int" -> edit.putInt(key, entry.getInt("v"))
                    "long" -> edit.putLong(key, entry.getLong("v"))
                    "float" -> edit.putFloat(key, entry.getDouble("v").toFloat())
                    "set" -> {
                        val array = entry.getJSONArray("v")
                        val set = mutableSetOf<String>()
                        for (i in 0 until array.length()) set.add(array.getString(i))
                        edit.putStringSet(key, set)
                    }
                    else -> edit.putString(key, entry.getString("v"))
                }
            }
            edit.apply()
            showToast(R.string.restore_config_done)
        } catch (e: Exception) {
            Log.e(TAG, "restore config failed", e)
            showToast(R.string.restore_config_failed)
        }
    }

    private fun encodePrefValue(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is Boolean -> {
                put("t", "bool")
                put("v", value)
            }
            is Int -> {
                put("t", "int")
                put("v", value)
            }
            is Long -> {
                put("t", "long")
                put("v", value)
            }
            is Float -> {
                put("t", "float")
                put("v", value.toDouble())
            }
            is Set<*> -> {
                put("t", "set")
                put("v", JSONArray(value.toList()))
            }
            else -> {
                put("t", "string")
                put("v", value?.toString() ?: "")
            }
        }
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_logcat)))
    }

    /**
     * Ensures the two conditions required for IMS registration on Android 17:     * the VoIMS opt-in flag (key 68) and a working APN split, then restores
     * cellular-preferred Wi-Fi calling if Wi-Fi preferred lost IMS.
     * Runs silently in the background; manual buttons keep their own toasts.
     */
    private fun ensureImsHealth() {
        val svc = carrierService ?: return
        Thread {
            try {
                val subIds = svc.getActiveSubscriptions()
                    .mapNotNull { it.substringBefore(":").toIntOrNull() }
                    .distinct()
                for (subId in subIds) {
                    try {
                        if (svc.getImsProvisioningInt(subId, KEY_VOIMS_OPT_IN_STATUS)
                            != PROVISIONING_VALUE_ENABLED
                        ) {
                            svc.setImsProvisioningInt(
                                subId,
                                KEY_VOIMS_OPT_IN_STATUS,
                                PROVISIONING_VALUE_ENABLED
                            )
                            Log.i(TAG, "VoIMS opt-in enabled for subId=$subId")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "VoIMS opt-in check failed for subId=$subId", e)
                    }
                }

                try {
                    val mode = svc.getWfcMode()
                    Log.i(TAG, "wfc guard: subIds=$subIds mode=$mode " +
                        "registered=${subIds.map { it to runCatching { svc.isImsRegistered(it) }.getOrNull() }}")
                    if (mode == WFC_MODE_WIFI_PREFERRED || mode == WFC_MODE_WIFI_ONLY) {
                        val anyUnregistered = subIds.any { subId ->
                            try {
                                !svc.isImsRegistered(subId)
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (anyUnregistered) {
                            svc.setWfcMode(WFC_MODE_CELLULAR_PREFERRED)
                            Log.i(TAG, "WFC mode $mode fell back to cellular")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WFC guard failed", e)
                }

                try {
                    if (svc.sanitizeApnImsType()) {
                        Log.i(TAG, "APN ims type sanitized")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "APN sanitize failed", e)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ensureImsHealth failed", e)
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_PHONE_STATE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSubscriptionStatus()
        }
    }

    private fun loadSubscriptionStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), PERMISSION_REQ_PHONE_STATE)
            return
        }

        val sm = getSystemService(SubscriptionManager::class.java)
        val subscriptions: List<SubscriptionInfo> = sm.activeSubscriptionInfoList ?: emptyList()

        if (subscriptions.isEmpty()) {
            binding.layoutSub0.visibility = View.GONE
            binding.layoutSub1.visibility = View.GONE
            showToast(R.string.no_active_sim)
            return
        }

        subIdPhone0 = subscriptions[0].subscriptionId
        binding.textNameSub0.text = subscriptions[0].displayName
        binding.layoutSub0.visibility = View.VISIBLE

        if (subscriptions.size < 2) {
            binding.layoutSub1.visibility = View.GONE
            return
        }
        subIdPhone1 = subscriptions[1].subscriptionId
        binding.textNameSub1.text = subscriptions[1].displayName
        binding.layoutSub1.visibility = View.VISIBLE
    }
}
