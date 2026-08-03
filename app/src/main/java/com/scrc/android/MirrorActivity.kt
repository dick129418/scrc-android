package com.scrc.android

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.scrc.android.databinding.ActivityMirrorBinding
import kotlinx.coroutines.launch
import kotlin.math.abs

class MirrorActivity : AppCompatActivity(), SurfaceHolder.Callback, ScrcpySession.Listener {
    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_MAX_SIZE = "max_size"
        const val EXTRA_POWER_SAVE = "power_save"
        const val EXTRA_NEW_DISPLAY = "new_display"
        const val EXTRA_START_APP = "start_app"
        private const val KEY_POWER_SAVE = "power_save"
        private const val AUTO_COLLAPSE_MS = 3_000L
    }

    private lateinit var binding: ActivityMirrorBinding
    private var session: ScrcpySession? = null
    private var controlReady = false
    private var overlayExpanded = true
    private var videoWidth = 0
    private var videoHeight = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapseOverlay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 投屏期间控制端不自动息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMirrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.holder.addCallback(this)
        binding.surfaceView.isClickable = true
        binding.surfaceView.isFocusable = true
        binding.surfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (videoWidth > 0 && videoHeight > 0) {
                applyLetterbox(videoWidth, videoHeight)
            }
        }

        setupFloatOverlay()

        val preferPowerSave = intent.getBooleanExtra(EXTRA_POWER_SAVE, false)
        binding.switchPowerSave.isChecked = preferPowerSave
        binding.switchPowerSave.setOnCheckedChangeListener { _, checked ->
            persistPowerSave(checked)
            scheduleAutoCollapse()
            if (controlReady) {
                applyDisplayPower(blackout = checked)
            }
        }
        binding.btnDisconnect.setOnClickListener {
            mainHandler.removeCallbacks(autoCollapseRunnable)
            session?.stop()
            session = null
            finish()
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

        val config = SessionConfig(
            host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
            port = intent.getIntExtra(EXTRA_PORT, 5555),
            maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, 1280),
            newDisplay = intent.getStringExtra(EXTRA_NEW_DISPLAY),
            startAppPackage = intent.getStringExtra(EXTRA_START_APP),
        )
        session = ScrcpySession(applicationContext, config, this)

        lifecycleScope.launch {
            session?.start()
        }
        scheduleAutoCollapse()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
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
        runOnUiThread {
            binding.textMirrorStatus.text = message
            // 连接过程中保持展开，方便看状态
            if (!controlReady) {
                expandOverlay(resetTimer = true)
            }
        }
    }

    override fun onConnected(deviceName: String) {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 5555)
        if (host.isNotEmpty()) {
            ConnectionHistoryStore.from(this).remember(host, port, deviceName, incrementLaunch = false)
        }
        runOnUiThread {
            binding.textMirrorStatus.text = "已连接：$deviceName"
            Toast.makeText(this, "已连接 $deviceName", Toast.LENGTH_SHORT).show()
            controlReady = true
            if (binding.switchPowerSave.isChecked) {
                applyDisplayPower(blackout = true)
            }
            expandOverlay(resetTimer = true)
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            videoWidth = width
            videoHeight = height
            binding.textMirrorStatus.text = "画面 ${width}x$height"
            applyLetterbox(width, height)
        }
    }

    override fun onDisconnected(error: String?) {
        runOnUiThread {
            controlReady = false
            if (error != null) {
                Toast.makeText(this, "断开：$error", Toast.LENGTH_LONG).show()
            }
            if (!isFinishing) finish()
        }
    }

    private fun applyLetterbox(videoW: Int, videoH: Int) {
        val containerW = binding.root.width
        val containerH = binding.root.height
        if (containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) return

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val viewAspect = containerW.toFloat() / containerH.toFloat()
        val lp = binding.surfaceView.layoutParams as FrameLayout.LayoutParams
        if (videoAspect > viewAspect) {
            lp.width = containerW
            lp.height = (containerW / videoAspect).toInt().coerceAtLeast(1)
        } else {
            lp.height = containerH
            lp.width = (containerH * videoAspect).toInt().coerceAtLeast(1)
        }
        lp.gravity = Gravity.CENTER
        binding.surfaceView.layoutParams = lp
    }

    private fun setupFloatOverlay() {
        binding.dotOverlay.contentDescription = getString(R.string.cd_float_dot)
        // 仅状态文字可拖动，避免抢走省电开关的触摸
        binding.textMirrorStatus.setOnTouchListener(
            FloatDragTouchListener(expandOnTap = false),
        )
        // 小点：点击展开，拖动移位
        binding.dotOverlay.setOnTouchListener(
            FloatDragTouchListener(expandOnTap = true),
        )
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (overlayExpanded) {
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
        }
    }

    private fun expandOverlay(resetTimer: Boolean) {
        if (!overlayExpanded) {
            overlayExpanded = true
            binding.dotOverlay.visibility = View.GONE
            binding.panelOverlay.visibility = View.VISIBLE
            binding.panelOverlay.alpha = 0f
            binding.panelOverlay.animate().alpha(1f).setDuration(150).start()
        }
        if (resetTimer) scheduleAutoCollapse()
    }

    private fun collapseOverlay() {
        if (!overlayExpanded) return
        overlayExpanded = false
        mainHandler.removeCallbacks(autoCollapseRunnable)
        binding.panelOverlay.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                if (!overlayExpanded) {
                    binding.panelOverlay.visibility = View.GONE
                    binding.panelOverlay.alpha = 1f
                    binding.dotOverlay.visibility = View.VISIBLE
                    binding.dotOverlay.alpha = 0f
                    binding.dotOverlay.animate().alpha(1f).setDuration(120).start()
                }
            }
            .start()
    }

    private fun clampOverlayTranslation(tx: Float, ty: Float): Pair<Float, Float> {
        val root = binding.root
        val float = binding.floatOverlay
        if (root.width <= 0 || root.height <= 0 || float.width <= 0) {
            return tx to ty
        }
        val maxTx = (root.width - float.left - float.width).toFloat().coerceAtLeast(0f)
        val maxTy = (root.height - float.top - float.height).toFloat().coerceAtLeast(0f)
        val minTx = -float.left.toFloat()
        val minTy = -float.top.toFloat()
        return tx.coerceIn(minTx, maxTx) to ty.coerceIn(minTy, maxTy)
    }

    private fun applyDisplayPower(blackout: Boolean) {
        val control = session?.control() ?: return
        control.setDisplayPower(!blackout)
    }

    private fun persistPowerSave(enabled: Boolean) {
        getSharedPreferences(ConnectionHistoryStore.PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_POWER_SAVE, enabled)
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

        // SurfaceView 已按视频比例 letterbox，区域内线性映射即可
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

    /**
     * 拖动浮层；点击（未超过 touchSlop）时：小点展开，状态文字则刷新收起计时。
     */
    private inner class FloatDragTouchListener(
        private val expandOnTap: Boolean,
    ) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@MirrorActivity).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var downTransX = 0f
        private var downTransY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val float = binding.floatOverlay
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTransX = float.translationX
                    downTransY = float.translationY
                    dragging = false
                    mainHandler.removeCallbacks(autoCollapseRunnable)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val (tx, ty) = clampOverlayTranslation(downTransX + dx, downTransY + dy)
                        float.translationX = tx
                        float.translationY = ty
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (expandOnTap) {
                            expandOverlay(resetTimer = true)
                        } else {
                            scheduleAutoCollapse()
                        }
                    } else if (overlayExpanded) {
                        scheduleAutoCollapse()
                    }
                    return true
                }
            }
            return false
        }
    }
}
