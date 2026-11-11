/*
 * *************************************************************************
 *  AccentPaletteTest.kt
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Contrast is checked against the colors the themes actually resolve:
 * light window/sheet white, dark window #131313, dark sheet grey875 #2A2A2A,
 * player background_default grey850 #323232, player sheet black @ 80%,
 * onboarding #011422.
 */
@RunWith(JUnit4::class)
class AccentPaletteTest {

    private val lightApp = intArrayOf(0xFFFFFFFF.toInt())
    private val darkApp = intArrayOf(0xFF131313.toInt(), 0xFF2A2A2A.toInt())
    private val player = intArrayOf(0xFF323232.toInt(), 0xCC000000.toInt())
    private val onboarding = intArrayOf(0xFF011422.toInt())

    @Test
    fun stockOrangeKeepsTheBaseTheme() {
        assertNull(canonicalAccentSeed(STOCK_ORANGE))
        assertNull(canonicalAccentSeed(0x80FF610A.toInt()))
    }

    @Test
    fun legacyMaterial500MapsOntoThePreset() {
        val legacy = mapOf(
                0xFF2196F3.toInt() to PRESET_BLUE,
                0xFF4CAF50.toInt() to PRESET_GREEN,
                0xFFF44336.toInt() to PRESET_RED,
                0xFF9C27B0.toInt() to PRESET_PURPLE,
                0xFF009688.toInt() to PRESET_TEAL,
                0xFFE91E63.toInt() to PRESET_PINK,
        )
        for ((old, preset) in legacy) {
            assertEquals(preset, canonicalAccentSeed(old))
            assertEquals(preset, canonicalAccentSeed(old and 0x00FFFFFF))
            for (surfaces in listOf(lightApp, darkApp, player)) {
                assertEquals(
                        buildAccentColors(preset, surfaces).primary,
                        buildAccentColors(canonicalAccentSeed(old)!!, surfaces).primary,
                )
            }
        }
    }

    @Test
    fun legacyBlueIsNotLeftAsTheTooLight500() {
        val legacy = 0xFF2196F3.toInt()
        assertNotEquals(PRESET_BLUE, buildAccentColors(legacy, lightApp).primary)
        assertEquals(PRESET_BLUE, buildAccentColors(canonicalAccentSeed(legacy)!!, lightApp).primary)
    }

    @Test
    fun arbitraryColorsAreNotCollapsedToAPreset() {
        val custom = intArrayOf(0xFF123456.toInt(), 0xFFC0FFEE.toInt(), 0xFFFF00FF.toInt(), 0xFF010203.toInt())
        val presets = setOf(PRESET_BLUE, PRESET_GREEN, PRESET_RED, PRESET_PURPLE, PRESET_TEAL, PRESET_PINK, STOCK_ORANGE)
        for (color in custom) {
            val seed = canonicalAccentSeed(color)
            assertEquals(color, seed)
            assertTrue(seed !in presets)
        }
    }

    @Test
    fun presetsStayExactOnWhiteWhenTheyAlreadyPass() {
        for (preset in arrayOf(PRESET_BLUE, PRESET_GREEN, PRESET_RED, PRESET_PURPLE, PRESET_TEAL, PRESET_PINK)) {
            val colors = buildAccentColors(preset, lightApp)
            assertEquals(hex(preset), hex(colors.primary))
            assertReadable(colors, lightApp)
        }
    }

    @Test
    fun presetsAreRetunedForDarkAppPlayerAndOnboarding() {
        for (preset in arrayOf(PRESET_BLUE, PRESET_GREEN, PRESET_RED, PRESET_PURPLE, PRESET_TEAL, PRESET_PINK)) {
            for (surfaces in listOf(darkApp, player, onboarding)) {
                val colors = buildAccentColors(preset, surfaces)
                assertNotEquals(preset, colors.primary)
                assertReadable(colors, surfaces)
                assertHue(preset, colors.primary)
            }
        }
    }

    @Test
    fun arbitraryAndExtremeSeedsPassInBothModes() {
        val seeds = intArrayOf(
                0xFF123456.toInt(),
                0xFFC0FFEE.toInt(),
                0xFFFF00FF.toInt(),
                0xFF010203.toInt(),
                0xFF000000.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF808080.toInt(),
                0xFF111111.toInt(),
                0xFFEEEEEE.toInt(),
        )
        for (seed in seeds) {
            for (surfaces in listOf(lightApp, darkApp, player, onboarding)) {
                assertReadable(buildAccentColors(seed, surfaces), surfaces)
            }
        }
    }

    @Test
    fun blackAndWhiteKeepTheirIdentityWhenContrastAlreadyHolds() {
        assertEquals(0xFF000000.toInt(), buildAccentColors(0xFF000000.toInt(), lightApp).primary)
        assertEquals(0xFFFFFFFF.toInt(), buildAccentColors(0xFFFFFFFF.toInt(), darkApp).primary)
        val whiteOnDark = buildAccentColors(0xFFFFFFFF.toInt(), darkApp)
        assertEquals(0xFF000000.toInt(), whiteOnDark.onPrimary)
        val blackOnLight = buildAccentColors(0xFF000000.toInt(), lightApp)
        assertEquals(0xFFFFFFFF.toInt(), blackOnLight.onPrimary)
    }

    @Test
    fun modeChangeMovesLightnessTowardTheBackground() {
        val seed = 0xFFE91E63.toInt()
        val onLight = buildAccentColors(seed, lightApp).primary
        val onDark = buildAccentColors(seed, darkApp).primary
        assertTrue(relativeLuminance(onDark) > relativeLuminance(onLight))
        assertReadable(buildAccentColors(seed, lightApp), lightApp)
        assertReadable(buildAccentColors(seed, darkApp), darkApp)
    }

    @Test
    fun onPrimaryIsNeverWhiteOnALightFill() {
        val seeds = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFFC0FFEE.toInt(), PRESET_PINK, 0xFF808080.toInt())
        for (seed in seeds) {
            for (surfaces in listOf(lightApp, darkApp, player)) {
                val colors = buildAccentColors(seed, surfaces)
                if (contrastRatio(0xFFFFFFFF.toInt(), colors.primary) < TEXT_CONTRAST) {
                    assertEquals(0xFF000000.toInt(), colors.onPrimary)
                }
                assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun donateCardKeepsWhiteTextReadable() {
        val seeds = intArrayOf(PRESET_PURPLE, PRESET_TEAL, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF123456.toInt())
        for (seed in seeds) {
            for (surfaces in listOf(lightApp, darkApp, player)) {
                val donate = buildAccentColors(seed, surfaces).donate
                assertTrue(
                        "donate ${hex(donate)} from ${hex(seed)}",
                        contrastRatio(0xFFFFFFFF.toInt(), donate) >= TEXT_CONTRAST,
                )
            }
        }
    }

    @Test
    fun focusScrimsUseThePrimaryHue() {
        val colors = buildAccentColors(0xFF123456.toInt(), darkApp)
        assertEquals(0x1A, (colors.focus10 ushr 24) and 0xFF)
        assertEquals(0x33, (colors.focus20 ushr 24) and 0xFF)
        assertEquals(colors.primary and 0x00FFFFFF, colors.focus10 and 0x00FFFFFF)
        assertEquals(colors.primary and 0x00FFFFFF, colors.focus20 and 0x00FFFFFF)
    }

    @Test
    fun savedColorConstantsMatchTheResourceXml() {
        val xml = repoFile("application/resources/src/main/res/values/colors.xml").readText()
        assertTrue(xml.contains("#ff610a"))
        assertTrue(xml.contains("#1565c0"))
        assertTrue(xml.contains("#2e7d32"))
        assertTrue(xml.contains("#c62828"))
        assertTrue(xml.contains("#6a1b9a"))
        assertTrue(xml.contains("#00695c"))
        assertTrue(xml.contains("#c2185b"))
        assertTrue(xml.contains("#131313"))
        assertTrue(xml.contains("#323232"))
        assertTrue(xml.contains("#2a2a2a"))
        assertTrue(xml.contains("#011422"))
    }

    @Test
    fun themeEntryPointsUseTheSharedOverlay() {
        val styles = repoFile("application/vlc-android/res/values/styles.xml").readText()
        assertTrue(styles.contains("ThemeOverlay.VLMe.Accent.Runtime"))
        assertTrue(styles.contains("ThemeOverlay.VLMe.Accent.Blue.Night"))
        assertTrue(styles.contains("colorOnPrimary\">#FF000000"))
        assertTrue(styles.contains("android:textColor\">?attr/colorOnPrimary"))

        val ui = repoFile("application/vlc-android/src/org/videolan/vlc/gui/helpers/UiTools.kt").readText()
        assertTrue(ui.contains("applyAccentOverlay()"))
        val player = repoFile("application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt").readText()
        val playerOnCreate = player.substringAfter("override fun onCreate")
        assertTrue(playerOnCreate.indexOf("applyAccentOverlay()") < playerOnCreate.indexOf("super.onCreate"))
        val dialog = repoFile("application/vlc-android/src/org/videolan/vlc/gui/dialogs/VLCBottomSheetDialogFragment.kt").readText()
        assertTrue(dialog.contains("applyAccentOverlay()"))
        val onboarding = repoFile("application/vlc-android/src/org/videolan/vlc/gui/onboarding/OnboardingActivity.kt").readText()
        val onboardingOnCreate = onboarding.substringAfter("override fun onCreate")
        assertTrue(onboardingOnCreate.indexOf("applyAccentOverlay()") < onboardingOnCreate.indexOf("super.onCreate"))
        val prefs = repoFile("application/vlc-android/res/xml/preferences_ui.xml").readText()
        assertTrue(prefs.contains("cpv_allowCustom=\"true\""))
        val overlay = repoFile("application/vlc-android/src/org/videolan/vlc/gui/helpers/AccentOverlay.kt").readText()
        assertTrue(!overlay.contains("addAssetPath("))
        assertTrue(!overlay.contains("VMRuntime"))
        assertTrue(!overlay.contains("setHiddenApiExemptions"))
        val presetBranch = overlay.substringAfter("val preset = presetStyle").substringBefore("val colors = buildAccentColors")
        assertTrue(presetBranch.contains("theme.applyStyle(preset, true)"))
        assertTrue(!presetBranch.contains("Accent_Runtime"))
        val loader = repoFile("application/vlc-android/src/org/videolan/vlc/gui/helpers/AccentColorLoader.kt").readText()
        assertTrue(!loader.contains("applicationContext"))
        val widgets = repoFile("application/vlc-android/src/org/videolan/vlc/gui/helpers/AccentWidgets.kt").readText()
        assertTrue(!widgets.contains("setOnHierarchyChangeListener"))
    }

    @Test
    fun runtimeOverlayRequiresResolvedColorsAndSurfaceKey() {
        val expected = buildAccentColors(PRESET_BLUE, intArrayOf(0xFF323232.toInt()))
        assertTrue(!runtimeOverlayAccepted(29, expected.primary, expected.onPrimary, expected.primary, expected.onPrimary, expected))
        assertTrue(runtimeOverlayAccepted(30, expected.primary, expected.onPrimary, expected.primary, expected.onPrimary, expected))
        assertTrue(!runtimeOverlayAccepted(30, 0xFFFF610A.toInt(), 0xFFFFFFFF.toInt(), expected.primary, expected.onPrimary, expected))
        assertTrue(!runtimeOverlayAccepted(30, expected.primary, expected.onPrimary, 0xFFFF610A.toInt(), expected.onPrimary, expected))
        val day = paletteKey(PRESET_BLUE, intArrayOf(0xFFFFFFFF.toInt()))
        val player = paletteKey(PRESET_BLUE, intArrayOf(0xFF323232.toInt(), 0xFF131313.toInt()))
        assertTrue(day != player)
        assertTrue(surfacesAreDark(intArrayOf(0xFF323232.toInt(), 0xFF131313.toInt())))
        assertTrue(!surfacesAreDark(intArrayOf(0xFFFFFFFF.toInt())))
    }

    @Test
    fun darkPresetStylesMatchPaletteOnRealSurfaces() {
        val styles = repoFile("application/vlc-android/res/values/styles.xml").readText()
        val surfaces = intArrayOf(0xFF323232.toInt(), 0xFF131313.toInt(), 0xFF2A2A2A.toInt())
        val presets = listOf(
                "Blue" to PRESET_BLUE,
                "Green" to PRESET_GREEN,
                "Red" to PRESET_RED,
                "Purple" to PRESET_PURPLE,
                "Teal" to PRESET_TEAL,
                "Pink" to PRESET_PINK,
        )
        for ((name, seed) in presets) {
            val colors = buildAccentColors(seed, surfaces)
            val block = styles.substringAfter("ThemeOverlay.VLMe.Accent.$name.Night").substringBefore("</style>")
            val primaryHex = "#${String.format("%08X", colors.primary.toLong() and 0xFFFFFFFFL)}"
            val onHex = "#${String.format("%08X", colors.onPrimary.toLong() and 0xFFFFFFFFL)}"
            assertTrue("$name $primaryHex", block.contains("colorPrimary\">$primaryHex"))
            assertTrue("$name $onHex", block.contains("colorOnPrimary\">$onHex"))
            assertReadable(colors, surfaces)
        }
    }

    @Test
    fun customAccentNoteShowsOnlyBelowApi30ForNonPresets() {
        val presets = intArrayOf(
                STOCK_ORANGE, PRESET_BLUE, PRESET_GREEN, PRESET_RED, PRESET_PURPLE, PRESET_TEAL, PRESET_PINK,
                0xFF2196F3.toInt(), 0xFF4CAF50.toInt(), 0xFFF44336.toInt(), 0xFF9C27B0.toInt(), 0xFF009688.toInt(), 0xFFE91E63.toInt(),
                0x1565C0,
        )
        for (sdk in intArrayOf(17, 21, 27, 29)) {
            assertTrue(customAccentNoteVisible(sdk, 0xFF123456.toInt()))
            for (preset in presets) assertTrue("sdk $sdk ${hex(preset)}", !customAccentNoteVisible(sdk, preset))
        }
        for (sdk in intArrayOf(30, 33, 36)) {
            assertTrue(!customAccentNoteVisible(sdk, 0xFF123456.toInt()))
            assertTrue(!customAccentNoteVisible(sdk, PRESET_BLUE))
        }
        val strings = repoFile("application/resources/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("Icons, seek bars and checkboxes keep the default color on this Android version."))
    }

    @Test
    fun themeAttributeParserReadsCompiledQuestionMarkIds() {
        assertEquals(0x7f040156, parseThemeAttribute("?2130968918"))
        assertEquals(0x7f040156, parseThemeAttribute("?0x7f040156"))
        assertEquals(null, parseThemeAttribute("#ff610a"))
        assertEquals(null, parseThemeAttribute(null))
    }

    private fun assertReadable(colors: AccentColors, rawSurfaces: IntArray) {
        val surfaces = prepareSurfaces(rawSurfaces)
        assertTrue(surfaces.isNotEmpty())
        for (surface in surfaces) {
            val text = contrastRatio(colors.primary, surface)
            assertTrue("${hex(colors.primary)} on ${hex(surface)} = $text", text >= TEXT_CONTRAST)
            assertTrue(text >= COMPONENT_CONTRAST)
        }
        val onFill = contrastRatio(colors.onPrimary, colors.primary)
        assertTrue("onPrimary ${hex(colors.onPrimary)} on ${hex(colors.primary)} = $onFill", onFill >= TEXT_CONTRAST)
        if (contrastRatio(0xFFFFFFFF.toInt(), colors.primary) < TEXT_CONTRAST) {
            assertEquals(0xFF000000.toInt(), colors.onPrimary)
        }
    }

    private fun assertHue(seed: Int, actual: Int) {
        val seedHsl = rgbToHsl(seed)
        if (seedHsl[1] < 0.2) return
        val delta = hueDelta(seed, actual)
        assertTrue("hue moved $delta from ${hex(seed)} to ${hex(actual)}", delta < 15.0)
    }

    private fun hueDelta(a: Int, b: Int): Double {
        val delta = Math.abs(rgbToHsl(a)[0] - rgbToHsl(b)[0]) * 360.0
        return minOf(delta, 360.0 - delta)
    }

    private fun hex(color: Int) = String.format("#%08X", color)
}

@RunWith(JUnit4::class)
class AccentColorTableTest {

    @Test
    fun tableCarriesArbitraryColorsAndEntryNames() {
        val slots = listOf(
                AccentColorSlot(0x7F05000A, "vlme_accent_primary", 0xFF123456.toInt()),
                AccentColorSlot(0x7F05000C, "vlme_accent_on_primary", 0xFF000000.toInt()),
        )
        val table = createAccentColorTable("org.videolan.mobile.app", slots)
        assertEquals(table.size, readLeInt(table, 4))
        assertTrue(String(table, Charsets.ISO_8859_1).contains("vlme_accent_primary"))
        assertTrue(String(table, Charsets.ISO_8859_1).contains("vlme_accent_on_primary"))
        assertTrue(contains(table, byteArrayOf(0x56, 0x34, 0x12, 0xFF.toByte())))
        assertTrue(contains(table, byteArrayOf(0x00, 0x00, 0x00, 0xFF.toByte())))
    }

    @Test
    fun runtimeLoaderInstallIsStatic() {
        val cls = Class.forName("org.videolan.vlc.gui.helpers.AccentColorLoader")
        val method = cls.getDeclaredMethod(
                "install",
                android.content.res.Resources::class.java,
                ByteArray::class.java,
                String::class.java,
        )
        assertTrue(Modifier.isStatic(method.modifiers))
        assertNotEquals(null, cls.getDeclaredMethod("remove", android.content.res.Resources::class.java))
    }

    @Test
    fun buildToolsReadGeneratedColorTable() {
        val table = createAccentColorTable(
                "org.videolan.mobile.app",
                listOf(AccentColorSlot(0x7F0603D6, "vlme_probe", 0xFF123456.toInt())),
        )
        val root = java.io.File.createTempFile("accent-table", "").apply { delete(); mkdirs() }
        val zip = java.io.File(root, "table.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("resources.arsc"))
            out.write(table)
            out.closeEntry()
        }
        val dump = runTool(locateBuildTool("aapt"), "dump", "resources", zip.absolutePath)
        assertTrue(dump, dump.contains("name=org.videolan.mobile.app"))
        assertTrue(dump, dump.contains("color/vlme_probe"))
        assertTrue(dump, dump.contains("d=0xff123456"))

        val resDir = java.io.File(root, "res/values").apply { mkdirs() }
        java.io.File(resDir, "colors.xml").writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><resources><color name=\"vlme_probe\">#FF123456</color></resources>",
        )
        val manifest = java.io.File(root, "AndroidManifest.xml")
        manifest.writeText("<manifest package=\"org.videolan.mobile.app\"/>")
        val compiled = java.io.File(root, "compiled").apply { mkdirs() }
        runTool(locateBuildTool("aapt2"), "compile", java.io.File(resDir, "colors.xml").absolutePath, "-o", compiled.absolutePath)
        val linked = java.io.File(root, "linked.apk")
        val androidJar = java.io.File(sdkRoot(), "platforms/android-36/android.jar")
        assertTrue(androidJar.isFile)
        val flat = compiled.listFiles()?.first { it.name.endsWith(".flat") }
                ?: throw AssertionError("aapt2 compile produced no flat file")
        runTool(
                locateBuildTool("aapt2"), "link",
                "-o", linked.absolutePath,
                "--manifest", manifest.absolutePath,
                "-I", androidJar.absolutePath,
                flat.absolutePath,
        )
        val linkedDump = runTool(locateBuildTool("aapt2"), "dump", "resources", linked.absolutePath)
        assertTrue(linkedDump, linkedDump.contains("vlme_probe"))
        assertTrue(linkedDump, linkedDump.contains("#ff123456") || linkedDump.contains("0xff123456") || linkedDump.contains("d=0xff123456"))
        root.deleteRecursively()
    }

    private fun sdkRoot(): String =
            System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/home/ubuntu/android-sdk"

    private fun locateBuildTool(name: String): String {
        val tools = java.io.File(sdkRoot(), "build-tools")
        val found = tools.listFiles()?.flatMap { dir ->
            dir.listFiles()?.filter { it.name == name && it.canExecute() } ?: emptyList()
        }?.firstOrNull()
        return found?.absolutePath ?: throw AssertionError("$name not found under ${tools.absolutePath}")
    }

    private fun runTool(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return output
    }

    @Test
    fun tableRejectsANonAppPackageId() {
        var threw = false
        try {
            createAccentColorTable("android", listOf(AccentColorSlot(0x01010001, "white", 0xFFFFFFFF.toInt())))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

private fun repoFile(relative: String): File {
    val candidates = listOf(
            File(relative),
            File("application/vlc-android").resolve(relative.removePrefix("application/vlc-android/")),
            File("..").resolve(relative.removePrefix("application/")),
            File("../..").resolve(relative),
    )
    return candidates.firstOrNull { it.isFile } ?: error("Missing $relative from ${File(".").absolutePath}")
}
