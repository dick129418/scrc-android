package com.scrc.android

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.scrc.android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedPreset: ResolutionPreset = ResolutionPreset.ADAPT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        binding.inputHost.setText(prefs.getString(KEY_HOST, "192.168.1."))
        binding.inputPort.setText(prefs.getString(KEY_PORT, "5555"))
        binding.inputMaxSize.setText(prefs.getString(KEY_CUSTOM_MAX, "1280"))

        val savedLabel = prefs.getString(KEY_PRESET, ResolutionPreset.ADAPT.label)
        selectedPreset = ResolutionPreset.fromLabel(savedLabel.orEmpty())

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

        binding.btnConnect.setOnClickListener {
            val host = binding.inputHost.text?.toString()?.trim().orEmpty()
            val port = binding.inputPort.text?.toString()?.toIntOrNull() ?: 5555
            val custom = binding.inputMaxSize.text?.toString()?.toIntOrNull()

            if (host.isEmpty()) {
                Toast.makeText(this, "请输入被控手机 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedPreset == ResolutionPreset.CUSTOM && (custom == null || custom <= 0)) {
                Toast.makeText(this, "请输入有效的自定义最大边长", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val maxSize = selectedPreset.resolveMaxSize(this, custom)
            prefs.edit {
                putString(KEY_HOST, host)
                putString(KEY_PORT, port.toString())
                putString(KEY_PRESET, selectedPreset.label)
                putString(KEY_CUSTOM_MAX, custom?.toString() ?: "1280")
            }

            binding.textStatus.text = getString(
                R.string.status_connecting_fmt,
                selectedPreset.label,
                if (maxSize == 0) "原始" else maxSize.toString(),
            )

            startActivity(
                Intent(this, MirrorActivity::class.java).apply {
                    putExtra(MirrorActivity.EXTRA_HOST, host)
                    putExtra(MirrorActivity.EXTRA_PORT, port)
                    putExtra(MirrorActivity.EXTRA_MAX_SIZE, maxSize)
                },
            )
        }
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

    companion object {
        private const val PREFS = "scrc_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PRESET = "preset"
        private const val KEY_CUSTOM_MAX = "custom_max"
    }
}
