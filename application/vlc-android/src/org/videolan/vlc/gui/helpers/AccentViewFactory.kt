/*
 * *************************************************************************
 *  AccentViewFactory.kt
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
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatDelegate

/** Factory installed in place of AppCompat's, so custom accents can retint widgets on API 17+. */
internal class AccentViewFactory(private val delegate: AppCompatDelegate) : LayoutInflater.Factory2 {
    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val created = delegate.createView(parent, name, context, attrs)
        AccentWidgets.apply(created, attrs, context)
        return created
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return onCreateView(null, name, context, attrs)
    }
}
