package org.videolan.mobile.app

import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoroutineDebugPropertyTest {
    @Test
    fun propertyIsSetOnlyWhenDebugIsTrue() {
        val previous = System.getProperty(DEBUG_PROPERTY_NAME)
        try {
            System.clearProperty(DEBUG_PROPERTY_NAME)
            applyCoroutineDebugProperty(false)
            assertNull(System.getProperty(DEBUG_PROPERTY_NAME))

            applyCoroutineDebugProperty(true)
            assertEquals(DEBUG_PROPERTY_VALUE_ON, System.getProperty(DEBUG_PROPERTY_NAME))

            applyCoroutineDebugProperty(false)
            assertEquals(DEBUG_PROPERTY_VALUE_ON, System.getProperty(DEBUG_PROPERTY_NAME))
        } finally {
            if (previous == null) System.clearProperty(DEBUG_PROPERTY_NAME)
            else System.setProperty(DEBUG_PROPERTY_NAME, previous)
        }
    }

    @Test
    fun appBuildConfigDebugFollowsTheAppBuildType() {
        val debuggable = when (BuildConfig.BUILD_TYPE) {
            "debug", "dev" -> true
            else -> false
        }
        assertEquals(debuggable, BuildConfig.DEBUG)
    }
}
