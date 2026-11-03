package org.videolan.vlc
/*
 * ************************************************************************
 *  VlcTrack.kt
 * *************************************************************************
 * Copyright © 2022 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
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
import org.videolan.libvlc.MediaPlayer
import  org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import  org.videolan.libvlc.interfaces.IMedia

class VlcTrackImpl : VlcTrack {
    private var mediaTrack: IMedia.Track? = null
    private var mediaplayerTrack: MediaPlayer.TrackDescription? = null

    constructor(track: MediaPlayer.TrackDescription) {
        this.mediaplayerTrack = track
    }

    constructor(track: IMedia.Track) {
        this.mediaTrack = track
    }

    override fun getId() = mediaplayerTrack?.id?.toString() ?: mediaTrack!!.id.toString()

    override fun getName() = mediaplayerTrack?.name ?: mediaTrack!!.description
    override fun getWidth() = (mediaTrack as? IMedia.VideoTrack)?.width ?: 0
    override fun getHeight() = (mediaTrack as? IMedia.VideoTrack)?.height ?: 0
    override fun getDisplayAspectRatio(): Float {
        val track = mediaTrack as? IMedia.VideoTrack ?: return 0f
        if (track.width <= 0 || track.height <= 0) return 0f
        val sar = if (track.sarNum > 0 && track.sarDen > 0)
            track.sarNum.toFloat() / track.sarDen else 1f
        val ratio = track.width.toFloat() * sar / track.height
        return if (track.orientation >= IMedia.VideoTrack.Orientation.LeftTop) 1f / ratio else ratio
    }

    override fun getProjection() = (mediaTrack as? IMedia.VideoTrack)?.projection ?: 0 //todo good default value?
    override fun getFrameRateDen() = (mediaTrack as? IMedia.VideoTrack)?.frameRateDen ?: 0 //todo good default value?
    override fun getFrameRateNum() = (mediaTrack as? IMedia.VideoTrack)?.frameRateNum ?: 0 //todo good default value?

}