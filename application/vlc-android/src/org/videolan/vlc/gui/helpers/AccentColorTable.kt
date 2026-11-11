/*
 * *************************************************************************
 *  AccentColorTable.kt
 * **************************************************************************
 *  Copyright © 2021 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Ported to Kotlin for VLMe from Material Components' ColorResourcesTableCreator
 *  (1.13, including the type-id pool and UTF-8 length fixes). The resource
 *  package name is taken from the resource table rather than applicationId,
 *  because this fork's applicationId and resource namespace differ.
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.helpers

import java.io.ByteArrayOutputStream

internal data class AccentColorSlot(val id: Int, val entryName: String, val argb: Int)

/**
 * Builds a resources.arsc table that overrides the given color resource ids.
 * [ResourcesLoader] (API 30+) and, below that, AssetManager consume this table.
 */
internal fun createAccentColorTable(packageName: String, slots: List<AccentColorSlot>): ByteArray {
    require(slots.isNotEmpty()) { "No accent colors to install" }
    require(packageName.isNotEmpty() && packageName.length <= 128) { "Bad resource package name" }
    val packageId = (slots[0].id ushr 24) and 0xFF
    val typeId = (slots[0].id ushr 16) and 0xFF
    require(packageId == 0x7F) { "Accent colors are not in the app package: $packageId" }
    require(typeId != 0) { "Accent color type id is 0" }
    val sorted = slots.map { slot ->
        val slotPackage = (slot.id ushr 24) and 0xFF
        val slotType = (slot.id ushr 16) and 0xFF
        require(slotPackage == packageId && slotType == typeId) { "Mixed color resource ids" }
        require(slot.entryName.isNotEmpty()) { "Missing color entry name" }
        slot
    }.sortedBy { it.id and 0xFFFF }
    val typeStrings = stringPool(utf8 = false, *(typeNames(typeId)))
    val keyStrings = stringPool(utf8 = true, *sorted.map { it.entryName }.toTypedArray())
    val entries = sorted.mapIndexed { index, slot -> resEntry(index, slot.argb) }
    val entryCount = (sorted.last().id and 0xFFFF) + 1
    val typeSpec = typeSpecChunk(typeId, entryCount, sorted.map { it.id and 0xFFFF }.toSet())
    val typeChunk = typeChunk(typeId, entryCount, sorted.map { it.id and 0xFFFF }.toSet(), entries)
    val globalStrings = stringPool(utf8 = false)
    val packageChunk = packageChunk(packageId, packageName, typeStrings, keyStrings, typeSpec, typeChunk)
    return resTable(globalStrings, packageChunk)
}

private fun typeNames(typeId: Int): Array<String> {
    val names = Array(typeId) { index -> "?" + (index + 1) }
    names[typeId - 1] = "color"
    return names
}

private fun resTable(globalStrings: ByteArray, packageChunk: ByteArray): ByteArray {
    val headerSize = 0x0C
    val size = headerSize + globalStrings.size + packageChunk.size
    val out = ByteArrayOutputStream()
    out.write(leShort(0x0002))
    out.write(leShort(headerSize))
    out.write(leInt(size))
    out.write(leInt(1))
    out.write(globalStrings)
    out.write(packageChunk)
    val bytes = out.toByteArray()
    check(bytes.size == size)
    return bytes
}

private fun packageChunk(
        packageId: Int,
        packageName: String,
        typeStrings: ByteArray,
        keyStrings: ByteArray,
        typeSpec: ByteArray,
        typeChunk: ByteArray,
): ByteArray {
    val headerSize = 0x120
    val size = headerSize + typeStrings.size + keyStrings.size + typeSpec.size + typeChunk.size
    val out = ByteArrayOutputStream()
    out.write(leShort(0x0200))
    out.write(leShort(headerSize))
    out.write(leInt(size))
    out.write(leInt(packageId))
    val chars = packageName.toCharArray()
    repeat(128) { index ->
        val code = if (index < chars.size) chars[index].code else 0
        out.write(leShort(code))
    }
    out.write(leInt(headerSize))
    out.write(leInt(0))
    out.write(leInt(headerSize + typeStrings.size))
    out.write(leInt(0))
    out.write(leInt(0))
    out.write(typeStrings)
    out.write(keyStrings)
    out.write(typeSpec)
    out.write(typeChunk)
    val bytes = out.toByteArray()
    check(bytes.size == size)
    return bytes
}

private fun typeSpecChunk(typeId: Int, entryCount: Int, present: Set<Int>): ByteArray {
    val headerSize = 0x10
    val size = headerSize + entryCount * 4
    val out = ByteArrayOutputStream()
    out.write(leShort(0x0202))
    out.write(leShort(headerSize))
    out.write(leInt(size))
    out.write(byteArrayOf((typeId and 0xFF).toByte(), 0, 0, 0))
    out.write(leInt(entryCount))
    for (entry in 0 until entryCount) {
        out.write(leInt(if (entry in present) 0x40000000 else 0))
    }
    val bytes = out.toByteArray()
    check(bytes.size == size)
    return bytes
}

private fun typeChunk(typeId: Int, entryCount: Int, present: Set<Int>, entries: List<ByteArray>): ByteArray {
    val headerSize = 0x54
    val entryStart = headerSize + entryCount * 4
    var packed = 0
    val offsets = IntArray(entryCount) { 0xFFFFFFFF.toInt() }
    for (entry in 0 until entryCount) {
        if (entry in present) {
            offsets[entry] = packed
            packed += 16
        }
    }
    val size = entryStart + entries.sumOf { it.size }
    val out = ByteArrayOutputStream()
    out.write(leShort(0x0201))
    out.write(leShort(headerSize))
    out.write(leInt(size))
    out.write(byteArrayOf((typeId and 0xFF).toByte(), 0, 0, 0))
    out.write(leInt(entryCount))
    out.write(leInt(entryStart))
    val config = ByteArray(0x40)
    config[0] = 0x40
    out.write(config)
    offsets.forEach { out.write(leInt(it)) }
    entries.forEach { out.write(it) }
    val bytes = out.toByteArray()
    check(bytes.size == size)
    return bytes
}

private fun resEntry(keyIndex: Int, argb: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(leShort(8))
    out.write(leShort(0x0002))
    out.write(leInt(keyIndex))
    out.write(leShort(8))
    out.write(byteArrayOf(0, 0x1C))
    out.write(leInt(argb))
    return out.toByteArray()
}

private fun stringPool(utf8: Boolean, vararg raw: String): ByteArray {
    val encoded = raw.map { if (utf8) utf8String(it) else utf16String(it) }
    var cursor = 0
    val index = IntArray(encoded.size)
    encoded.forEachIndexed { i, bytes ->
        index[i] = cursor
        cursor += bytes.size
    }
    val pad = (4 - cursor % 4) % 4
    val headerSize = 0x1C
    val stringsStart = headerSize + encoded.size * 4
    val size = stringsStart + cursor + pad
    val out = ByteArrayOutputStream()
    out.write(leShort(0x0001))
    out.write(leShort(headerSize))
    out.write(leInt(size))
    out.write(leInt(encoded.size))
    out.write(leInt(0))
    out.write(leInt(if (utf8) 0x100 else 0))
    out.write(leInt(stringsStart))
    out.write(leInt(0))
    index.forEach { out.write(leInt(it)) }
    encoded.forEach { out.write(it) }
    if (pad > 0) out.write(ByteArray(pad))
    val bytes = out.toByteArray()
    check(bytes.size == size)
    return bytes
}

private fun utf16String(value: String): ByteArray {
    val chars = value.toCharArray()
    val out = ByteArray(chars.size * 2 + 4)
    out[0] = (chars.size and 0xFF).toByte()
    out[1] = ((chars.size ushr 8) and 0xFF).toByte()
    chars.forEachIndexed { i, c ->
        out[2 + i * 2] = (c.code and 0xFF).toByte()
        out[3 + i * 2] = ((c.code ushr 8) and 0xFF).toByte()
    }
    return out
}

private fun utf8String(value: String): ByteArray {
    val raw = value.toByteArray(Charsets.UTF_8)
    return encodeUtf8Len(value.length) + encodeUtf8Len(raw.size) + raw + byteArrayOf(0)
}

private fun encodeUtf8Len(length: Int): ByteArray {
    return if (length > 0x7F) {
        byteArrayOf(
                (((length shr 8) and 0x7F) or 0x80).toByte(),
                (length and 0xFF).toByte(),
        )
    } else {
        byteArrayOf((length and 0xFF).toByte())
    }
}

private fun leShort(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
)

private fun leInt(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
)
