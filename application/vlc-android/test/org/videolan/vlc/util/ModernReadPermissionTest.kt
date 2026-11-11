package org.videolan.vlc.util

import android.Manifest
import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The rest of the unit suite stays on API 29 because Robolectric's SDK 36
 * shadow of [Environment.isExternalStorageManager] throws. This test stubs that
 * method and exercises the Android 13 read-permission branch directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModernReadPermissionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun stubAllFilesAccessOff() {
        mockkStatic(Environment::class)
        every { Environment.isExternalStorageManager() } returns false
        Permissions.emptyCache()
    }

    @After
    fun cleanup() {
        Permissions.emptyCache()
        unmockkStatic(Environment::class)
    }

    @Test
    fun readMediaAudioAllowsAudioAndStorageReadsWhenAllFilesAccessIsOff() {
        val shadow = Shadows.shadowOf(app)
        shadow.denyPermissions(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
        )
        Permissions.emptyCache()
        assertFalse(Permissions.canReadAudios(app))
        assertFalse(Permissions.canReadStorage(app))

        shadow.grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        Permissions.emptyCache()
        assertTrue(Permissions.canReadAudios(app))
        assertTrue(Permissions.canReadStorage(app))
    }
}
