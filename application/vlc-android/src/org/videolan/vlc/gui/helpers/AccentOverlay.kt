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
 *  MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.LayoutInflaterCompat
import com.google.android.material.R as MaterialR
import org.videolan.tools.KEY_APP_ACCENT_COLOR
import org.videolan.tools.Settings
import org.videolan.vlc.R

private const val TAG = "VLC/Accent"

/**
 * Applies the saved accent on this context's own theme.
 *
 * Presets never go through the runtime color table. A compiled overlay is
 * chosen from this context's surfaces, so the day app, night mode, and the
 * always-dark player each keep their own palette. The orange runtime style
 * is not applied on that path.
 *
 * A custom color on API 30+ installs a [ResourcesLoader] on this context's
 * [Resources] only. [ResourcesManager] rebinds that [Resources] object; it
 * does not rewrite another activity's table. The runtime overlay is applied
 * only after the color resources and the resolved theme attributes both
 * match the palette. A miss removes the loader. A preset then uses its
 * compiled overlay instead of the orange defaults.
 *
 * Before API 30 there is no public per-context resource overlay. Custom
 * colors are painted onto inflated text, buttons, and image tints by
 * [AccentWidgets]. Drawable XML that resolves ?colorPrimary inside the shared
 * asset table keeps the base theme color. The saved preference is never
 * rewritten.
 */
fun Context.applyAccentOverlay() {
    val stored = try {
        Settings.getInstance(this).getInt(KEY_APP_ACCENT_COLOR, ContextCompat.getColor(this, R.color.orange800))
    } catch (e: ClassCastException) {
        Log.e(TAG, "app_accent_color is not an int; the saved value was left unchanged", e)
        return
    }
    val seed = canonicalAccentSeed(stored)
    if (seed == null) {
        AccentRuntime.set(this, null)
        removeRuntimeLoader(this)
        return
    }
    val surfaces = paletteSurfaces(theme).let { if (it.isEmpty()) defaultSurfaces() else it }
    val dark = surfacesAreDark(surfaces)
    val preset = presetStyle(seed, dark)
    if (preset != 0) {
        removeRuntimeLoader(this)
        theme.applyStyle(preset, true)
        AccentRuntime.set(this, null)
        return
    }
    val colors = buildAccentColors(seed, surfaces)
    if (installRuntimeOverlay(this, seed, surfaces, colors)) {
        AccentRuntime.set(this, null)
        return
    }
    AccentRuntime.set(this, colors)
    installWidgetFactory(this)
    if (!loggedCustomLimit) {
        loggedCustomLimit = true
        Log.e(TAG, "Custom accent could not be installed into the resource table (API ${Build.VERSION.SDK_INT}). " +
                "Inflated text, buttons and image tints use the palette. " +
                "Drawable XML resolved inside the shared asset table keeps the base theme color. " +
                "The saved color was not changed.")
    }
}

private var loggedCustomLimit = false

private fun installRuntimeOverlay(context: Context, seed: Int, surfaces: IntArray, colors: AccentColors): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    val resources = context.resources
    val key = paletteKey(seed, surfaces)
    return try {
        val slots = accentSlots(context, colors) ?: return false
        val table = createAccentColorTable(resources.getResourcePackageName(slots[0].id), slots)
        val loader = Class.forName("org.videolan.vlc.gui.helpers.AccentColorLoader")
        val install = loader.getDeclaredMethod(
                "install",
                Resources::class.java,
                ByteArray::class.java,
                String::class.java,
        )
        val installed = install.invoke(null, resources, table, key) as Boolean
        if (!installed) return false
        if (!resourceColorsMatch(context, colors)) {
            removeLoader(loader, resources)
            return false
        }
        context.theme.applyStyle(R.style.ThemeOverlay_VLMe_Accent_Runtime, true)
        val resolvedPrimary = themeColor(context.theme, MaterialR.attr.colorPrimary)
        val resolvedOnPrimary = themeColor(context.theme, MaterialR.attr.colorOnPrimary)
        val accepted = runtimeOverlayAccepted(
                Build.VERSION.SDK_INT,
                ContextCompat.getColor(context, R.color.vlme_accent_primary),
                ContextCompat.getColor(context, R.color.vlme_accent_on_primary),
                resolvedPrimary,
                resolvedOnPrimary,
                colors,
        )
        if (!accepted) {
            removeLoader(loader, resources)
            Log.e(TAG, "Runtime accent install did not resolve colorPrimary/colorOnPrimary; loader removed")
        }
        accepted
    } catch (t: Throwable) {
        Log.e(TAG, "Runtime accent install failed", t)
        false
    }
}

private fun removeRuntimeLoader(context: Context) {
    if (Build.VERSION.SDK_INT < 30) return
    try {
        val loader = Class.forName("org.videolan.vlc.gui.helpers.AccentColorLoader")
        removeLoader(loader, context.resources)
    } catch (t: Throwable) {
        Log.e(TAG, "Could not remove accent loader", t)
    }
}

/**
 * Installed before [android.app.Activity.onCreate] returns into AppCompat, so
 * this factory is the inflater factory. It delegates view creation to AppCompat
 * and then retints. A second call is ignored once any factory is set.
 */
private fun installWidgetFactory(context: Context) {
    try {
        val activity = context.findActivity() as? AppCompatActivity ?: return
        val inflater = LayoutInflater.from(activity)
        if (inflater.factory != null) return
        LayoutInflaterCompat.setFactory2(inflater, AccentViewFactory(activity.delegate))
    } catch (t: Throwable) {
        Log.e(TAG, "Could not install accent view factory", t)
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun resourceColorsMatch(context: Context, colors: AccentColors): Boolean {
    return ContextCompat.getColor(context, R.color.vlme_accent_primary) == colors.primary &&
            ContextCompat.getColor(context, R.color.vlme_accent_on_primary) == colors.onPrimary
}

private fun removeLoader(loaderClass: Class<*>, resources: Resources) {
    try {
        loaderClass.getDeclaredMethod("remove", Resources::class.java).invoke(null, resources)
    } catch (t: Throwable) {
        Log.e(TAG, "Could not remove accent loader", t)
    }
}

private fun themeColor(theme: Resources.Theme, attr: Int): Int {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return 0
    if (value.type !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return 0
    return value.data
}

internal fun presetStyle(seed: Int, dark: Boolean): Int = when (seed) {
    PRESET_BLUE -> if (dark) R.style.ThemeOverlay_VLMe_Accent_Blue_Night else R.style.ThemeOverlay_VLMe_Accent_Blue
    PRESET_GREEN -> if (dark) R.style.ThemeOverlay_VLMe_Accent_Green_Night else R.style.ThemeOverlay_VLMe_Accent_Green
    PRESET_RED -> if (dark) R.style.ThemeOverlay_VLMe_Accent_Red_Night else R.style.ThemeOverlay_VLMe_Accent_Red
    PRESET_PURPLE -> if (dark) R.style.ThemeOverlay_VLMe_Accent_Purple_Night else R.style.ThemeOverlay_VLMe_Accent_Purple
    PRESET_TEAL -> if (dark) R.style.ThemeOverlay_VLMe_Accent_Teal_Night else R.style.ThemeOverlay_VLMe_Accent_Teal
    PRESET_PINK -> if (dark) R.style.ThemeOverlay_VLMe_Accent_Pink_Night else R.style.ThemeOverlay_VLMe_Accent_Pink
    else -> 0
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

private fun Context.defaultSurfaces(): IntArray {
    val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    return intArrayOf(if (night) 0xFF131313.toInt() else 0xFFFFFFFF.toInt())
}

/**
 * Surfaces the palette must clear. A theme that resolves both a light page
 * and a dark sheet keeps the page color here; the sheet applies its own
 * overlay so one table is not asked to contrast with both.
 */
internal fun paletteSurfaces(theme: Resources.Theme): IntArray {
    val resolved = themeSurfaces(theme)
    val prepared = prepareSurfaces(resolved)
    val dark = prepared.any { relativeLuminance(it) < 0.4 }
    val light = prepared.any { relativeLuminance(it) >= 0.4 }
    if (dark && light) {
        val page = typedColor(theme, R.attr.background_default)
        if (page != null) return intArrayOf(page)
    }
    return resolved
}

internal fun themeSurfaces(theme: Resources.Theme): IntArray {
    val out = ArrayList<Int>(4)
    fun add(attr: Int) {
        val color = typedColor(theme, attr) ?: return
        out.add(color)
    }
    add(R.attr.background_default)
    add(R.attr.bottom_sheet_background)
    add(android.R.attr.colorBackground)
    add(android.R.attr.windowBackground)
    return out.toIntArray()
}

private fun typedColor(theme: Resources.Theme, attr: Int): Int? {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return null
    if (value.type !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return null
    return value.data
}
