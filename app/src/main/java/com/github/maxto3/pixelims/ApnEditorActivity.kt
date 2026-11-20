package com.github.maxto3.pixelims

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.github.maxto3.pixelims.databinding.ActivityApnEditorBinding
import com.topjohnwu.superuser.ipc.RootService

/**
 * Lists carrier APNs and lets the user edit the "type" field through the root service.
 * Fixing a default APN that wrongly includes "ims" is required for IMS PDN setup.
 */
class ApnEditorActivity : Activity() {

    private lateinit var binding: ActivityApnEditorBinding
    private var carrierService: ICarrierConfigRootService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            carrierService = ICarrierConfigRootService.Stub.asInterface(service)
            reload()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carrierService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApnEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnSanitizeIms.setOnClickListener { sanitize() }
    }

    override fun onStart() {
        super.onStart()
        RootService.bind(Intent(this, CarrierConfigRootService::class.java), serviceConnection)
    }

    override fun onDestroy() {
        super.onDestroy()
        RootService.unbind(serviceConnection)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun sanitize() {
        val svc = carrierService ?: return
        Thread {
            val changed = runCatching { svc.sanitizeApnImsType() }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (changed) R.string.apn_editor_sanitized else R.string.apn_editor_no_change,
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }
        }.start()
    }

    private fun reload() {
        val svc = carrierService ?: return
        Thread {
            val raw = runCatching { svc.queryApnList() }.getOrDefault("")
            val rows = raw.lineSequence()
                .filter { it.startsWith("Row:") }
                .mapNotNull { parseRow(it) }
                .toList()
            runOnUiThread { render(rows) }
        }.start()
    }

    private data class ApnRow(val id: Int, val apn: String, val type: String)

    private fun parseRow(line: String): ApnRow? {
        val id = Regex("_id=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val apn = Regex("apn=([^,]*)").find(line)?.groupValues?.get(1).orEmpty()
        val type = line.substringAfter("type=", "")
        return ApnRow(id, apn, type)
    }

    private fun render(rows: List<ApnRow>) {
        binding.apnListContainer.removeAllViews()
        if (rows.isEmpty()) {
            binding.apnListContainer.addView(TextView(this).apply {
                text = getString(R.string.apn_editor_empty)
                setTextColor(Color.GRAY)
            })
            return
        }
        for (row in rows) {
            val view = TextView(this).apply {
                text = "${row.apn}  [${row.type}]"
                textSize = 13f
                setPadding(0, 12, 0, 12)
                setOnClickListener { editType(row) }
            }
            binding.apnListContainer.addView(view)
        }
    }

    private fun editType(row: ApnRow) {
        val input = EditText(this).apply {
            setText(row.type)
            hint = getString(R.string.apn_editor_type_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(row.apn)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val svc = carrierService ?: return@setPositiveButton
                val newType = input.text.toString().trim()
                if (newType.isEmpty()) return@setPositiveButton
                Thread {
                    val ok = runCatching { svc.updateApnType(row.id, newType) }.getOrDefault(false)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) R.string.apn_editor_updated else R.string.init_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
