/*****************************************************************************
 * LaserDiscProvider.kt
 *****************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 *
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
 *****************************************************************************
 *
 * Classifies the optical disc behind an already-opened USB file descriptor,
 * by browsing "laser-dir://<fd>" through VLC's laser access module
 * (modules/access/laser.c), and returns one row per way the disc can be
 * played - none for a data disc, an empty drive or a device that is not an
 * optical drive.
 *
 * WHY laser-dir://: VLC 4's preparser opens an item whatever its scheme, so
 * the listing has a shortcut of its own. Not laser://<fd>, which is what a
 * playback MRL, laser/<demux>://<fd>, reaches the access as.
 *
 * WHY A BrowserProvider SUBCLASS: BrowserProvider owns the one correct way to
 * drive a MediaBrowser - a dedicated callback thread and a guaranteed release
 * - but its browseUrl() keeps only AUDIO, VIDEO and DIR items, which would
 * drop a disc row. So this subclass reuses its requestBrowsing() seam and
 * reads each raw IMedia before any filtering.
 *
 * Never mounted as a screen: its owner, FileBrowserProvider, uses it for
 * classify() calls and releases it. The fd's connection belongs to the caller;
 * laser.c acquires the token on open and releases it in its own Close().
 *****************************************************************************/

package org.videolan.vlc.providers

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.libvlc.util.MediaBrowser.EventListener
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.VLCInstance
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.util.LASER_TAG
import org.videolan.vlc.util.LaserDiscInfo
import kotlin.coroutines.resume

/**
 * @param context standard provider context.
 * @param dataset a throwaway dataset: this provider never populates a
 *                browsing screen, so callers pass an empty one they don't
 *                observe. It exists only because BrowserProvider's
 *                constructor requires it.
 */
class LaserDiscProvider(
    context: Context,
    dataset: LiveDataset<MediaLibraryItem>
) : BrowserProvider(
    context, dataset, url = null,
    sort = Medialibrary.SORT_DEFAULT, desc = false
) {

    /* Callback thread for this provider's own MediaBrowser (see
     * initBrowser), set up like the base class's private one. */
    private val ownHandlerThread = HandlerThread(
        "vlc-laser-provider",
        Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE
    ).apply { start() }
    private val ownBrowserHandler = Handler(ownHandlerThread.looper)

    override fun release() {
        /* Reset the browser FIRST.
         *
         * Before super.release(), whose scope cancellation makes the base
         * class release this same MediaBrowser from another thread.
         *
         * Before the handler thread quits, because a classification may still
         * be in flight: its own teardown posts to that thread, and with the
         * thread gone the native Close() would never run, leaving the fd
         * closed under libusb.
         *
         * Idempotent with classify()'s own releaseBrowserSync(). */
        mediabrowser?.let {
            try {
                it.changeEventListener(null)
            } catch (e: Exception) {
                Log.w(LASER_TAG, "release: reset threw", e)
            }
        }
        super.release()
        ownHandlerThread.quitSafely()
    }

    /**
     * Own, non-shared MediaBrowser. The base initBrowser() keeps one per
     * process, keyed by class name, so this provider and the
     * FileBrowserProvider that owns it would trample each other's browse.
     */
    override fun initBrowser() {
        if (mediabrowser == null) {
            mediabrowser = MediaBrowser(
                VLCInstance.getInstance(context),
                null,
                ownBrowserHandler
            )
        }
    }

    /**
     * Same as FileBrowserProvider.requestBrowsing(), on this provider's own
     * MediaBrowser: sub-items arrive on ownBrowserHandler's thread.
     */
    override suspend fun requestBrowsing(
        url: String?, eventListener: EventListener, interact: Boolean
    ) = withContext(coroutineContextProvider.IO) {
        initBrowser()
        if (url != null) mediabrowser?.let {
            it.changeEventListener(eventListener)
            val mrl = url.toUri()
            Log.d(LASER_TAG, "Browsing $mrl")
            it.browse(mrl, getFlags(interact))
        }
    }

    /** Never mounted as a screen - nothing to browse at the root. */
    override suspend fun browseRootImpl() {}

    /**
     * Browse laser-dir://<fd> and return the rows the disc offers.
     *
     * Not to be called concurrently on one instance: like the base class's
     * private filesFlow(), it drives requestBrowsing() directly rather than
     * through the browser actor. FileBrowserProvider classifies one drive at
     * a time.
     *
     * @param fd an already-opened, permission-granted USB fd, owned by the
     *           caller and valid for the duration of the call.
     * @return one [LaserDiscInfo] per row DirRead() emitted - two for a
     *         DVD-Video, with and without menus - or an empty list for a data
     *         disc, an empty drive, a device that is not an optical drive, or
     *         rows whose MRL did not have the expected shape.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun classify(fd: Int, deviceName: String): List<LaserDiscInfo> {
        try {
            /* Every row: a DVD is listed with and without menus. */
            val medias: List<IMedia> = usbMediaFlow("laser-dir://$fd").toList()
            return try {
                medias.mapNotNull { media -> discInfoOf(media, deviceName) }
            } finally {
                medias.forEach { it.release() }
            }
        } finally {
          /* Tear the browse down SYNCHRONOUSLY, so that laser.c's Close()
           * has released the token before the caller closes the
           * connection - an asynchronous teardown would race that close.
           *
           * NonCancellable: leaving the browser screen cancels this scope
           * mid-classification, and a suspending cleanup in a cancelled
           * coroutine throws instead of running. The caller would then close
           * the fd under a libusb handle with the interface claimed, maybe
           * mid-transaction, leaving the drive unusable until replugged.*/
            withContext(NonCancellable) { releaseBrowserSync() }
        }
    }

    /**
     * Ends the current browse and waits for it: changeEventListener(null)
     * resets the browser, which releases the media and so runs laser.c's
     * Close(), releasing the token. Posted to ownBrowserHandler, the thread
     * all MediaBrowser access happens on.
     */
    private suspend fun releaseBrowserSync() {
        val browser = mediabrowser ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            val posted = ownBrowserHandler.post {
                try {
                    browser.changeEventListener(null)
                } catch (e: Exception) {
                    Log.w(LASER_TAG, "releaseBrowserSync: reset threw", e)
                } finally {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            if (!posted) {
                /* The handler thread has quit. Reset inline: otherwise the
                 * native Close() never runs and the caller closes the fd
                 * anyway. Safe, since that dead thread was the only other
                 * user of the browser. */
                Log.w(LASER_TAG,
                    "releaseBrowserSync: handler thread is gone, resetting inline")
                try {
                    browser.changeEventListener(null)
                } catch (e: Exception) {
                    Log.w(LASER_TAG, "releaseBrowserSync: inline reset threw", e)
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
    /**
     * One browse item turned into a [LaserDiscInfo], or null if it does not
     * have the expected shape. One bad row does not reject the others.
     */
    private fun discInfoOf(media: IMedia, deviceName: String): LaserDiscInfo? {
        /* The whole MRL: "laser/dvd://<fd>" names the access module and the
         * demuxer, and is not a scheme. */
        val mrl = media.uri?.toString()
        if (mrl.isNullOrEmpty()) return null

        /* Every MRL DirRead() emits ends with the fd, which a tap replaces.
         * One that does not was mangled on its way through Uri. */
        if (!mrl.last().isDigit()) {
            Log.w(LASER_TAG,
                "classify: unexpected MRL shape '$mrl' - ignoring this row")
            return null
        }

        val title = media.getMeta(IMedia.Meta.Title) ?: ""
        return LaserDiscInfo(deviceName, mrl, title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun usbMediaFlow(url: String) = channelFlow<IMedia> {
        val listener = object : EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) {
                /* Retained before the send, since libVLC drops its reference
                 * when this returns, and released if the send fails: classify()
                 * only releases what it received. */
                if (trySend(media.apply { retain() }).isFailure) media.release()
            }
            override fun onBrowseEnd() {
                if (!isClosedForSend) close()
            }
            override fun onMediaRemoved(index: Int, media: IMedia) {}
        }
        requestBrowsing(url, listener, interact = false)
        /* No teardown here: classify() resets the browser synchronously, and
         * a second, asynchronous reset would race it. */
        awaitClose { }
    }
}