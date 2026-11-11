/*
 * *************************************************************************
 *  AccentOverlay.kt
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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.core.content.ContextCompat
import org.videolan.tools.KEY_APP_ACCENT_COLOR
import org.videolan.tools.Settings
import org.videolan.vlc.R
import java.io.File
import java.lang.reflect.Method

private const val TAG = "VLC/Accent"

/**
 * Applies the saved accent to the activity theme already in force, so the
 * player (always dark), dialogs, settings and the day/night app each get a
 * palette that contrasts with their own resolved backgrounds.
 *
 * Any opaque RGB is accepted. Orange 800 stays on the stock VLC theme.
 * The preference is read on every create, so the choice survives restart.
 */
fun Activity.applyAccentOverlay() {
    val stored = try {
        Settings.getInstance(this).getInt(KEY_APP_ACCENT_COLOR, ContextCompat.getColor(this, R.color.orange800))
    } catch (e: ClassCastException) {
        Log.w(TAG, "app_accent_color is not an int", e)
        return
    }
    val seed = canonicalAccentSeed(stored) ?: return
    val surfaces = themeSurfaces(theme)
    val resolved = if (surfaces.isEmpty()) defaultSurfaces() else surfaces
    val colors = buildAccentColors(seed, resolved)
    if (installAccentTable(this, colors, seed)) {
        theme.applyStyle(R.style.ThemeOverlay_VLMe_Accent_Runtime, true)
        return
    }
    val fallback = presetOverlayStyle(seed)
    if (fallback != 0) {
        theme.applyStyle(fallback, true)
        if (!loggedPresetFallback) {
            loggedPresetFallback = true
            Log.w(TAG, "Runtime accent table unavailable; using the preset overlay")
        }
    } else if (!loggedCustomFailure) {
        loggedCustomFailure = true
        Log.w(TAG, "Custom accent is saved but the runtime palette could not be installed")
    }
}

private fun presetOverlayStyle(seed: Int): Int = when (seed) {
    PRESET_BLUE -> R.style.ThemeOverlay_VLMe_Accent_Blue
    PRESET_GREEN -> R.style.ThemeOverlay_VLMe_Accent_Green
    PRESET_RED -> R.style.ThemeOverlay_VLMe_Accent_Red
    PRESET_PURPLE -> R.style.ThemeOverlay_VLMe_Accent_Purple
    PRESET_TEAL -> R.style.ThemeOverlay_VLMe_Accent_Teal
    PRESET_PINK -> R.style.ThemeOverlay_VLMe_Accent_Pink
    else -> 0
}

private fun Activity.defaultSurfaces(): IntArray {
    val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    return intArrayOf(if (night) 0xFF131313.toInt() else 0xFFFFFFFF.toInt())
}

internal fun themeSurfaces(theme: Resources.Theme): IntArray {
    val out = ArrayList<Int>(4)
    fun add(attr: Int) {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            out.add(value.data)
        }
    }
    add(R.attr.background_default)
    add(R.attr.bottom_sheet_background)
    add(android.R.attr.colorBackground)
    add(android.R.attr.windowBackground)
    return out.toIntArray()
}

private var loggedCustomFailure = false
private var loggedPresetFallback = false
private var legacyCookie = 0
private var legacyKey = 0

private fun installAccentTable(context: Context, colors: AccentColors, seed: Int): Boolean {
    return try {
        val slots = accentSlots(context, colors) ?: return false
        val packageName = context.resources.getResourcePackageName(slots[0].id)
        val table = createAccentColorTable(packageName, slots)
        if (Build.VERSION.SDK_INT >= 30) {
            val loader = Class.forName("org.videolan.vlc.gui.helpers.AccentColorLoader")
            val install = loader.getDeclaredMethod("install", Context::class.java, ByteArray::class.java)
            install.invoke(null, context, table) as Boolean
        } else {
            installLegacyTable(context, table, seed)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Accent resource table was not installed", t)
        false
    }
}

private fun accentSlots(context: Context, colors: AccentColors): List<AccentColorSlot>? {
    val pairs = listOf(
            R.color.vlme_accent_primary to colors.primary,
            R.color.vlme_accent_on_primary to colors.onPrimary,
            R.color.vlme_accent_primary_dark to colors.primaryDark,
            R.color.vlme_accent_secondary to colors.primary,
            R.color.vlme_accent_highlight to colors.primary,
            R.color.vlme_accent_separator to colors.primary,
            R.color.vlme_accent_donate to colors.donate,
            R.color.vlme_accent_focus_10 to colors.focus10,
            R.color.vlme_accent_focus_20 to colors.focus20,
    )
    val slots = ArrayList<AccentColorSlot>(pairs.size)
    for ((id, argb) in pairs) {
        if (context.resources.getResourceTypeName(id) != "color") return null
        slots.add(AccentColorSlot(id, context.resources.getResourceEntryName(id), argb))
    }
    return slots
}

/**
 * API 17-29 has no [android.content.res.loader.ResourcesLoader]. The same
 * table is added as an asset path. Android 9 and 10 hide that method from
 * apps targeting a current SDK, so those two releases get a narrow exemption
 * for AssetManager only. A failed call leaves the preset overlay in place.
 */
@SuppressLint("PrivateApi")
private fun installLegacyTable(context: Context, table: ByteArray, seed: Int): Boolean {
    if (legacyCookie != 0 && legacyKey == seed) return true
    if (Build.VERSION.SDK_INT in 28..29) exemptAssetManager()
    val dir = File(context.cacheDir, "vlme-accent")
    if (!dir.isDirectory && !dir.mkdirs()) return false
    File(dir, "resources.arsc").writeBytes(table)
    val addAssetPath: Method = context.assets.javaClass.getDeclaredMethod("addAssetPath", String::class.java)
    addAssetPath.isAccessible = true
    val cookie = addAssetPath.invoke(context.assets, dir.absolutePath) as Int
    if (cookie == 0) return false
    legacyCookie = cookie
    legacyKey = seed
    return true
}

@SuppressLint("PrivateApi")
private fun exemptAssetManager() {
    try {
        val classArrayType = arrayOfNulls<Class<*>>(0).javaClass
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                classArrayType,
        )
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null as Array<Class<*>>?) as Method
        val stringArrayType = arrayOfNulls<String>(0).javaClass
        val setExemptions = getDeclaredMethod.invoke(
                vmRuntime,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(stringArrayType),
        ) as Method
        val runtime = getRuntime.invoke(null)
        // Pass the String[] as one argument. Spreading it would call the method
        // with a single String and fail on Android 9/10.
        setExemptions.invoke(runtime, arrayOf("Landroid/content/res/AssetManager;") as Any)
    } catch (t: Throwable) {
        Log.w(TAG, "AssetManager exemption was not granted", t)
    }
}
