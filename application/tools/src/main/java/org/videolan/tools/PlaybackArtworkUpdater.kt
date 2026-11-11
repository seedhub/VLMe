/*
 * ************************************************************************
 *  PlaybackArtworkUpdater.kt
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
 */

package org.videolan.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Loads remote playback artwork off the caller.
 *
 * [request] returns a cached image immediately, or null, and never waits on
 * the network. At most one download runs. Repeating the same media keeps that
 * download. A different media or URL cancels it. The result is delivered only
 * when that media is still current. The updater does not retain the image.
 */
class PlaybackArtworkUpdater<T>(
    private val scope: CoroutineScope,
    private val cached: (String) -> T?,
    private val load: suspend (String) -> T?,
    private val isCurrent: (String) -> Boolean,
    private val onArtwork: (mediaKey: String, artwork: T?) -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    private var seq = 0
    private var inFlightKey: String? = null
    private var inFlightUrl: String? = null

    /**
     * @return the cached image when one is already available, otherwise null.
     * A null return means either no remote URL, a download is in flight, or a
     * new download was started. It does not mean the artwork failed.
     */
    fun request(mediaKey: String, artworkUrl: String?): T? {
        if (!artworkUrl.isNullOrEmpty()) {
            cached(artworkUrl)?.let { hit ->
                synchronized(lock) {
                    if (inFlightKey != null) cancelLocked()
                }
                return hit
            }
        }
        val generation = synchronized(lock) {
            if (artworkUrl.isNullOrEmpty()) {
                cancelLocked()
                return null
            }
            if (job?.isActive == true && inFlightKey == mediaKey && inFlightUrl == artworkUrl) {
                return null
            }
            cancelLocked()
            inFlightKey = mediaKey
            inFlightUrl = artworkUrl
            seq
        }
        val url = artworkUrl ?: return null
        val started = scope.launch {
            val artwork = try {
                load(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                null
            }
            val stillThisRequest = synchronized(lock) {
                if (generation != seq) {
                    false
                } else {
                    inFlightKey = null
                    inFlightUrl = null
                    true
                }
            }
            if (!stillThisRequest || !isCurrent(mediaKey)) return@launch
            onArtwork(mediaKey, artwork)
        }
        synchronized(lock) {
            if (generation == seq) job = started else started.cancel()
        }
        return null
    }

    fun cancel() {
        synchronized(lock) { cancelLocked() }
    }

    private fun cancelLocked() {
        job?.cancel()
        job = null
        inFlightKey = null
        inFlightUrl = null
        seq++
    }
}
