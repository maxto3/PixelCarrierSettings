package me.ikirby.pixelutils

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.ipc.RootService
import me.ikirby.pixelutils.databinding.ActivityMainBinding

class MainActivity : Activity() {

    companion object {
        private const val KEY_VOIMS_OPT_IN_STATUS = 68
        private const val PROVISIONING_VALUE_ENABLED = 1
        private const val PROVISIONING_VALUE_DISABLED = 0
    }

    private lateinit var binding: ActivityMainBinding

    private var subIdPhone0 = 0
    private var subIdPhone1 = 0
    private var carrierService: ICarrierConfigRootService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            carrierService = ICarrierConfigRootService.Stub.asInterface(service)
            binding.textStatus.visibility = View.GONE
            binding.contentArea.visibility = View.VISIBLE
            carrierService?.let { loadSubscriptionStatus() }
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
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        RootService.unbind(serviceConnection)
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(msg: String) {
        AlertDialog.Builder(this).apply {
            setMessage(msg)
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

        val intent = Intent(this, CarrierConfigRootService::class.java)
        RootService.bind(intent, serviceConnection)

        // 5-second timeout: if service still not connected, show error
        binding.root.postDelayed({
            if (carrierService == null) {
                binding.textStatus.text = getString(R.string.service_connection_failed)
                showToast(R.string.init_failed)
            }
        }, 5000)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            loadSubscriptionStatus()
        }
    }

    private fun loadSubscriptionStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 0)
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