package org.videolan.vlc

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY diagnostic for the screen-off PiP pause (Nov 2026): audio stops
 * 3-5 min after the screen turns off. Records the pause/stop reason with a
 * timestamp in SharedPreferences so it survives process death, and the
 * screen-on receiver in VideoPlayerActivity shows it as a Toast.
 * REMOVE once the cause is found (same playbook as the run #44-#46
 * diagnostics, which were removed in run #47).
 */
object VlmeDbg {
    private const val PREFS = "vlme_dbg"
    private const val KEY_NOTES = "notes"

    fun note(context: Context, tag: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val prev = prefs.getString(KEY_NOTES, "") ?: ""
            val entries = (if (prev.isEmpty()) emptyList() else prev.split("\n")).takeLast(4) + "$time $tag"
            prefs.edit().putString(KEY_NOTES, entries.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NOTES, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** First org.videolan frame outside the skipped classes, for pause() caller tags. */
    fun callerTag(vararg skipSimpleNames: String): String {
        return try {
            val trace = Throwable().stackTrace
            val frame = trace.firstOrNull { el ->
                el.className.startsWith("org.videolan.") &&
                        skipSimpleNames.none { el.className.contains(it) } &&
                        !el.className.contains("VlmeDbg")
            }
            frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
}
