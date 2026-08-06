package com.scrc.android

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        const val EXTRA_USB = "usb"
        private const val KEY_POWER_SAVE = "power_save"
        private const val AUTO_COLLAPSE_MS = 3_000L
        private const val MAX_RECONNECT = 5
        private const val STATS_INTERVAL_MS = 1_000L
    }

    private lateinit var binding: ActivityMirrorBinding
    private lateinit var sessionConfig: SessionConfig
    private var session: ScrcpySession? = null
    private var clipboardBridge: ClipboardBridge? = null
    private var gestureController: MirrorGestureController? = null
    private var controlReady = false
    private var overlayExpanded = true
    private var keyboardOpen = false
    private var modCtrl = false
    private var modAlt = false
    private var modShift = false
    private var modWin = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var suppressInputSync = false
    private var userStopping = false
    private var reconnectAttempt = 0
    private var statusBase = ""
    private var inPip = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapseOverlay() }
    private val syncInputRunnable = Runnable { flushLocalInput() }
    private val reconnectRunnable = Runnable { startSession() }
    private val statsRunnable = object : Runnable {
        override fun run() {
            refreshStatsLine()
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMirrorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.holder.addCallback(this)
        binding.surfaceView.isClickable = true
        binding.surfaceView.isFocusable = true
        gestureController = MirrorGestureController(binding.surfaceView) { handleTouch(it) }
        binding.surfaceView.setOnTouchListener { _, event ->
            if (inPip) return@setOnTouchListener true
            gestureController?.onTouch(event) ?: handleTouch(event)
            true
        }
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (videoWidth > 0 && videoHeight > 0) {
                applyLetterbox(videoWidth, videoHeight)
            }
        }

        setupFloatOverlay()
        setupKeyboardPanel()
        setupPipButton()

        clipboardBridge = ClipboardBridge(this) { text ->
            session?.control()?.setClipboard(text, paste = false)
        }

        val preferPowerSave = intent.getBooleanExtra(EXTRA_POWER_SAVE, false)
        binding.switchPowerSave.isChecked = preferPowerSave
        binding.switchPowerSave.setOnCheckedChangeListener { _, checked ->
            persistPowerSave(checked)
            scheduleAutoCollapse()
            if (controlReady) applyDisplayPower(blackout = checked)
        }
        binding.btnKeyboard.setOnClickListener {
            if (keyboardOpen) hideKeyboardPanel() else showKeyboardPanel()
        }
        binding.btnDisconnect.setOnClickListener {
            userStopping = true
            mainHandler.removeCallbacks(autoCollapseRunnable)
            mainHandler.removeCallbacks(reconnectRunnable)
            mainHandler.removeCallbacks(statsRunnable)
            clipboardBridge?.stop()
            session?.stop()
            session = null
            finish()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (keyboardOpen) hideKeyboardPanel()
                    else session?.control()?.injectKeyClick(ScrcpyConstants.KEYCODE_BACK)
                }
            },
        )

        sessionConfig = SessionConfig(
            host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
            port = intent.getIntExtra(EXTRA_PORT, 5555),
            maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, 1280),
            newDisplay = intent.getStringExtra(EXTRA_NEW_DISPLAY),
            startAppPackage = intent.getStringExtra(EXTRA_START_APP),
            usb = intent.getBooleanExtra(EXTRA_USB, false),
        )
        startSession()
        scheduleAutoCollapse()
    }

    private fun setupPipButton() {
        val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        binding.btnPip.visibility = if (supported) View.VISIBLE else View.GONE
        binding.btnPip.setOnClickListener {
            enterPip()
            scheduleAutoCollapse()
        }
    }

    private fun enterPip() {
        if (!controlReady || inPip) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val w = videoWidth.coerceAtLeast(9)
        val h = videoHeight.coerceAtLeast(16)
        enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(w, h)).build(),
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (controlReady && !inPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            if (keyboardOpen) hideKeyboardPanel()
            binding.floatOverlay.visibility = View.GONE
            clipboardBridge?.setPaused(true)
        } else {
            binding.floatOverlay.visibility = View.VISIBLE
            clipboardBridge?.setPaused(false)
            expandOverlay(resetTimer = true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controlReady &&
            !binding.inputRemote.isFocused &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                session?.control()?.injectKeyClick(event.keyCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startSession() {
        if (userStopping || isFinishing) return
        val s = ScrcpySession(applicationContext, sessionConfig, this)
        session = s
        val surface = binding.surfaceView.holder.surface
        if (surface?.isValid == true) s.setSurface(surface)
        lifecycleScope.launch { s.start() }
    }

    override fun onDestroy() {
        userStopping = true
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.removeCallbacks(syncInputRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(statsRunnable)
        clipboardBridge?.stop()
        clipboardBridge = null
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
            statusBase = message
            binding.textMirrorStatus.text = message
            if (!controlReady) expandOverlay(resetTimer = true)
        }
    }

    override fun onConnected(deviceName: String) {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 5555)
        if (host.isNotEmpty()) {
            ConnectionHistoryStore.from(this).remember(host, port, deviceName, incrementLaunch = false)
        }
        runOnUiThread {
            reconnectAttempt = 0
            statusBase = "已连接：$deviceName"
            binding.textMirrorStatus.text = statusBase
            Toast.makeText(this, "已连接 $deviceName", Toast.LENGTH_SHORT).show()
            controlReady = true
            clipboardBridge?.start()
            mainHandler.removeCallbacks(statsRunnable)
            mainHandler.post(statsRunnable)
            if (binding.switchPowerSave.isChecked) applyDisplayPower(blackout = true)
            expandOverlay(resetTimer = true)
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            videoWidth = width
            videoHeight = height
            statusBase = "画面 ${width}x$height"
            binding.textMirrorStatus.text = statusBase
            applyLetterbox(width, height)
            if (inPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(width.coerceAtLeast(9), height.coerceAtLeast(16)))
                        .build(),
                )
            }
        }
    }

    override fun onRemoteClipboard(text: String) {
        runOnUiThread { clipboardBridge?.onRemoteText(text) }
    }

    override fun onDisconnected(error: String?) {
        runOnUiThread {
            controlReady = false
            session = null
            clipboardBridge?.stop()
            mainHandler.removeCallbacks(statsRunnable)
            if (userStopping || isFinishing || error == null) {
                if (!isFinishing) finish()
                return@runOnUiThread
            }
            if (reconnectAttempt >= MAX_RECONNECT) {
                Toast.makeText(this, "重连失败：$error", Toast.LENGTH_LONG).show()
                finish()
                return@runOnUiThread
            }
            reconnectAttempt++
            statusBase = "断链，正在重连 ($reconnectAttempt/$MAX_RECONNECT)…"
            binding.textMirrorStatus.text = statusBase
            expandOverlay(resetTimer = true)
            mainHandler.removeCallbacks(reconnectRunnable)
            mainHandler.postDelayed(reconnectRunnable, 1000L * reconnectAttempt)
        }
    }

    private fun refreshStatsLine() {
        if (!controlReady || inPip) return
        val s = session ?: return
        val (fps, latencyMs) = s.snapshotStats()
        val base = statusBase.ifBlank { "投屏中" }
        binding.textMirrorStatus.text = getString(R.string.status_stats_fmt, base, fps, latencyMs)
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
        binding.textMirrorStatus.setOnTouchListener(FloatDragTouchListener(expandOnTap = false))
        binding.dotOverlay.setOnTouchListener(FloatDragTouchListener(expandOnTap = true))
    }

    private fun setupKeyboardPanel() {
        binding.inputRemote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressInputSync) return
                mainHandler.removeCallbacks(syncInputRunnable)
                mainHandler.post(syncInputRunnable)
            }
        })
        binding.inputRemote.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                flushLocalInput()
                injectKeyWithMods(KeyEvent.KEYCODE_ENTER)
                true
            } else {
                false
            }
        }
        bindModToggle(binding.btnModCtrl) { modCtrl = it }
        bindModToggle(binding.btnModAlt) { modAlt = it }
        bindModToggle(binding.btnModShift) { modShift = it }
        bindModToggle(binding.btnModWin) { modWin = it }
        binding.btnInputDel.setOnClickListener {
            flushLocalInput()
            injectKeyWithMods(KeyEvent.KEYCODE_DEL)
        }
        binding.btnInputEnter.setOnClickListener {
            flushLocalInput()
            injectKeyWithMods(KeyEvent.KEYCODE_ENTER)
        }
        binding.btnInputClose.setOnClickListener { hideKeyboardPanel() }
        refreshModUi()
    }

    private fun bindModToggle(btn: TextView, set: (Boolean) -> Unit) {
        btn.setOnClickListener {
            val next = btn.tag != true
            btn.tag = next
            set(next)
            refreshModUi()
        }
    }

    private fun metaState(): Int {
        var m = 0
        if (modShift) m = m or KeyEvent.META_SHIFT_ON
        if (modAlt) m = m or KeyEvent.META_ALT_ON
        if (modCtrl) m = m or KeyEvent.META_CTRL_ON
        if (modWin) m = m or KeyEvent.META_META_ON
        return m
    }

    private fun refreshModUi() {
        styleMod(binding.btnModCtrl, modCtrl)
        styleMod(binding.btnModAlt, modAlt)
        styleMod(binding.btnModShift, modShift)
        styleMod(binding.btnModWin, modWin)
        val parts = buildList {
            if (modCtrl) add("Ctrl")
            if (modAlt) add("Alt")
            if (modShift) add("Shift")
            if (modWin) add("Win")
        }
        if (parts.isEmpty()) {
            binding.textModStatus.visibility = View.GONE
        } else {
            binding.textModStatus.visibility = View.VISIBLE
            binding.textModStatus.text = getString(R.string.mod_status_fmt, parts.joinToString(" + "))
        }
    }

    private fun styleMod(btn: TextView, on: Boolean) {
        btn.tag = on
        btn.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent else R.color.white))
    }

    private fun clearModifiers() {
        modCtrl = false
        modAlt = false
        modShift = false
        modWin = false
        refreshModUi()
    }

    private fun injectKeyWithMods(keycode: Int) {
        val meta = metaState()
        session?.control()?.injectKeyClick(keycode, meta)
        if (meta != 0) clearModifiers()
    }

    private fun showKeyboardPanel() {
        if (!controlReady) {
            Toast.makeText(this, "尚未连接", Toast.LENGTH_SHORT).show()
            return
        }
        keyboardOpen = true
        mainHandler.removeCallbacks(autoCollapseRunnable)
        collapseOverlay()
        binding.panelKeyboard.visibility = View.VISIBLE
        binding.inputRemote.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.inputRemote, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboardPanel() {
        keyboardOpen = false
        mainHandler.removeCallbacks(syncInputRunnable)
        flushLocalInput()
        clearModifiers()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.inputRemote.windowToken, 0)
        binding.panelKeyboard.visibility = View.GONE
        binding.surfaceView.requestFocus()
    }

    private fun clearLocalInput() {
        suppressInputSync = true
        binding.inputRemote.setText("")
        suppressInputSync = false
    }

    private fun flushLocalInput() {
        if (suppressInputSync) return
        val editable = binding.inputRemote.text ?: return
        if (android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editable) >= 0) {
            mainHandler.postDelayed(syncInputRunnable, 40)
            return
        }
        val text = editable.toString()
        if (text.isEmpty()) return
        val control = session?.control() ?: return
        val meta = metaState()
        if (meta != 0) {
            for (ch in text) {
                val code = charToKeycode(ch)
                if (code != null) control.injectKeyClick(code, meta)
                else control.injectOrPaste(ch.toString())
            }
            clearModifiers()
        } else {
            clipboardBridge?.noteLocalText(text)
            control.injectOrPaste(text)
        }
        clearLocalInput()
    }

    private fun charToKeycode(ch: Char): Int? = when (ch) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
        in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
        ' ' -> KeyEvent.KEYCODE_SPACE
        '\n' -> KeyEvent.KEYCODE_ENTER
        '\t' -> KeyEvent.KEYCODE_TAB
        else -> null
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (overlayExpanded && !keyboardOpen && !inPip) {
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
        }
    }

    private fun expandOverlay(resetTimer: Boolean) {
        if (inPip) return
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
        if (root.width <= 0 || root.height <= 0 || float.width <= 0) return tx to ty
        val maxTx = (root.width - float.left - float.width).toFloat().coerceAtLeast(0f)
        val maxTy = (root.height - float.top - float.height).toFloat().coerceAtLeast(0f)
        val minTx = -float.left.toFloat()
        val minTy = -float.top.toFloat()
        return tx.coerceIn(minTx, maxTx) to ty.coerceIn(minTy, maxTy)
    }

    private fun applyDisplayPower(blackout: Boolean) {
        session?.control()?.setDisplayPower(!blackout)
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
        if (keyboardOpen && action == ScrcpyConstants.ACTION_UP) {
            binding.inputRemote.post { binding.inputRemote.requestFocus() }
        }
        return true
    }

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
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        val (tx, ty) = clampOverlayTranslation(downTransX + dx, downTransY + dy)
                        float.translationX = tx
                        float.translationY = ty
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (expandOnTap) expandOverlay(resetTimer = true)
                        else scheduleAutoCollapse()
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
