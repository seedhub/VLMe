/*
 * ************************************************************************
 *  SubtitleVideoLayout.kt
 * *************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui.video

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.ScaleGestureDetectorCompat
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val SUBTITLE_GESTURE_SCALE = "subtitle_gesture_scale"
private const val SUBTITLE_GESTURE_POSITION = "subtitle_gesture_position"
private const val MIN_SUBTITLE_SCALE = 0.5f
private const val MAX_SUBTITLE_SCALE = 2f

/** Keeps the subtitle plane inside the viewport, independently of video zoom. */
class SubtitleVideoLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : VLCVideoLayout(context, attrs) {
    var videoAspectRatio: (() -> Float)? = null
        set(value) {
            field = value
            refreshSubtitleLayout()
        }
    var independentSubtitles = true
        set(value) {
            if (field == value) return
            field = value
            refreshSubtitleLayout()
        }

    private fun refreshSubtitleLayout() {
        // Invalidate the nested FrameLayout's measurement cache when leaving a disc menu.
        subtitleSurface?.requestLayout() ?: requestLayout()
    }

    private val settings = Settings.getInstance(context)
    private var subtitleScale = settings.getFloat(SUBTITLE_GESTURE_SCALE, 1f).coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE)
    private var subtitlePosition = settings.getFloat(SUBTITLE_GESTURE_POSITION, 1f).coerceIn(0f, 1f)
    private var gestureStarted = false
    val isSubtitleGesturePending: Boolean
        get() = gestureStarted
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var initialPosition = 1f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val location = IntArray(2)
    private val subtitleBounds = Rect()
    private val subtitleTouchHeight = 96.dp.toFloat()
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                subtitleScale = (subtitleScale * detector.scaleFactor).coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE)
                dragging = true
                requestLayout()
                return true
            }
        }).apply { ScaleGestureDetectorCompat.setQuickScaleEnabled(this, false) }

    private val subtitleSurface: SurfaceView?
        get() = findViewById(R.id.surface_subtitles)

    private fun hasIndependentSurface(): Boolean {
        val params = subtitleSurface?.layoutParams ?: return false
        // OpenGL composites subtitles into the video and leaves this surface unused.
        return independentSubtitles && params.width > 0 && params.height > 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!hasIndependentSurface()) return
        val subtitles = subtitleSurface ?: return
        val params = subtitles.layoutParams
        // VideoHelper shares these LayoutParams with the video. Measure independently
        // without changing them, so video zoom cannot crop the subtitle surface.
        val aspectRatio = videoAspectRatio?.invoke()?.takeIf { it > 0f }
            ?: (params.width.toFloat() / params.height)
        val subtitleWidth = min(measuredWidth.toFloat(), measuredHeight * aspectRatio) * subtitleScale
        val subtitleHeight = subtitleWidth / aspectRatio
        subtitles.measure(
            View.MeasureSpec.makeMeasureSpec(subtitleWidth.roundToInt().coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(subtitleHeight.roundToInt().coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!hasIndependentSurface()) return
        val subtitles = subtitleSurface ?: return
        val parent = subtitles.parent as? ViewGroup ?: return
        val subtitleWidth = subtitles.measuredWidth
        val subtitleHeight = subtitles.measuredHeight
        // Anchor the bottom edge so pinching keeps captions at the same height.
        subtitlePosition = subtitlePosition.coerceIn(min(1f, subtitleTouchHeight * subtitleScale / height.coerceAtLeast(1)), 1f)
        val x = (width - subtitleWidth) / 2 - parent.left
        val y = (height * subtitlePosition).roundToInt() - subtitleHeight - parent.top
        subtitles.layout(x, y, x + subtitleWidth, y + subtitleHeight)
        subtitleBounds.set((width * 0.15f).roundToInt(),
            ((height * subtitlePosition) - subtitleTouchHeight * subtitleScale).roundToInt().coerceAtLeast(0),
            (width * 0.85f).roundToInt(),
            (height * subtitlePosition).roundToInt())
    }

    /**
     * The subtitle surface is transparent and has no text hit boxes. Reserve its lower,
     * central caption band for manipulation; the side regions retain volume/brightness.
     * A tap still reaches the regular player handler; a drag or pinch consumes the sequence.
     */
    fun onSubtitleTouchEvent(event: MotionEvent, enabled: Boolean): Boolean {
        if (!enabled || event.pointerCount > 2 || !hasIndependentSurface() || subtitleSurface?.visibility != View.VISIBLE) {
            gestureStarted = false
            dragging = false
            return false
        }
        getLocationOnScreen(location)
        val x = event.rawX - location[0]
        val y = event.rawY - location[1]
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureStarted = subtitleBounds.contains(x.roundToInt(), y.roundToInt())
            dragging = false
            downX = x
            downY = y
            initialPosition = subtitlePosition
        }
        if (!gestureStarted) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                if (!dragging && abs(x - downX) > touchSlop && abs(x - downX) > abs(y - downY)) {
                    gestureStarted = false
                    return false
                }
                if (abs(y - downY) > touchSlop) dragging = true
                if (dragging && height > 0) {
                    subtitlePosition = (initialPosition + (y - downY) / height)
                        .coerceIn(min(1f, subtitleTouchHeight * subtitleScale / height), 1f)
                    requestLayout()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Do not jump when the remaining finger continues after a pinch.
                val remaining = if (event.actionIndex == 0) 1 else 0
                downY = event.getY(remaining) + event.rawY - event.y - location[1]
                initialPosition = subtitlePosition
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureStarted = false
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) settings.edit {
                    putFloat(SUBTITLE_GESTURE_SCALE, subtitleScale)
                    putFloat(SUBTITLE_GESTURE_POSITION, subtitlePosition)
                }
                return dragging
            }
        }
        return dragging || event.pointerCount > 1
    }
}
