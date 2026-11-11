/*
 * *************************************************************************
 *  PreferencesUi.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
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

package org.videolan.vlc.gui.preferences

import android.app.Dialog
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.AndroidDevices
import org.videolan.tools.KEY_APP_ACCENT_COLOR
import org.videolan.tools.KEY_APP_THEME
import org.videolan.tools.KEY_ARTISTS_SHOW_ALL
import org.videolan.tools.KEY_BLACK_THEME
import org.videolan.tools.KEY_DAYNIGHT
import org.videolan.tools.KEY_INCLUDE_MISSING
import org.videolan.tools.KEY_MEDIA_SEEN
import org.videolan.tools.KEY_SET_LOCALE
import org.videolan.tools.KEY_SHOW_HEADERS
import org.videolan.tools.LIST_TITLE_ELLIPSIZE
import org.videolan.tools.LocaleUtils
import org.videolan.tools.PREF_TV_UI
import org.videolan.tools.RESULT_UPDATE_SEEN_MEDIA
import org.videolan.tools.SHOW_VIDEO_THUMBNAILS
import org.videolan.tools.SLEEP_TIMER_DEFAULT_INTERVAL
import org.videolan.tools.SLEEP_TIMER_DEFAULT_RESET_INTERACTION
import org.videolan.tools.SLEEP_TIMER_DEFAULT_WAIT
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.dialogs.SleepTimerDialog
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.customAccentNoteVisible


class PreferencesUi : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: SharedPreferences
    private val accentNoteListeners = java.util.WeakHashMap<Dialog, ViewTreeObserver.OnGlobalLayoutListener>()
    private lateinit var sleepTimerPreference: PreferenceScreen
    override fun getXml() = R.xml.preferences_ui

    override fun getTitleId() = R.string.interface_prefs_screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareLocaleList()
        setupTheme()
    }

    override fun onCreatePreferences(bundle: Bundle?, s: String?) {
        super.onCreatePreferences(bundle, s)
        val groupVideoPreference = preferenceManager.findPreference<EditTextPreference>("video_group_size")
        groupVideoPreference?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        groupVideoPreference?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
            val text = preference.text
            if (text.isNullOrEmpty()) {
                ""
            } else {
                getString(R.string.video_group_size_summary, text)
            }
        }
        settings = Settings.getInstance(requireActivity())
        sleepTimerPreference = findPreference("default_sleep_timer")!!
        manageSleepTimerSummary()
        refreshAccentNote()
    }

    private fun setupTheme() {
        val prefs = preferenceScreen.sharedPreferences!!
        if (!prefs.contains(KEY_APP_THEME)) {
            var theme = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            if (prefs.getBoolean(KEY_DAYNIGHT, false) && !AndroidDevices.canUseSystemNightMode()) {
                theme = AppCompatDelegate.MODE_NIGHT_AUTO
            } else if (prefs.contains(KEY_BLACK_THEME)) {
                theme = if (prefs.getBoolean(KEY_BLACK_THEME, false))
                    AppCompatDelegate.MODE_NIGHT_YES
                else
                    AppCompatDelegate.MODE_NIGHT_NO
            }
            prefs.putSingle(KEY_APP_THEME, theme.toString())
        }

        if (!AndroidDevices.canUseSystemNightMode()) {
            val pref = findPreference<ListPreference>(KEY_APP_THEME)
            pref?.entries = resources.getStringArray(R.array.daynight_mode_legacy_entries)
            pref?.entryValues = resources.getStringArray(R.array.daynight_mode_legacy_values)

        }
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
        requireActivity().supportFragmentManager.registerFragmentLifecycleCallbacks(accentDialogCallbacks, false)
    }

    override fun onStop() {
        requireActivity().supportFragmentManager.unregisterFragmentLifecycleCallbacks(accentDialogCallbacks)
        super.onStop()
        preferenceScreen.sharedPreferences!!
                .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == null) return false
        when (preference.key) {
            PREF_TV_UI -> {
                Settings.tvUI = (preference as TwoStatePreference).isChecked
                (activity as PreferencesActivity).setRestartApp()
                return true
            }
            SHOW_VIDEO_THUMBNAILS -> {
                Settings.showVideoThumbs = (preference as TwoStatePreference).isChecked
                (activity as PreferencesActivity).setRestart()
                return true
            }
            KEY_SHOW_HEADERS -> {
                Settings.showHeaders = (preference as TwoStatePreference).isChecked
                (activity as PreferencesActivity).setRestart()
                return true
            }
            "default_sleep_timer" -> {
                val newFragment = SleepTimerDialog.newInstance(true)
                newFragment.onDismissListener  = DialogInterface.OnDismissListener { manageSleepTimerSummary() }
                newFragment.show(requireActivity().supportFragmentManager, "time")
            }
            KEY_MEDIA_SEEN -> requireActivity().setResult(RESULT_UPDATE_SEEN_MEDIA)
            KEY_ARTISTS_SHOW_ALL -> (activity as PreferencesActivity).updateArtists()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (sharedPreferences == null || key == null) return
        when (key) {
            KEY_SET_LOCALE -> {
                (activity as PreferencesActivity).setRestart()
                UiTools.restartDialog(requireActivity())
            }
            KEY_APP_THEME, KEY_APP_ACCENT_COLOR -> {
                if (key == KEY_APP_ACCENT_COLOR) refreshAccentNote()
                UiTools.restartDialog(requireActivity())
            }
            LIST_TITLE_ELLIPSIZE -> {
                Settings.listTitleEllipsize = sharedPreferences.getString(LIST_TITLE_ELLIPSIZE, "0")?.toInt() ?: 0
                (activity as PreferencesActivity).setRestart()
            }
            KEY_INCLUDE_MISSING -> {
                Settings.includeMissing = sharedPreferences.getBoolean(key, true)
                (activity as PreferencesActivity).setRestart()
            }
        }
    }

    private fun manageSleepTimerSummary() {
        val interval = settings.getLong(SLEEP_TIMER_DEFAULT_INTERVAL,  -1L)
        if (interval == -1L) {
            sleepTimerPreference.summary = getString(R.string.disabled)
            return
        }
        val wait = settings.getBoolean(SLEEP_TIMER_DEFAULT_WAIT,  false)
        val reset = settings.getBoolean(SLEEP_TIMER_DEFAULT_RESET_INTERACTION,  false)
        sleepTimerPreference.summary = getString(R.string.default_sleep_timer_summary, Tools.millisToString(interval), if (wait) "true" else "false", if (reset) "true" else "false")
    }

    private fun refreshAccentNote() {
        val preference = findPreference<Preference>(KEY_APP_ACCENT_COLOR) ?: return
        val color = try {
            settings.getInt(KEY_APP_ACCENT_COLOR, ContextCompat.getColor(requireContext(), R.color.orange800))
        } catch (_: ClassCastException) {
            return
        }
        preference.summary = if (customAccentNoteVisible(Build.VERSION.SDK_INT, color)) {
            getString(R.string.accent_custom_color_limit)
        } else {
            null
        }
    }

    private val accentDialogCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, fragment: Fragment) {
            if (fragment !is ColorPickerDialog) return
            val preference = findPreference<ColorPreferenceCompat>(KEY_APP_ACCENT_COLOR) ?: return
            if (fragment.tag != preference.fragmentTag) return
            val dialog = fragment.dialog ?: return
            if (accentNoteListeners.containsKey(dialog)) return
            val listener = ViewTreeObserver.OnGlobalLayoutListener { syncAccentDialogNote(dialog) }
            accentNoteListeners[dialog] = listener
            dialog.setOnShowListener {
                val decor = dialog.window?.decorView ?: return@setOnShowListener
                decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
                syncAccentDialogNote(dialog)
            }
        }

        override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment !is ColorPickerDialog) return
            val dialog = fragment.dialog ?: return
            val listener = accentNoteListeners.remove(dialog) ?: return
            val decor = dialog.window?.decorView ?: return
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    private fun syncAccentDialogNote(dialog: Dialog) {
        try {
            syncAccentDialogNoteInner(dialog)
        } catch (_: RuntimeException) {
        }
    }

    private fun syncAccentDialogNoteInner(dialog: Dialog) {
        val column = pickerColumn(dialog.window?.decorView) ?: return
        val existing = column.findViewWithTag<View>(ACCENT_NOTE_TAG)
        if (!customAccentNoteVisible(Build.VERSION.SDK_INT, savedAccentColor())) {
            if (existing != null) column.removeView(existing)
            return
        }
        if (existing != null) return
        val note = TextView(column.context)
        note.tag = ACCENT_NOTE_TAG
        note.text = getString(R.string.accent_custom_color_limit)
        TextViewCompat.setTextAppearance(note, android.R.style.TextAppearance_Small)
        val pad = (16 * column.resources.displayMetrics.density).toInt()
        note.setPadding(pad, pad / 2, pad, pad / 4)
        column.addView(note, 0)
    }

    private fun savedAccentColor(): Int {
        return try {
            settings.getInt(KEY_APP_ACCENT_COLOR, ContextCompat.getColor(requireContext(), R.color.orange800))
        } catch (_: ClassCastException) {
            ContextCompat.getColor(requireContext(), R.color.orange800)
        }
    }

    private fun pickerColumn(root: View?): LinearLayout? {
        if (root == null) return null
        if (root is LinearLayout && root.orientation == LinearLayout.VERTICAL && containsPicker(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                pickerColumn(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun containsPicker(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = child.javaClass.name
            if (name.endsWith("ColorPickerView") || name.endsWith("NestedGridView")) return true
            if (child is ViewGroup && containsPicker(child)) return true
        }
        return false
    }

    private fun prepareLocaleList() {
        val localePair = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.device_default))
        val lp = findPreference<ListPreference>(KEY_SET_LOCALE)
        lp?.entries = localePair.localeEntries
        lp?.entryValues = localePair.localeEntryValues
    }

    private companion object {
        const val ACCENT_NOTE_TAG = "vlme_accent_custom_note"
    }
}
