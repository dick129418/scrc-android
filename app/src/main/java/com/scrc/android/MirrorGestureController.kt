package com.scrc.android

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/** 客户端捏合缩放 / 双指平移 / 双击；单指交给 [onSingleTouch]。 */
class MirrorGestureController(
    private val target: View,
    private val onSingleTouch: (MotionEvent) -> Boolean,
) {
    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4f
        private const val DOUBLE_TAP_SCALE = 2f
    }

    private var scale = 1f
    private var multiTouch = false
    private var panLastX = Float.NaN
    private var panLastY = Float.NaN

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = scale
                val next = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (old == next) return true
                val fx = detector.focusX
                val fy = detector.focusY
                target.translationX += (fx - target.left - target.pivotX) * (1f - next / old)
                target.translationY += (fy - target.top - target.pivotY) * (1f - next / old)
                scale = next
                applyTransform()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        target.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) {
                    reset()
                } else {
                    target.pivotX = e.x
                    target.pivotY = e.y
                    scale = DOUBLE_TAP_SCALE
                    target.translationX = 0f
                    target.translationY = 0f
                    applyTransform()
                }
                return true
            }
        },
    )

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                panLastX = Float.NaN
                panLastY = Float.NaN
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multiTouch) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    onSingleTouch(cancel)
                    cancel.recycle()
                }
                multiTouch = true
                if (event.pointerCount >= 2) {
                    panLastX = (event.getX(0) + event.getX(1)) / 2f
                    panLastY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasMulti = multiTouch
                multiTouch = false
                panLastX = Float.NaN
                panLastY = Float.NaN
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (wasMulti) return true
            }
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (multiTouch || event.pointerCount > 1 || scaleDetector.isInProgress) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                event.pointerCount >= 2 &&
                scale > 1.01f &&
                !scaleDetector.isInProgress
            ) {
                val cx = (event.getX(0) + event.getX(1)) / 2f
                val cy = (event.getY(0) + event.getY(1)) / 2f
                if (!panLastX.isNaN()) {
                    target.translationX += cx - panLastX
                    target.translationY += cy - panLastY
                    clampTranslation()
                }
                panLastX = cx
                panLastY = cy
            }
            return true
        }

        return onSingleTouch(event)
    }

    fun reset() {
        scale = 1f
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.translationX = 0f
        target.translationY = 0f
        applyTransform()
    }

    private fun applyTransform() {
        target.scaleX = scale
        target.scaleY = scale
        clampTranslation()
    }

    private fun clampTranslation() {
        val parent = target.parent as? View ?: return
        val pw = parent.width.toFloat()
        val ph = parent.height.toFloat()
        val w = target.width * scale
        val h = target.height * scale
        if (pw <= 0f || ph <= 0f) return
        val maxTx = max(0f, (w - pw) / 2f)
        val maxTy = max(0f, (h - ph) / 2f)
        if (scale <= 1.01f) {
            target.translationX = 0f
            target.translationY = 0f
        } else {
            target.translationX = target.translationX.coerceIn(-maxTx, maxTx)
            target.translationY = target.translationY.coerceIn(-maxTy, maxTy)
        }
    }
}
