package org.videolan.vlc.util

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDataTestUtilTest {
    @Test
    fun flowGetValueReturnsTheFirstEmission() = runBlocking {
        val value = getValue(flow {
            emit("first")
            emit("second")
        })
        assertEquals("first", value)
    }
}
