/*****************************************************************************
 * StorageProvider.kt
 *****************************************************************************
 * Copyright © 2018 VLC authors and VideoLAN
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
 *****************************************************************************/

package org.videolan.vlc.providers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.Storage
import org.videolan.resources.AndroidDevices
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.R
import org.videolan.vlc.repository.DirectoryRepository
import java.io.File

class StorageProvider(context: Context, dataset: LiveDataset<MediaLibraryItem>, url: String?) : FileBrowserProvider(context, dataset, url, false, sort = Medialibrary.SORT_FILENAME, desc = false) {

    // the ML doesn't index hidden files. no need to display them here
    override fun getFlags(interact: Boolean) = if (interact) MediaBrowser.Flag.NoSlavesAutodetect else 0

    override suspend fun browseRootImpl() {
        val customDirectories = DirectoryRepository.getInstance(context).getCustomDirectories()
        val customPaths = customDirectories.map { it.path }
        // Device roots only. Custom paths are added below, and a custom path must not
        // count as a container of itself or the skip loop never adds one.
        val deviceStorages = DirectoryRepository.getInstance(context).getMediaDirectories()
                .filter { it !in customPaths }
        val storagesList = ArrayList<MediaLibraryItem>()
        for (mediaDirLocation in deviceStorages) {
            val file = File(mediaDirLocation)
            if (!file.exists() || !file.canRead()) continue
            if (mediaDirLocation.isEmpty()) continue
            val storage = Storage(Uri.fromFile(File(mediaDirLocation)))
            if (AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY == mediaDirLocation)
                storage.name = context.getString(R.string.internal_memory)
            storagesList.add(storage)
        }
        customLoop@ for (customDir in customDirectories) {
            for (mediaDirLocation in deviceStorages) {
                if (mediaDirLocation.isEmpty()) continue
                if (customDir.path.startsWith(mediaDirLocation)) continue@customLoop
            }
            storagesList.add(Storage(customDir.path.toUri()))
        }
        dataset.value = storagesList
    }

    private val sb = StringBuilder()
    override fun getDescription(folderCount: Int, mediaFileCount: Int): String {
        val res = context.resources
        sb.clear()
        if (folderCount > 0) {
            sb.append(res.getQuantityString(R.plurals.subfolders_quantity, folderCount, folderCount))
        } else sb.append(res.getString(R.string.nosubfolder))
        return sb.toString()
    }

    override suspend fun findMedia(media: IMedia) = media.takeIf { it.isStorage() }?.let { Storage(it.uri) }

    override fun computeHeaders(value: List<MediaLibraryItem>) {}
}

private fun IMedia.isStorage() = type == IMedia.Type.Directory
