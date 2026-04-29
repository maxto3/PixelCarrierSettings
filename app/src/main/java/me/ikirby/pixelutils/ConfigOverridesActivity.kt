package me.ikirby.pixelutils

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PersistableBundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.topjohnwu.superuser.ipc.RootService
import me.ikirby.pixelutils.databinding.ActivityConfigOverridesBinding

class ConfigOverridesActivity : Activity() {

    private lateinit var binding: ActivityConfigOverridesBinding

    private var subId = 0
    private var carrierService: ICarrierConfigRootService? = null

    // Feature tracking: label -> (statusView, success)
    private data class FeatureStatus(
        val labelResId: Int,
        val statusView: () -> TextView,
        var applied: Boolean? = null   // null = not attempted yet
    )

    private val featureStatuses = mutableMapOf<String, FeatureStatus>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            carrierService = ICarrierConfigRootService.Stub.asInterface(service)
            // Show "Ready" for all features on connect
            for ((_, fs) in featureStatuses) {
                fs.statusView().text = getString(R.string.feat_pending, getString(fs.labelResId))
                fs.statusView().setTextColor(Color.GRAY)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carrierService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConfigOverridesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subId = intent.getIntExtra("subId", 0)
        title = intent.getStringExtra("displayName") ?: ""
        actionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize feature statuses (order must match enableAllFeatures steps)
        featureStatuses["volte"] = FeatureStatus(R.string.feat_volte_label, statusView = { binding.textStatusVolte })
        featureStatuses["nr_sa"] = FeatureStatus(R.string.feat_nr_sa_label, statusView = { binding.textStatusNrSa })
        featureStatuses["vonr"] = FeatureStatus(R.string.feat_vonr_label, statusView = { binding.textStatusVonr })
        featureStatuses["wfc"] = FeatureStatus(R.string.feat_wfc_label, statusView = { binding.textStatusWfc })
        featureStatuses["5g_threshold"] = FeatureStatus(R.string.feat_5g_threshold_label, statusView = { binding.textStatus5gThreshold })
        featureStatuses["signal_inflate"] = FeatureStatus(R.string.feat_signal_inflate_label, statusView = { binding.textStatusSignalInflate })
        featureStatuses["ims_status"] = FeatureStatus(R.string.feat_ims_status_label, statusView = { binding.textStatusImsStatus })
        featureStatuses["show_4g"] = FeatureStatus(R.string.feat_show_4g_label, statusView = { binding.textStatusShow4g })

        // Show "Initializing" placeholders before service connects
        for ((_, fs) in featureStatuses) {
            fs.statusView().text = getString(R.string.initializing)
            fs.statusView().setTextColor(Color.GRAY)
        }

        binding.btnEnableAll.setOnClickListener { enableAllFeatures() }
        binding.btnResetConfig.setOnClickListener { resetConfig() }
    }

    override fun onStart() {
        super.onStart()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        RootService.unbind(serviceConnection)
    }

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
        finish()
        return true
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun init() {
        val intent = Intent(this, CarrierConfigRootService::class.java)
        RootService.bind(intent, serviceConnection)
    }

    private fun ensureService(): ICarrierConfigRootService? {
        val svc = carrierService
        if (svc == null) {
            showToast(R.string.init_failed)
        }
        return svc
    }

    private fun updateStatus(key: String, success: Boolean) {
        val fs = featureStatuses[key] ?: return
        fs.applied = success
        val label = getString(fs.labelResId)
        if (success) {
            fs.statusView().text = getString(R.string.feat_status_applied, label)
            fs.statusView().setTextColor(Color.parseColor("#4CAF50")) // green
        } else {
            fs.statusView().text = getString(R.string.feat_status_rejected, label)
            fs.statusView().setTextColor(Color.parseColor("#FF5722")) // red/orange
        }
    }

    private fun resetAllStatuses() {
        for ((_, fs) in featureStatuses) {
            fs.applied = null
            fs.statusView().text = getString(R.string.feat_pending, getString(fs.labelResId))
            fs.statusView().setTextColor(Color.GRAY)
        }
    }

    // Step definition
    private data class FeatureStep(
        val descriptionResId: Int,
        val doneResId: Int,
        val failResId: Int,
        val statusKey: String,
        val action: (ICarrierConfigRootService) -> Boolean
    )

    private fun enableAllFeatures() {
        val svc = ensureService() ?: return

        binding.textProgress.visibility = View.VISIBLE
        binding.btnEnableAll.isEnabled = false

        val steps = listOf(
            FeatureStep(R.string.step_volte, R.string.step_volte_done, R.string.step_volte_fail, "volte") { s -> overrideVoLTE(s) },
            FeatureStep(R.string.step_nr_sa, R.string.step_nr_sa_done, R.string.step_nr_sa_fail, "nr_sa") { s -> overrideNRMode(s) },
            FeatureStep(R.string.step_vonr, R.string.step_vonr_done, R.string.step_vonr_fail, "vonr") { s -> overrideVoNR(s) },
            FeatureStep(R.string.step_wfc, R.string.step_wfc_done, R.string.step_wfc_fail, "wfc") { s -> overrideWFC(s) },
            FeatureStep(R.string.step_5g_threshold, R.string.step_5g_threshold_done, R.string.step_5g_threshold_fail, "5g_threshold") { s -> override5GSignalThreshold(s) },
            FeatureStep(R.string.step_signal_inflate, R.string.step_signal_inflate_done, R.string.step_signal_inflate_fail, "signal_inflate") { s -> overrideSignalInflate(s) },
            FeatureStep(R.string.step_ims_status, R.string.step_ims_status_done, R.string.step_ims_status_fail, "ims_status") { s -> overrideShowIMSStatus(s) },
            FeatureStep(R.string.step_show_4g, R.string.step_show_4g_done, R.string.step_show_4g_fail, "show_4g") { s -> overrideShow4G(s) },
        )

        val handler = Handler(Looper.getMainLooper())
        var delay = 0L
        val stepInterval = 400L

        for ((index, step) in steps.withIndex()) {
            handler.postDelayed({
                binding.textProgress.text = getString(step.descriptionResId)
                try {
                    val success = step.action(svc)
                    updateStatus(step.statusKey, success)
                    showToast(if (success) step.doneResId else step.failResId)
                } catch (_: Exception) {
                    updateStatus(step.statusKey, false)
                    showToast(step.failResId)
                }

                if (index == steps.lastIndex) {
                    binding.textProgress.text = getString(R.string.all_features_done)
                    binding.btnEnableAll.isEnabled = true
                }
            }, delay)
            delay += stepInterval
        }
    }

    private fun applyOverrides(svc: ICarrierConfigRootService, overrides: PersistableBundle?): Boolean {
        val persist = binding.switchPersist.isChecked
        val success = try {
            svc.overrideCarrierConfig(subId, overrides, persist)
        } catch (_: Exception) {
            false
        }

        // If persist is enabled, also save to local storage as fallback
        if (persist) {
            if (overrides != null) {
                CarrierConfigPersistence.saveOverrides(this, subId, overrides)
            } else {
                // Reset: clear persisted overrides
                CarrierConfigPersistence.clearOverrides(this, subId)
            }
        }

        return success
    }

    private fun resetConfig() {
        val svc = ensureService() ?: return
        applyOverrides(svc, null)
        // Always clear local persistence on reset
        CarrierConfigPersistence.clearOverrides(this, subId)
        showToast(R.string.config_reset)
        resetAllStatuses()
    }

    private fun overrideVoLTE(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putBoolean("carrier_volte_available_bool", true)
        }
        return applyOverrides(svc, overrides)
    }

    private fun overrideShowIMSStatus(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putBoolean("show_ims_registration_status_bool", true)
        }
        return applyOverrides(svc, overrides)
    }

    private fun overrideVoNR(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putBoolean("vonr_enabled_bool", true)
            putBoolean("vonr_setting_visibility_bool", true)
        }
        return applyOverrides(svc, overrides)
    }

    private fun overrideNRMode(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putIntArray(
                "carrier_nr_availabilities_int_array",
                intArrayOf(
                    1, // NSA
                    2  // SA
                )
            )
        }
        return applyOverrides(svc, overrides)
    }

    private fun overrideWFC(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putBoolean("carrier_wfc_ims_available_bool", true)
            putBoolean("carrier_wfc_supports_wifi_only_bool", true)
            putBoolean("carrier_default_wfc_ims_roaming_enabled_bool", true)
            putBoolean("editable_wfc_mode_bool", true)
            putBoolean("editable_wfc_roaming_mode_bool", true)
            putInt("wfc_spn_format_idx_int", 4)
        }
        return applyOverrides(svc, overrides)
    }

    private fun override5GSignalThreshold(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putIntArray(
                "5g_nr_ssrsrp_thresholds_int_array",
                intArrayOf(-115, -105, -95, -85)
            )
        }
        return applyOverrides(svc, overrides)
    }

    private fun overrideSignalInflate(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putBoolean("inflate_signal_strength_bool", false)
        }
        return applyOverrides(svc, overrides)
    }

    private fun overrideShow4G(svc: ICarrierConfigRootService): Boolean {
        val overrides = PersistableBundle().apply {
            putBoolean("show_4g_for_lte_data_icon_bool", true)
        }
        return applyOverrides(svc, overrides)
    }
}