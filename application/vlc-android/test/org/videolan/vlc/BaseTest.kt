package org.videolan.vlc

import android.Manifest
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.vlc.util.Permissions

@RunWith(RobolectricTestRunner::class)
// SDK 36's Robolectric shadow of Environment.isExternalStorageManager throws
// ArrayIndexOutOfBoundsException. API 29 takes the pre-Android 11 permission path
// and never calls that method. The app target stays 36.
@Config(application = VLCTestApplication::class, manifest = Config.NONE, sdk = [29])
abstract class BaseTest {
    val context: Context = ApplicationProvider.getApplicationContext()
    val application = (RuntimeEnvironment.application as VLCTestApplication)
    val medialibrary: Medialibrary

    //To prevent Method getMainLooper in android.os.Looper not mocked error when setting value for MutableLiveData
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    init {
        MockKAnnotations.init(this)
        medialibrary = MLServiceLocator.getAbstractMedialibrary().apply { init(context) }
    }

    @Before
    open fun beforeTest() {
        // Inside the Robolectric sandbox. @BeforeClass runs before the sandbox
        // exists and initializing Dispatchers.Main there breaks Looper.
        Dispatchers.setMain(Dispatchers.Unconfined)
        // Robolectric's SDK 36 shadow of Environment.isExternalStorageManager throws.
        // Granting the read permissions makes canReadAudios/canReadVideos return before that call,
        // which is what the library screens require before they page stub data.
        Shadows.shadowOf(application).grantPermissions(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
        )
        Permissions.emptyCache()
        println("beforeTest")
    }

    @After
    open fun afterTest() {
        println("afterTest")
        clearAllMocks()
        Dispatchers.resetMain()
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun cleanupTestClass() {
            unmockkAll()
        }
    }
}