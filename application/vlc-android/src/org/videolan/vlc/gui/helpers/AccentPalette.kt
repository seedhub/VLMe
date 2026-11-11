/*
 * *************************************************************************
 *  AccentPalette.kt
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

/**
 * WCAG contrast for an arbitrary accent seed against the surfaces that will
 * actually sit behind it. The seed's hue is kept. Lightness moves only as far
 * as it must so normal text clears 4.5:1 and solid components clear 3:1.
 *
 * The six preset ints and the six legacy Material 500 ints must stay in step
 * with res/values/colors.xml and the previous picker. Orange 800 is the stock
 * VLC theme and is not recolored.
 */
internal const val TEXT_CONTRAST = 4.5
internal const val COMPONENT_CONTRAST = 3.0

internal const val STOCK_ORANGE = 0xFFFF610A.toInt()
internal const val PRESET_BLUE = 0xFF1565C0.toInt()
internal const val PRESET_GREEN = 0xFF2E7D32.toInt()
internal const val PRESET_RED = 0xFFC62828.toInt()
internal const val PRESET_PURPLE = 0xFF6A1B9A.toInt()
internal const val PRESET_TEAL = 0xFF00695C.toInt()
internal const val PRESET_PINK = 0xFFC2185B.toInt()

private val LEGACY_TO_PRESET = mapOf(
        0xFF2196F3.toInt() to PRESET_BLUE,
        0xFF4CAF50.toInt() to PRESET_GREEN,
        0xFFF44336.toInt() to PRESET_RED,
        0xFF9C27B0.toInt() to PRESET_PURPLE,
        0xFF009688.toInt() to PRESET_TEAL,
        0xFFE91E63.toInt() to PRESET_PINK,
)

internal data class AccentColors(
        val primary: Int,
        val onPrimary: Int,
        val primaryDark: Int,
        val donate: Int,
        val focus10: Int,
        val focus20: Int,
)

/**
 * @return the opaque seed to theme, or null when [stored] is the stock orange
 * and the base VLC theme should be left alone. Legacy Material 500 values
 * resolve to the matching preset so a color saved by the old picker keeps
 * that hue.
 */
internal fun canonicalAccentSeed(stored: Int): Int? {
    val opaque = stored or 0xFF000000.toInt()
    if (opaque == STOCK_ORANGE) return null
    return LEGACY_TO_PRESET[opaque] ?: opaque
}

/**
 * Drops fully transparent colors. Translucent surfaces are composited over
 * the darkest and lightest opaque surface, which is the pair of backgrounds
 * accent text can actually sit on.
 */
/** True when the lightest resolved surface is a dark UI, including the player in day mode. */
internal fun surfacesAreDark(raw: IntArray): Boolean {
    val surfaces = prepareSurfaces(raw)
    if (surfaces.isEmpty()) return false
    return relativeLuminance(surfaces.maxBy { relativeLuminance(it) }) < 0.4
}

/**
 * The runtime overlay is applied only when API 30+ actually resolved both
 * colors. A loader object or a non-zero asset cookie is not acceptance.
 */
internal fun runtimeOverlayAccepted(
        sdkInt: Int,
        resourcePrimary: Int,
        resourceOnPrimary: Int,
        resolvedPrimary: Int,
        resolvedOnPrimary: Int,
        expected: AccentColors,
): Boolean {
    if (sdkInt < 30) return false
    return resourcePrimary == expected.primary &&
            resourceOnPrimary == expected.onPrimary &&
            resolvedPrimary == expected.primary &&
            resolvedOnPrimary == expected.onPrimary
}

internal fun paletteKey(seed: Int, surfaces: IntArray): String {
    return seed.toUInt().toString(16) + ":" + surfaces.joinToString(",") { it.toUInt().toString(16) }
}

internal fun prepareSurfaces(raw: IntArray): List<Int> {
    val opaque = ArrayList<Int>()
    val partial = ArrayList<Int>()
    for (color in raw) {
        val alpha = (color ushr 24) and 0xFF
        when {
            alpha == 255 -> opaque.add(color)
            alpha > 0 -> partial.add(color)
        }
    }
    if (opaque.isEmpty() && partial.isEmpty()) return emptyList()
    val out = LinkedHashSet<Int>()
    out.addAll(opaque)
    if (partial.isNotEmpty()) {
        val behind = if (opaque.isEmpty()) {
            listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        } else {
            listOf(
                    opaque.minBy { relativeLuminance(it) },
                    opaque.maxBy { relativeLuminance(it) },
            )
        }
        for (fg in partial) {
            for (bg in behind) out.add(composite(fg, bg))
        }
    }
    return out.toList()
}

internal fun buildAccentColors(seed: Int, rawSurfaces: IntArray): AccentColors {
    val surfaces = prepareSurfaces(rawSurfaces).ifEmpty { listOf(0xFFFFFFFF.toInt()) }
    val opaqueSeed = seed or 0xFF000000.toInt()
    val primary = accessibleForeground(opaqueSeed, surfaces)
    return AccentColors(
            primary = primary,
            onPrimary = onContentColor(primary),
            primaryDark = darker(primary),
            donate = donateFill(opaqueSeed, surfaces),
            focus10 = withAlpha(primary, 0x1A),
            focus20 = withAlpha(primary, 0x33),
    )
}

internal fun relativeLuminance(color: Int): Double {
    fun channel(component: Int): Double {
        val s = (component and 0xFF) / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color shr 16) + 0.7152 * channel(color shr 8) + 0.0722 * channel(color)
}

internal fun contrastRatio(a: Int, b: Int): Double {
    val l1 = relativeLuminance(a)
    val l2 = relativeLuminance(b)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

internal fun composite(fg: Int, bg: Int): Int {
    val alpha = ((fg ushr 24) and 0xFF) / 255.0
    if (alpha >= 1.0) return fg or 0xFF000000.toInt()
    if (alpha <= 0.0) return bg or 0xFF000000.toInt()
    fun mix(src: Int, dst: Int) = (src * alpha + dst * (1.0 - alpha)).toInt().coerceIn(0, 255)
    val r = mix((fg shr 16) and 0xFF, (bg shr 16) and 0xFF)
    val g = mix((fg shr 8) and 0xFF, (bg shr 8) and 0xFF)
    val b = mix(fg and 0xFF, bg and 0xFF)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun rgbToHsl(color: Int): DoubleArray {
    val r = ((color shr 16) and 0xFF) / 255.0
    val g = ((color shr 8) and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2.0
    val d = max - min
    if (d < 1e-9) return doubleArrayOf(0.0, 0.0, l)
    val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d) + (if (g < b) 6.0 else 0.0)
        g -> ((b - r) / d) + 2.0
        else -> ((r - g) / d) + 4.0
    } / 6.0
    return doubleArrayOf(h, s, l)
}

internal fun hslToColor(h: Double, s: Double, l: Double): Int {
    if (s <= 0.0) {
        val v = (l * 255.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val q = if (l < 0.5) l * (1.0 + s) else l + s - l * s
    val p = 2.0 * l - q
    fun hue(t0: Double): Int {
        var t = t0
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        val c = when {
            t < 1.0 / 6.0 -> p + (q - p) * 6.0 * t
            t < 0.5 -> q
            t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6.0
            else -> p
        }
        return (c * 255.0).roundToInt().coerceIn(0, 255)
    }
    val r = hue(h + 1.0 / 3.0)
    val g = hue(h)
    val b = hue(h - 1.0 / 3.0)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun Double.roundToInt(): Int = Math.round(this).toInt()

private fun accessibleForeground(seed: Int, surfaces: List<Int>): Int {
    if (surfaces.all { contrastRatio(seed, it) >= TEXT_CONTRAST }) return seed
    val lightest = surfaces.maxBy { relativeLuminance(it) }
    val lighten = relativeLuminance(lightest) < 0.4
    return adjustLightness(seed, surfaces, lighten)
}

private fun adjustLightness(seed: Int, surfaces: List<Int>, lighten: Boolean): Int {
    val hsl = rgbToHsl(seed)
    fun colorAt(l: Double) = hslToColor(hsl[0], hsl[1], l.coerceIn(0.0, 1.0))
    fun passes(color: Int) = surfaces.all { contrastRatio(color, it) >= TEXT_CONTRAST }
    val extreme = if (lighten) 1.0 else 0.0
    if (!passes(colorAt(extreme))) {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val blackScore = surfaces.minOf { contrastRatio(black, it) }
        val whiteScore = surfaces.minOf { contrastRatio(white, it) }
        return if (whiteScore >= blackScore) white else black
    }
    var lo = if (lighten) hsl[2] else 0.0
    var hi = if (lighten) 1.0 else hsl[2]
    repeat(24) {
        val mid = (lo + hi) / 2.0
        if (lighten) {
            if (passes(colorAt(mid))) hi = mid else lo = mid
        } else {
            if (passes(colorAt(mid))) lo = mid else hi = mid
        }
    }
    var chosen = if (lighten) hi else lo
    var color = colorAt(chosen)
    var guard = 0
    while (!passes(color) && guard++ < 50) {
        chosen = if (lighten) (chosen + 0.01).coerceAtMost(1.0) else (chosen - 0.01).coerceAtLeast(0.0)
        color = colorAt(chosen)
    }
    return if (passes(color)) color else colorAt(extreme)
}

internal fun onContentColor(fill: Int): Int {
    val white = 0xFFFFFFFF.toInt()
    val black = 0xFF000000.toInt()
    val whiteContrast = contrastRatio(white, fill)
    val blackContrast = contrastRatio(black, fill)
    return when {
        whiteContrast >= TEXT_CONTRAST && whiteContrast >= blackContrast -> white
        blackContrast >= TEXT_CONTRAST -> black
        whiteContrast >= blackContrast -> white
        else -> black
    }
}

/**
 * Donate cards hardcode white text. The fill keeps the seed hue and is dark
 * enough for that text. When a color also clears 3:1 against the page, the
 * lightest such fill is used so the card stays visible.
 */
private fun donateFill(seed: Int, surfaces: List<Int>): Int {
    val white = 0xFFFFFFFF.toInt()
    val hsl = rgbToHsl(seed)
    var bestBoth: Int? = null
    var bestText: Int? = null
    var l = 0.0
    while (l <= 1.0001) {
        val color = hslToColor(hsl[0], hsl[1], l.coerceIn(0.0, 1.0))
        val text = contrastRatio(white, color)
        val component = surfaces.minOf { contrastRatio(color, it) }
        if (text >= TEXT_CONTRAST) {
            bestText = color
            if (component >= COMPONENT_CONTRAST) bestBoth = color
        }
        l += 0.005
    }
    var result = bestBoth ?: bestText ?: seed
    var guard = 0
    while (contrastRatio(white, result) < TEXT_CONTRAST && guard++ < 40) {
        val current = rgbToHsl(result)
        result = hslToColor(current[0], current[1], (current[2] - 0.01).coerceAtLeast(0.0))
    }
    return result
}

private fun darker(color: Int): Int {
    val hsl = rgbToHsl(color)
    return hslToColor(hsl[0], hsl[1], (hsl[2] - 0.12).coerceAtLeast(0.0))
}

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
