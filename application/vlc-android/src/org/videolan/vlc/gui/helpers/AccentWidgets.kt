/*
 * *************************************************************************
 *  AccentWidgets.kt
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

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import java.util.WeakHashMap

/**
 * Palette for one context. A dialog can hold its own entry. Lookup walks
 * wrappers so a view inflated in an activity finds the activity palette.
 * Nothing here is written into the shared asset table.
 */
internal object AccentRuntime {
    private val colors = WeakHashMap<Context, AccentColors>()

    fun set(context: Context, value: AccentColors?) {
        if (value == null) colors.remove(context) else colors[context] = value
    }

    fun get(context: Context): AccentColors? {
        var current: Context? = context
        while (current != null) {
            colors[current]?.let { return it }
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}

/** Compiled layout XML encodes a theme attribute as '?' plus its resource id. */
internal fun parseThemeAttribute(raw: String?): Int? {
    if (raw == null || raw.length < 2 || raw[0] != '?') return null
    val body = raw.substring(1)
    return when {
        body.startsWith("0x") || body.startsWith("0X") -> body.substring(2).toLongOrNull(16)?.toInt()
        else -> body.toIntOrNull()
    }
}

internal object AccentWidgets {
    fun tintTree(root: View) {
        applyResolved(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) tintTree(root.getChildAt(i))
        }
    }

    fun apply(view: View?, attrs: AttributeSet?, context: Context) {
        if (view == null) return
        val colors = AccentRuntime.get(context) ?: return
        var primary = false
        var onPrimary = false
        if (attrs != null) {
            for (index in 0 until attrs.attributeCount) {
                when (parseThemeAttribute(attrs.getAttributeValue(index))) {
                    MaterialR.attr.colorOnPrimary -> onPrimary = true
                    MaterialR.attr.colorPrimary, MaterialR.attr.colorAccent -> primary = true
                }
            }
        }
        if (view is TextView) {
            when {
                onPrimary -> view.setTextColor(colors.onPrimary)
                primary -> view.setTextColor(colors.primary)
            }
        }
        if (primary && view is ImageView) {
            ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(colors.primary))
        }
        applyResolved(view)
    }

    private fun applyResolved(view: View) {
        val colors = AccentRuntime.get(view.context) ?: return
        val themePrimary = resolve(view.context, MaterialR.attr.colorPrimary) ?: return
        val themeAccent = resolve(view.context, MaterialR.attr.colorAccent)
        val themeOnPrimary = resolve(view.context, MaterialR.attr.colorOnPrimary)
        if (view is TextView) {
            val text = view.currentTextColor
            if (text == themePrimary || (themeAccent != null && text == themeAccent)) {
                view.setTextColor(colors.primary)
            }
        }
        if (view is MaterialButton) {
            val background = view.backgroundTintList?.firstColor()
            if (background == themePrimary) {
                view.backgroundTintList = ColorStateList.valueOf(colors.primary)
                if (themeOnPrimary != null && view.currentTextColor == themeOnPrimary) {
                    view.setTextColor(colors.onPrimary)
                }
            }
        }
        if (view is ImageView) {
            val tint = ImageViewCompat.getImageTintList(view)?.firstColor()
            if (tint == themePrimary || (themeAccent != null && tint == themeAccent)) {
                ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(colors.primary))
            }
        }
    }

    private fun ColorStateList.firstColor(): Int {
        if (Build.VERSION.SDK_INT >= 23) return defaultColor
        return getColorForState(android.util.StateSet.WILD_CARD, 0)
    }

    private fun resolve(context: Context, attr: Int): Int? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return null
        if (value.type !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return null
        return value.data
    }
}
