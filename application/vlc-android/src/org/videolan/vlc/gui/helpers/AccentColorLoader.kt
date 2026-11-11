/*
 * *************************************************************************
 *  AccentColorLoader.kt
 * **************************************************************************
 *  Copyright © 2026 VLMe authors
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.helpers

import android.content.Context
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.FileOutputStream

/**
 * API 30+ half of accent installation. Kept in its own class so Dalvik (API
 * 17-20) does not verify [ResourcesLoader] while loading the rest of the app.
 * [AccentOverlay] loads this class only when [Build.VERSION.SDK_INT] is 30+.
 */
@RequiresApi(30)
internal class AccentColorLoader {
    companion object {
        private val installed = java.util.WeakHashMap<Resources, ResourcesLoader>()

        @JvmStatic
        fun install(context: Context, table: ByteArray): Boolean {
            val targets = LinkedHashSet<Resources>()
            targets.add(context.resources)
            context.applicationContext?.resources?.let { targets.add(it) }
            var installedAny = false
            for (resources in targets) {
                val loader = newLoader(table) ?: continue
                installed.remove(resources)?.let { previous -> resources.removeLoaders(previous) }
                resources.addLoaders(loader)
                installed[resources] = loader
                installedAny = true
            }
            return installedAny
        }

        private fun newLoader(table: ByteArray): ResourcesLoader? {
            val memfd = Os.memfd_create("vlme-accent.arsc", 0)
            return try {
                FileOutputStream(memfd).use { stream ->
                    stream.write(table)
                    Os.lseek(memfd, 0, OsConstants.SEEK_SET)
                    ParcelFileDescriptor.dup(memfd).use { pfd ->
                        ResourcesLoader().also { loader ->
                            loader.addProvider(ResourcesProvider.loadFromTable(pfd, null))
                        }
                    }
                }
            } finally {
                try {
                    Os.close(memfd)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
