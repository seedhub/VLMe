package org.videolan.vlc

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
import org.robolectric.annotation.Config
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary

@RunWith(RobolectricTestRunner::class)
@Config(application = VLCTestApplication::class, manifest = Config.NONE)
open class BaseTest {
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