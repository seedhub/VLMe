package org.videolan.vlc.viewmodels.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import com.jraska.livedata.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import junit.framework.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.stubs.StubMedia
import org.videolan.libvlc.util.MediaBrowser
import org.robolectric.Shadows
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.stubs.StubMediaWrapper
import org.videolan.tools.CoroutineContextProvider
import org.videolan.tools.Settings
import org.videolan.vlc.BaseTest
import org.videolan.vlc.database.BrowserFavDao
import org.videolan.vlc.providers.BrowserProvider
import org.videolan.vlc.repository.BrowserFavRepository
import org.videolan.vlc.util.TestCoroutineContextProvider
import org.videolan.vlc.util.applyMock
import java.io.File


class FileBrowserModelTest : BaseTest() {
    // Preferences choose directories to add in medialibrary scan.

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mockedLibVlc: LibVLC = mockk(relaxed = true)
    private lateinit var mediaBrowser: MediaBrowser
    private val mockedFavoritesDao: BrowserFavDao = mockk(relaxed = true)
    private val mockedFavoritesRepo: BrowserFavRepository = spyk(BrowserFavRepository(mockedFavoritesDao))

    private lateinit var browserModel: BrowserModel
    private lateinit var browserProvider: BrowserProvider

    private val countVideos = 2
    private val countDirs = 4
    private var browseListener: MediaBrowser.EventListener? = null
    private var scriptedPath: String? = null
    private var scriptedChildren: List<File> = emptyList()

    init {
        BrowserFavRepository.applyMock(mockedFavoritesRepo)
        BrowserProvider.overrideCreator = false
        BrowserProvider.registerCreator(clazz = CoroutineContextProvider::class.java) { TestCoroutineContextProvider() }
    }

    override fun beforeTest() {
        super.beforeTest()
        val handler: Handler = mockk()
        BrowserProvider.overrideCreator = true
        BrowserProvider.registerCreator {
            this@FileBrowserModelTest.mediaBrowser = spyk(MediaBrowser(mockedLibVlc, null, handler))
            every { mediaBrowser.changeEventListener(any()) } answers {
                browseListener = args[0] as? MediaBrowser.EventListener
            }
            every { mediaBrowser.browse(any<Uri>(), any()) } answers {
                emitBrowse(args[0] as Uri)
            }
            every { mediaBrowser.browse(any<String>(), any()) } answers {
                emitBrowse((args[0] as String).toUri())
            }
            mediaBrowser
        }
        BrowserProvider.overrideCreator = false
        setupTestFiles()
    }

    private fun initBrowserModel(url: String?, showHiddenFiles: Boolean, showDummyCategory: Boolean = false) {
        Settings.showHiddenFiles = showHiddenFiles
        browserModel = BrowserModel(application, url, TYPE_FILE, showDummyCategory, coroutineContextProvider = TestCoroutineContextProvider())
        browserProvider = browserModel.provider
        mediaBrowser = BrowserProvider.get(browserProvider)
    }

    private fun setupTestFiles() {
        (0 until countDirs).map { temporaryFolder.newFile("dir$it") }
        (0 until countVideos).map { temporaryFolder.newFile("video$it.mp4") }
    }

    private fun addFileToProvider(i: Int, file: File) {
        val t = StubMedia(mockedLibVlc, "file://${file.path}").apply { if (!file.name.endsWith(".mp4")) type = IMedia.Type.Directory }
        browserProvider.addMedia(StubMediaWrapper(t))
    }

    private fun emitBrowse(uri: Uri) {
        val listener = browseListener ?: return
        val path = uri.path
        val children = when {
            scriptedPath != null && path == scriptedPath -> scriptedChildren
            path != null && File(path).isDirectory -> File(path).listFiles()?.sortedBy { it.name }.orEmpty()
            else -> emptyList()
        }
        // Deliver after browse() returns. Calling the listener inline re-enters the
        // provider actor that is waiting on this browse and the folder map never updates.
        Handler(Looper.getMainLooper()).post {
            children.forEachIndexed { index, file ->
                val media = StubMedia(mockedLibVlc, file.toURI().toString()).apply {
                    type = if (file.name.endsWith(".mp4")) IMedia.Type.File else IMedia.Type.Directory
                }
                listener.onMediaAdded(index, media)
            }
            listener.onBrowseEnd()
        }
    }

    private fun fillFilesInDataset(file: File) {
        val files = file.listFiles()?.sortedBy { it.name }.orEmpty()
        files.forEachIndexed(this::addFileToProvider)
        scriptedChildren = files
        browserProvider.parseSubDirectories()
        repeat(8) { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun getFakeBrowserFav(index: Int): org.videolan.vlc.mediadb.models.BrowserFav {
        val t = temporaryFolder.newFile("fake_media$index")
        return org.videolan.vlc.mediadb.models.BrowserFav(t.path.toUri(), 0, "vid_$index", null)
    }

    @Test
    fun whenHideHiddenFiles_checkBrowserFlagsOmitShowHiddenFiles() {
        initBrowserModel(null, showHiddenFiles = false)
        assertEquals(0, browserProvider.getFlags(false) and MediaBrowser.Flag.ShowHiddenFiles)
    }

    @Test
    fun whenShowHiddenFiles_checkBrowserFlagsIncludeShowHiddenFiles() {
        initBrowserModel(null, showHiddenFiles = true)
        assertTrue(browserProvider.getFlags(false) and MediaBrowser.Flag.ShowHiddenFiles != 0)
    }

    @Test
    fun whenAtRootAndInternalStorageIsEmpty_checkShowsFolderIsEmpty() {
        initBrowserModel(null, showHiddenFiles = false)

        val internalStorage = browserModel.dataset.test()
                .awaitValue()
                .value()[0]

        // TODO Has to wait for browserChannel queue
        Thread.sleep(1000)

        assertTrue(browserModel.isFolderEmpty(internalStorage as MediaWrapper))
    }

    @Test
    fun whenAtRootAndInternalStorageHasDirectories_checkShowsFolderIsNotEmpty() {
        initBrowserModel(null, showHiddenFiles = false)

        val internalStorage = browserModel.dataset.test()
                .awaitValue()
                .value()[0] as MediaWrapper

        scriptedPath = internalStorage.uri.path
        fillFilesInDataset(temporaryFolder.root)

        assertFalse(browserModel.isFolderEmpty(internalStorage))
    }

    @Test
    fun whenAtRootAndSavedList_checkPrefetchListIsFilled() {
        initBrowserModel(null, showHiddenFiles = false)

        val internalStorage = browserModel.dataset.test()
                .awaitValue()
                .value()[0] as MediaWrapper

        scriptedPath = internalStorage.uri.path
        fillFilesInDataset(temporaryFolder.root)

        browserModel.saveList(internalStorage)

        initBrowserModel(internalStorage.uri.toString(), false)
        repeat(8) { Shadows.shadowOf(Looper.getMainLooper()).idle() }

        val testResult = browserModel.dataset.test().awaitValue().value()

        assertEquals(countDirs + countVideos, testResult.size)
    }

    @Test
    fun whenAtRootAndHasLocalFavorite_checkDataSetContainsIt() {
        // The file browser screen shows favorites in their own list, fed by getFavDao().
        val favorites = MutableStateFlow(emptyList<org.videolan.vlc.mediadb.models.BrowserFav>())
        every { mockedFavoritesRepo.getFavDao() } returns favorites

        initBrowserModel(null, showHiddenFiles = false)

        val storage = browserModel.dataset.test()
                .awaitValue()
                .value()
        assertEquals(1, storage.size)

        val favorite = getFakeBrowserFav(0)
        favorites.value = listOf(favorite)
        val favoritesModel = BrowserFavoritesModel(context)

        val shown = favoritesModel.favorites.test()
                .awaitValue()
                .value()
        assertEquals(1, shown.size)
        assertEquals("vid_0", shown[0].title)
        assertEquals(favorite.uri.path, (shown[0] as MediaWrapper).uri.path)
        assertTrue(shown[0].hasStateFlags(MediaLibraryItem.FLAG_FAVORITE))
        assertEquals(1, browserModel.dataset.test().value().size)
    }
}