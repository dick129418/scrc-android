package com.scrc.android

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.scrc.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var historyStore: ConnectionHistoryStore
    private var selectedPreset: ResolutionPreset = ResolutionPreset.ADAPT
    private var scanJob: Job? = null
    private var appListJob: Job? = null
    private var appMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyStore = ConnectionHistoryStore.from(this)
        val prefs = getSharedPreferences(ConnectionHistoryStore.PREFS, MODE_PRIVATE)

        binding.inputHost.setText(prefs.getString(KEY_HOST, "192.168.1."))
        binding.inputPort.setText(prefs.getString(KEY_PORT, "5555"))
        binding.inputMaxSize.setText(prefs.getString(KEY_CUSTOM_MAX, "1280"))
        binding.switchPowerSave.isChecked = prefs.getBoolean(KEY_POWER_SAVE, false)

        val savedLabel = prefs.getString(KEY_PRESET, ResolutionPreset.ADAPT.label)
        selectedPreset = ResolutionPreset.fromLabel(savedLabel.orEmpty())

        appMode = prefs.getBoolean(KEY_APP_MODE, false)
        binding.toggleMode.check(if (appMode) R.id.modeApp else R.id.modeMirror)
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            appMode = checkedId == R.id.modeApp
            updateModeUi()
        }
        updateModeUi()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            ResolutionPreset.labels(),
        )
        binding.inputResolution.setAdapter(adapter)
        binding.inputResolution.setText(selectedPreset.label, false)
        binding.inputResolution.setOnItemClickListener { _, _, position, _ ->
            selectedPreset = ResolutionPreset.entries[position]
            updateResolutionUi()
        }
        updateResolutionUi()
        renderHistory()

        binding.btnConnect.setOnClickListener { connectCurrent() }
        binding.btnScan.setOnClickListener {
            if (scanJob?.isActive == true) {
                cancelScan()
            } else {
                startScan()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        appListJob?.cancel()
        super.onDestroy()
    }

    private fun connectCurrent() {
        val host = binding.inputHost.text?.toString()?.trim().orEmpty()
        val port = binding.inputPort.text?.toString()?.toIntOrNull() ?: 5555
        val custom = binding.inputMaxSize.text?.toString()?.toIntOrNull()

        if (host.isEmpty()) {
            Toast.makeText(this, "请输入被控手机 IP", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedPreset == ResolutionPreset.CUSTOM && (custom == null || custom <= 0)) {
            Toast.makeText(this, "请输入有效的自定义最大边长", Toast.LENGTH_SHORT).show()
            return
        }

        val maxSize = selectedPreset.resolveMaxSize(this, custom)
        val powerSave = binding.switchPowerSave.isChecked
        val prefs = getSharedPreferences(ConnectionHistoryStore.PREFS, MODE_PRIVATE)
        prefs.edit {
            putString(KEY_HOST, host)
            putString(KEY_PORT, port.toString())
            putString(KEY_PRESET, selectedPreset.label)
            putString(KEY_CUSTOM_MAX, custom?.toString() ?: "1280")
            putBoolean(KEY_POWER_SAVE, powerSave)
            putBoolean(KEY_APP_MODE, appMode)
        }
        historyStore.remember(host, port)
        renderHistory()

        if (appMode) {
            loadAppsAndPick(host, port, maxSize, powerSave)
        } else {
            binding.textStatus.text = getString(
                R.string.status_connecting_fmt,
                selectedPreset.label,
                if (maxSize == 0) "原始" else maxSize.toString(),
            )
            startMirror(
                SessionConfig(host = host, port = port, maxSize = maxSize),
                powerSave,
            )
        }
    }

    private fun loadAppsAndPick(host: String, port: Int, maxSize: Int, powerSave: Boolean) {
        if (appListJob?.isActive == true) return
        binding.btnConnect.isEnabled = false
        binding.textStatus.text = getString(R.string.action_load_apps)

        appListJob = lifecycleScope.launch {
            try {
                val apps = RemoteAppLister.listApps(applicationContext, host, port)
                if (apps.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.app_list_empty, Toast.LENGTH_SHORT).show()
                    binding.textStatus.text = getString(R.string.app_list_empty)
                    return@launch
                }
                binding.textStatus.text = getString(R.string.status_idle)
                showAppPicker(apps) { app ->
                    val display = ResolutionPreset.localDisplaySpec(this@MainActivity)
                    binding.textStatus.text = getString(
                        R.string.status_connecting_fmt,
                        "${app.name} · $display",
                        if (maxSize == 0) "原始" else maxSize.toString(),
                    )
                    startMirror(
                        SessionConfig(
                            host = host,
                            port = port,
                            maxSize = maxSize,
                            newDisplay = display,
                            startAppPackage = app.packageName,
                        ),
                        powerSave,
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                binding.textStatus.text = getString(R.string.app_list_failed, msg)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.app_list_failed, msg),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                binding.btnConnect.isEnabled = true
                appListJob = null
            }
        }
    }

    private fun showAppPicker(apps: List<DeviceApp>, onPicked: (DeviceApp) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val search = view.findViewById<EditText>(R.id.inputAppSearch)
        val listView = view.findViewById<ListView>(R.id.listApps)
        val adapter = AppListAdapter(apps)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_pick_app)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            dialog.dismiss()
            onPicked(app)
        }
        dialog.show()
    }

    private fun startMirror(config: SessionConfig, powerSave: Boolean) {
        startActivity(
            Intent(this, MirrorActivity::class.java).apply {
                putExtra(MirrorActivity.EXTRA_HOST, config.host)
                putExtra(MirrorActivity.EXTRA_PORT, config.port)
                putExtra(MirrorActivity.EXTRA_MAX_SIZE, config.maxSize)
                putExtra(MirrorActivity.EXTRA_POWER_SAVE, powerSave)
                putExtra(MirrorActivity.EXTRA_NEW_DISPLAY, config.newDisplay)
                putExtra(MirrorActivity.EXTRA_START_APP, config.startAppPackage)
            },
        )
    }

    private fun fillAddress(host: String, port: Int) {
        binding.inputHost.setText(host)
        binding.inputPort.setText(port.toString())
        binding.textStatus.text = getString(R.string.status_idle)
    }

    private fun startScan() {
        val inputPort = binding.inputPort.text?.toString()?.toIntOrNull()
        val ports = buildSet {
            add(5555)
            if (inputPort != null && inputPort in 1..65535) add(inputPort)
        }

        val subnets = LanScanner.localSubnets()
        if (subnets.isEmpty()) {
            Toast.makeText(this, R.string.scan_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        binding.listDiscovered.removeAllViews()
        binding.listDiscovered.visibility = View.GONE
        binding.labelDiscovered.visibility = View.VISIBLE
        binding.btnScan.setText(R.string.action_scan_cancel)

        val subnetHint = subnets.joinToString("、") { "${it.selfHost} (${it.prefix}.0/24)" }
        binding.textStatus.text = getString(R.string.scan_subnet_hint, subnetHint)

        scanJob = lifecycleScope.launch {
            try {
                val found = LanScanner.scan(
                    ports = ports,
                    onProgress = { scanned, total ->
                        withContext(Dispatchers.Main) {
                            binding.textStatus.text =
                                getString(R.string.scan_progress, scanned, total)
                        }
                    },
                    onFound = { device ->
                        withContext(Dispatchers.Main) {
                            appendDiscovered(device)
                        }
                    },
                )
                binding.textStatus.text = getString(R.string.scan_done, found.size)
                if (found.isEmpty()) {
                    binding.labelDiscovered.visibility = View.GONE
                }
            } finally {
                binding.btnScan.setText(R.string.action_scan)
                scanJob = null
            }
        }
    }

    private fun cancelScan() {
        scanJob?.cancel()
        binding.textStatus.text = getString(R.string.scan_cancelled)
    }

    private fun appendDiscovered(device: DiscoveredDevice) {
        binding.labelDiscovered.visibility = View.VISIBLE
        binding.listDiscovered.visibility = View.VISIBLE
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_address_row, binding.listDiscovered, false)
        row.findViewById<TextView>(R.id.textPrimary).text = device.host
        row.findViewById<TextView>(R.id.textSecondary).apply {
            visibility = View.VISIBLE
            text = getString(R.string.discovered_secondary, device.port)
        }
        row.setOnClickListener { fillAddress(device.host, device.port) }
        binding.listDiscovered.addView(row)
    }

    private fun renderHistory() {
        val entries = historyStore.load()
        binding.listHistory.removeAllViews()

        if (entries.isEmpty()) {
            binding.textHistoryEmpty.visibility = View.VISIBLE
            binding.listHistory.visibility = View.GONE
            return
        }

        binding.textHistoryEmpty.visibility = View.GONE
        binding.listHistory.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_address_row, binding.listHistory, false)
            row.findViewById<TextView>(R.id.textPrimary).text = entry.host
            row.findViewById<TextView>(R.id.textSecondary).apply {
                visibility = View.VISIBLE
                text = if (entry.deviceName.isNullOrBlank()) {
                    getString(R.string.history_secondary, entry.port)
                } else {
                    getString(R.string.history_secondary_named, entry.deviceName, entry.port)
                }
            }
            row.findViewById<ImageButton>(R.id.btnRemove).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    historyStore.remove(entry.host, entry.port)
                    renderHistory()
                }
            }
            row.setOnClickListener { fillAddress(entry.host, entry.port) }
            binding.listHistory.addView(row)
        }
    }

    private fun updateModeUi() {
        binding.textModeHint.setText(
            if (appMode) R.string.mode_hint_app else R.string.mode_hint_mirror,
        )
        binding.btnConnect.setText(
            if (appMode) R.string.action_connect_app else R.string.action_connect,
        )
    }

    private fun updateResolutionUi() {
        val custom = selectedPreset == ResolutionPreset.CUSTOM
        binding.layoutCustomMaxSize.visibility = if (custom) View.VISIBLE else View.GONE

        binding.textResolutionHint.text = when (selectedPreset) {
            ResolutionPreset.ADAPT -> {
                val size = ResolutionPreset.adaptToLocalScreen(this)
                getString(R.string.resolution_hint_adapt, size)
            }
            ResolutionPreset.ORIGINAL -> getString(R.string.resolution_hint_original)
            ResolutionPreset.CUSTOM -> getString(R.string.resolution_hint_custom)
            else -> getString(
                R.string.resolution_hint_fixed,
                selectedPreset.resolveMaxSize(this, null),
            )
        }
    }

    private inner class AppListAdapter(
        private val all: List<DeviceApp>,
    ) : BaseAdapter() {
        private var filtered: List<DeviceApp> = all

        fun filter(query: String) {
            val q = query.trim()
            filtered = if (q.isEmpty()) {
                all
            } else {
                all.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = filtered.size
        override fun getItem(position: Int): DeviceApp = filtered[position]
        override fun getItemId(position: Int): Long = filtered[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(
                R.layout.item_address_row,
                parent,
                false,
            )
            val app = getItem(position)
            row.findViewById<TextView>(R.id.textPrimary).text = app.name
            row.findViewById<TextView>(R.id.textSecondary).apply {
                visibility = View.VISIBLE
                text = if (app.system) {
                    "${app.packageName} · ${getString(R.string.app_system_tag)}"
                } else {
                    app.packageName
                }
            }
            row.findViewById<ImageButton>(R.id.btnRemove).visibility = View.GONE
            return row
        }
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PRESET = "preset"
        private const val KEY_CUSTOM_MAX = "custom_max"
        private const val KEY_POWER_SAVE = "power_save"
        private const val KEY_APP_MODE = "app_mode"
    }
}
