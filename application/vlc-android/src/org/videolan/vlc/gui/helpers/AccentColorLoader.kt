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
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.helpers

import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.FileOutputStream

/**
 * Installs one color table on a single [Resources] object. The loader is a
 * new memfd each time; nothing is rewritten in place, and the application
 * Resources object is never touched. Callers still have to read back the
 * resolved colors before trusting the install.
 *
 * This class is loaded only from API 30 code paths so Dalvik (API 17–20)
 * does not verify [ResourcesLoader] while loading the rest of the app.
 */
@RequiresApi(30)
internal class AccentColorLoader {
    companion object {
        private class Slot(val loader: ResourcesLoader, val key: String)

        private val installed = java.util.WeakHashMap<Resources, Slot>()

        @JvmStatic
        fun installedKey(resources: Resources): String? = installed[resources]?.key

        @JvmStatic
        fun install(resources: Resources, table: ByteArray, key: String): Boolean {
            val previous = installed[resources]
            if (previous != null && previous.key == key) return true
            val loader = newLoader(table) ?: return false
            if (previous != null) resources.removeLoaders(previous.loader)
            resources.addLoaders(loader)
            installed[resources] = Slot(loader, key)
            return true
        }

        @JvmStatic
        fun remove(resources: Resources) {
            val previous = installed.remove(resources) ?: return
            resources.removeLoaders(previous.loader)
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
