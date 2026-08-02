package com.scrc.android

import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scrc.android.databinding.ActivityMirrorBinding
import kotlinx.coroutines.launch

class MirrorActivity : AppCompatActivity(), SurfaceHolder.Callback, ScrcpySession.Listener {
    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_MAX_SIZE = "max_size"
    }

    private lateinit var binding: ActivityMirrorBinding
    private var session: ScrcpySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMirrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.holder.addCallback(this)
        binding.surfaceView.isClickable = true
        binding.surfaceView.isFocusable = true
        binding.surfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        // 系统返回键注入到被控端；再按一次系统返回可退出本页（由用户从最近任务划掉亦可）
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    session?.control()?.injectKeyClick(ScrcpyConstants.KEYCODE_BACK)
                }
            },
        )

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 5555)
        val maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, 1280)
        session = ScrcpySession(applicationContext, host, port, maxSize, this)

        lifecycleScope.launch {
            session?.start()
        }
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        session?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        session?.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session?.setSurface(null)
    }

    override fun onStatus(message: String) {
        runOnUiThread { binding.textMirrorStatus.text = message }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            binding.textMirrorStatus.text = "已连接：$deviceName"
            Toast.makeText(this, "已连接 $deviceName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            binding.textMirrorStatus.text = "画面 ${width}x$height"
        }
    }

    override fun onDisconnected(error: String?) {
        runOnUiThread {
            if (error != null) {
                Toast.makeText(this, "断开：$error", Toast.LENGTH_LONG).show()
            }
            if (!isFinishing) finish()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val s = session ?: return false
        val videoW = s.getVideoWidth()
        val videoH = s.getVideoHeight()
        if (videoW <= 0 || videoH <= 0) return false

        val viewW = binding.surfaceView.width.toFloat()
        val viewH = binding.surfaceView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return false

        // SurfaceView + MediaCodec 默认铺满整个 Surface，按拉伸映射坐标
        val x = (event.x / viewW * videoW).toInt().coerceIn(0, videoW - 1)
        val y = (event.y / viewH * videoH).toInt().coerceIn(0, videoH - 1)

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> ScrcpyConstants.ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ScrcpyConstants.ACTION_UP
            MotionEvent.ACTION_MOVE -> ScrcpyConstants.ACTION_MOVE
            else -> return false
        }

        val pressure = if (action == ScrcpyConstants.ACTION_UP) {
            0f
        } else {
            event.pressure.coerceIn(0f, 1f).let { if (it <= 0f) 1f else it }
        }

        try {
            s.control()?.injectTouch(action, x, y, videoW, videoH, pressure)
        } catch (_: Exception) {
        }
        return true
    }
}
