/*
 * ************************************************************************
 *  SubtitleVideoLayoutTest.kt
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

import android.app.Application
import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.videolan.tools.Settings
import org.videolan.vlc.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubtitleVideoLayoutTest {
    private lateinit var layout: SubtitleVideoLayout
    private lateinit var subtitles: SurfaceView
    private lateinit var videoParams: FrameLayout.LayoutParams

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.getInstance(context).edit().remove("subtitle_gesture_scale")
            .remove("subtitle_gesture_position").commit()
        layout = SubtitleVideoLayout(context)
        val frame = layout.findViewById<FrameLayout>(R.id.player_surface_frame)
        videoParams = FrameLayout.LayoutParams(2400, 1350)
        subtitles = SurfaceView(context).apply { id = R.id.surface_subtitles }
        frame.addView(subtitles, videoParams)
        measureAndLayout()
    }

    private fun measureAndLayout() {
        layout.measure(View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        layout.layout(0, 0, 2400, 1080)
    }

    @Test
    fun zoomedVideoDoesNotCropSubtitlesOrChangeVideoSize() {
        assertEquals(1920, subtitles.width)
        assertEquals(1080, subtitles.height)
        assertEquals(1080, subtitles.bottom)
        assertEquals(2400, videoParams.width)
        assertEquals(1350, videoParams.height)
        assertSame(videoParams, subtitles.layoutParams)
    }

    @Test
    fun subtitlePlaneRemainsInsideViewportAfterResize() {
        videoParams.width = 3840
        videoParams.height = 2160
        measureAndLayout()
        assertEquals(1920, subtitles.width)
        assertEquals(1080, subtitles.height)
        assertTrue(subtitles.left >= 0)
        assertTrue(subtitles.right <= layout.width)
        assertTrue(subtitles.top >= 0)
    }

    @Test
    fun fillModeDoesNotStretchSubtitleAspectRatio() {
        layout.videoAspectRatio = { 16f / 9f }
        videoParams.width = 2400
        videoParams.height = 1080
        measureAndLayout()
        assertEquals(1920, subtitles.width)
        assertEquals(1080, subtitles.height)
        assertEquals(2400, videoParams.width)
        assertEquals(1080, videoParams.height)
    }

    @Test
    fun discMenusKeepTheirVideoCoordinates() {
        layout.independentSubtitles = false
        measureAndLayout()
        assertEquals(2400, subtitles.width)
        assertEquals(1350, subtitles.height)
    }

    @Test
    fun openGlSurfaceLeavesVideoGesturesAvailable() {
        videoParams.width = FrameLayout.LayoutParams.MATCH_PARENT
        videoParams.height = FrameLayout.LayoutParams.MATCH_PARENT
        measureAndLayout()
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1050f))
        assertFalse(touch(MotionEvent.ACTION_MOVE, 750f))
    }

    private fun touch(action: Int, y: Float, enabled: Boolean = true, x: Float = 1200f): Boolean {
        val event = MotionEvent.obtain(0, 20, action, x, y, 0)
        return try { layout.onSubtitleTouchEvent(event, enabled) } finally { event.recycle() }
    }

    @Test
    fun draggingMovesOnlySubtitlesAndPersistsPosition() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1050f))
        assertTrue(touch(MotionEvent.ACTION_MOVE, 750f))
        measureAndLayout()
        assertEquals(780, subtitles.bottom)
        assertEquals(1350, videoParams.height)
        assertTrue(touch(MotionEvent.ACTION_UP, 750f))
        assertEquals(780f / 1080, Settings.getInstance(layout.context)
            .getFloat("subtitle_gesture_position", 1f), 0.001f)
    }

    @Test
    fun lockedPlayerAndTouchesOutsideCaptionBandDoNotCaptureGestures() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1050f, enabled = false))
        assertFalse(touch(MotionEvent.ACTION_MOVE, 750f, enabled = false))
        assertFalse(touch(MotionEvent.ACTION_DOWN, 200f))
        assertFalse(touch(MotionEvent.ACTION_MOVE, 400f))
    }

    @Test
    fun pinchChangesSubtitleSizeWithoutZoomingVideo() {
        touch(MotionEvent.ACTION_DOWN, 1050f)
        fun multiTouch(action: Int, span: Float, time: Long) {
            val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
                x = 1200f + (if (index == 0) -span else span) / 2
                y = 1000f
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(0, time, action, 2, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { layout.onSubtitleTouchEvent(event, true) } finally { event.recycle() }
        }
        multiTouch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 400f, 30)
        multiTouch(MotionEvent.ACTION_MOVE, 600f, 60)
        multiTouch(MotionEvent.ACTION_MOVE, 800f, 90)
        measureAndLayout()
        assertTrue(subtitles.width > 1920)
        assertEquals(1080, subtitles.bottom)
        assertEquals(2400, videoParams.width)
        assertEquals(1350, videoParams.height)
    }

    @Test
    fun horizontalSwipeLeavesSeekingAvailable() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1050f))
        assertFalse(touch(MotionEvent.ACTION_MOVE, 1050f, x = 1400f))
        assertFalse(layout.isSubtitleGesturePending)
        assertFalse(touch(MotionEvent.ACTION_UP, 1050f, x = 1400f))
    }

    @Test
    fun tapLeavesPlayerControlsAvailable() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, 1050f))
        assertTrue(layout.isSubtitleGesturePending)
        assertFalse(touch(MotionEvent.ACTION_UP, 1050f))
        assertFalse(layout.isSubtitleGesturePending)
    }

    @Test
    fun canceledDragDoesNotPersistPosition() {
        touch(MotionEvent.ACTION_DOWN, 1050f)
        touch(MotionEvent.ACTION_MOVE, 750f)
        assertTrue(touch(MotionEvent.ACTION_CANCEL, 750f))
        assertFalse(layout.isSubtitleGesturePending)
        assertFalse(Settings.getInstance(layout.context).contains("subtitle_gesture_position"))
    }

    @Test
    fun rotationKeepsSubtitlesInViewport() {
        layout.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        layout.layout(0, 0, 1080, 2400)
        assertEquals(1080, subtitles.width)
        assertEquals(2400, subtitles.bottom)
        assertTrue(subtitles.left >= 0)
        assertTrue(subtitles.top >= 0)
        assertTrue(subtitles.right <= layout.width)
    }

    @Test
    fun thirdPointerLeavesVideoGesturesAvailable() {
        touch(MotionEvent.ACTION_DOWN, 1050f)
        val properties = Array(3) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(3) { index -> MotionEvent.PointerCoords().apply {
            x = 1100f + index * 100
            y = 1050f
            pressure = 1f
            size = 1f
        } }
        val action = MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(0, 30, action, 3, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            assertFalse(layout.onSubtitleTouchEvent(event, true))
            assertFalse(layout.isSubtitleGesturePending)
        } finally {
            event.recycle()
        }
    }
}
