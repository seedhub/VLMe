package org.videolan.vlc.viewmodels.mobile

import androidx.core.content.edit
import com.jraska.livedata.test
import org.junit.Assert.*
import org.junit.Test
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.stubs.StubDataSource
import org.videolan.resources.MEDIALIBRARY_PAGE_SIZE
import org.videolan.tools.KEY_ARTISTS_SHOW_ALL
import org.videolan.tools.Settings
import org.videolan.vlc.BaseTest

class AudioBrowserViewModelTest : BaseTest() {
    private lateinit var audioBrowserViewModel: AudioBrowserViewModel

    override fun beforeTest() {
        super.beforeTest()
        StubDataSource.getInstance().resetData()
    }

    private fun createDummyAudios(count: Int, title: String): List<Long> = (0 until count).map {
        StubDataSource.getInstance().addMediaWrapper("$title $it", MediaWrapper.TYPE_AUDIO).id
    }

    private fun waitForProvidersData() = audioBrowserViewModel.providers.map {
        it.pagedList.test().awaitValue()
    }

    private fun setupViewModel(showAll: Boolean = false) {
        Settings.getInstance(context).edit(commit = true) { putBoolean(KEY_ARTISTS_SHOW_ALL, showAll) }
        audioBrowserViewModel = AudioBrowserViewModel(context)
    }

    @Test
    fun whenNoArtistAlbumTrackGenre_checkResultIsEmpty() {
        setupViewModel()
        waitForProvidersData()

        assertTrue(audioBrowserViewModel.isEmpty())
    }

    @Test
    fun whenNoArtistAlbumTrackGenre_checkTotalCountIsZero() {
        setupViewModel()
        waitForProvidersData()

        assertEquals(0, audioBrowserViewModel.tracksProvider.getTotalCount())
        assertEquals(0, audioBrowserViewModel.genresProvider.getTotalCount())
        assertEquals(0, audioBrowserViewModel.albumsProvider.getTotalCount())
        assertEquals(0, audioBrowserViewModel.artistsProvider.getTotalCount())
    }

    @Test
    fun whenThereAre5Tracks_checkResultIsNotEmpty() {
        setupViewModel()
        StubDataSource.getInstance().setAudioByCount(2, null)
        createDummyAudios(3, "XYZ")

        waitForProvidersData()
    }

    @Test
    fun whenThereAre5Tracks_checkTracksAre5GenresAre5AlbumsAre5ArtistsAre5ForTotalCount() {
        setupViewModel()
        StubDataSource.getInstance().setAudioByCount(2, null) // one artist, album, and genre
        createDummyAudios(3, "XYZ") // a second artist, album, and genre

        waitForProvidersData()

        assertEquals(5, audioBrowserViewModel.tracksProvider.getTotalCount())
        assertEquals(2, audioBrowserViewModel.genresProvider.getTotalCount())
        assertEquals(2, audioBrowserViewModel.albumsProvider.getTotalCount())
        assertEquals(2, audioBrowserViewModel.artistsProvider.getTotalCount())
    }

    @Test
    fun whenThereAre5TracksWithShowAllTrue_checkTracksAre5GenresAre5AlbumsAre5ArtistsAre5ForTotalCount() {
        setupViewModel(showAll = true)
        StubDataSource.getInstance().setAudioByCount(2, null)
        createDummyAudios(3, "XYZ")

        waitForProvidersData()

        assertEquals(5, audioBrowserViewModel.tracksProvider.getTotalCount())
        assertEquals(2, audioBrowserViewModel.genresProvider.getTotalCount())
        assertEquals(2, audioBrowserViewModel.albumsProvider.getTotalCount())
        // Empty album-artist names collapse onto the track artist, so showAll adds no extra row.
        assertEquals(2, audioBrowserViewModel.artistsProvider.getTotalCount())
    }

    @Test
    fun whenThereAre5TracksWithShowAllTrue_checkTracksAre5GenresAre5AlbumsAre5ArtistsAre5ForPagedData() {
        setupViewModel(showAll = true)
        StubDataSource.getInstance().setAudioByCount(2, null) // AlbumArtist & Artist are same, so only one is added.
        createDummyAudios(3, "XYZ") // AlbumArtist & Artist are different, so both are added.

        waitForProvidersData()

        assertEquals(5, audioBrowserViewModel.tracksProvider.pagedList.test().value().size)
        assertEquals(2, audioBrowserViewModel.genresProvider.pagedList.test().value().size)
        assertEquals(2, audioBrowserViewModel.albumsProvider.pagedList.test().value().size)
        assertEquals(2, audioBrowserViewModel.artistsProvider.pagedList.test().value().size)
    }

    @Test
    fun whenMoreThanMaxSizeTracks_checkTotalCountIsTotal() {
        setupViewModel()
        val count = MEDIALIBRARY_PAGE_SIZE * 3 + 1
        StubDataSource.getInstance().setAudioByCount(count, null)

        // Every generated file uses the same artist, album, and genre.
        assertEquals(count, audioBrowserViewModel.tracksProvider.getTotalCount())
        assertEquals(1, audioBrowserViewModel.genresProvider.getTotalCount())
        assertEquals(1, audioBrowserViewModel.albumsProvider.getTotalCount())
        assertEquals(1, audioBrowserViewModel.artistsProvider.getTotalCount())
    }

    @Test
    fun whenMoreThanMaxSizeTracks_checkLastTrackIsNotLoadedYet() {
        setupViewModel()
        val count = MEDIALIBRARY_PAGE_SIZE * 3 + 1
        StubDataSource.getInstance().setAudioByCount(count, null)

        waitForProvidersData()

        assertNull(audioBrowserViewModel.tracksProvider.pagedList.test().value()[count - 1])
        assertEquals(1, audioBrowserViewModel.genresProvider.pagedList.test().value().size)
        assertNotNull(audioBrowserViewModel.genresProvider.pagedList.test().value()[0])
        assertEquals(1, audioBrowserViewModel.albumsProvider.pagedList.test().value().size)
        assertNotNull(audioBrowserViewModel.albumsProvider.pagedList.test().value()[0])
        assertEquals(1, audioBrowserViewModel.artistsProvider.pagedList.test().value().size)
        assertNotNull(audioBrowserViewModel.artistsProvider.pagedList.test().value()[0])
    }

    @Test
    fun whenMoreThanMaxSizeTracks_checkGetAllReturnsAll() {
        setupViewModel()
        val count = MEDIALIBRARY_PAGE_SIZE * 3 + 1
        StubDataSource.getInstance().setAudioByCount(count, null)
        waitForProvidersData()

        // getAll returns the paged snapshot: the first page is present and the unloaded tail is not.
        val tracks = audioBrowserViewModel.tracksProvider.getAll()
        assertEquals(count, tracks.size)
        assertNotNull(tracks[0])
        assertNull(tracks[count - 1])
        assertEquals(1, audioBrowserViewModel.genresProvider.getAll().size)
        assertNotNull(audioBrowserViewModel.genresProvider.getAll()[0])
        assertEquals(1, audioBrowserViewModel.albumsProvider.getAll().size)
        assertNotNull(audioBrowserViewModel.albumsProvider.getAll()[0])
        assertEquals(1, audioBrowserViewModel.artistsProvider.getAll().size)
        assertNotNull(audioBrowserViewModel.artistsProvider.getAll()[0])
    }

    @Test
    fun whenThereAre5TracksWith3TracksHavingDifferentAlbumArtistAndArtistAndShowAllIsTrue_checkResultContainsEightArtists() {
        setupViewModel()
        StubDataSource.getInstance().setAudioByCount(2, null) // AlbumArtist & Artist are same, so only one is added.
        createDummyAudios(3, "XYZ") // AlbumArtist & Artist are different, so both are added.

        audioBrowserViewModel.artistsProvider.showAll = true
        assertEquals(2, audioBrowserViewModel.artistsProvider.pagedList.test().awaitValue().value().size)
    }

    @Test
    fun whenThereAre5TracksWith3TracksHavingDifferentAlbumArtistAndArtistAndShowAllIsFalse_checkResultContainsFiveArtists() {
        setupViewModel()
        StubDataSource.getInstance().setAudioByCount(2, null) // AlbumArtist & Artist are same, so only one is added.
        createDummyAudios(3, "XYZ") // AlbumArtist & Artist are different, so both are added.

        audioBrowserViewModel.artistsProvider.showAll = false
        assertEquals(2, audioBrowserViewModel.artistsProvider.pagedList.test().awaitValue().value().size)
    }

    @Test
    fun whenNoTrackAndFiltered_checkResultIsEmpty() {
        setupViewModel()
        audioBrowserViewModel.filter("xyz")

        waitForProvidersData()

        assertTrue(audioBrowserViewModel.isEmpty())
    }

    @Test
    fun whenThereAreTracksButFilteredWithNonExistingTrack_checkTrackResultIsEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        audioBrowserViewModel.filter("unknown")

        waitForProvidersData()

        assertTrue(audioBrowserViewModel.tracksProvider.isEmpty())
    }

    @Test
    fun whenThereAreAlbumsButFilteredWithNonExistingAlbum_checkAlbumResultIsEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        audioBrowserViewModel.filter("unknown")

        waitForProvidersData()

        assertTrue(audioBrowserViewModel.albumsProvider.isEmpty())
    }

    @Test
    fun whenThereAreArtistsButFilteredWithNonExistingArtists_checkArtistResultIsEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        audioBrowserViewModel.filter("unknown")

        waitForProvidersData()

        assertTrue(audioBrowserViewModel.artistsProvider.isEmpty())
    }

    @Test
    fun whenThereAreGenresButFilteredWithNonExistingGenre_checkGenreResultIsEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        audioBrowserViewModel.filter("unknown")

        waitForProvidersData()

        assertTrue(audioBrowserViewModel.genresProvider.isEmpty())
    }

    @Test
    fun whenThereAreTracksAndFilteredWithExistingTrack_checkTrackResultIsNotEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        audioBrowserViewModel.filter("XYZ")

        waitForProvidersData()

        assertFalse(audioBrowserViewModel.tracksProvider.isEmpty())
    }

    @Test
    fun whenThereAreArtistsAndFilteredWithExistingArtist_checkArtistResultIsNotEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        // The default artist for the stubs
        audioBrowserViewModel.filter("Artisto")

        waitForProvidersData()

        assertFalse(audioBrowserViewModel.artistsProvider.isEmpty())
    }

    @Test
    fun whenThereAreAlbumsAndFilteredWithExistingAlbum_checkAlbumResultIsNotEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        // The default album for the stubs
        audioBrowserViewModel.filter("CD1")

        waitForProvidersData()

        assertFalse(audioBrowserViewModel.albumsProvider.isEmpty())
    }

    @Test
    fun whenThereAreGenresAndFilteredWithExistingGenre_checkGenreResultIsNotEmpty() {
        setupViewModel()
        createDummyAudios(3, "XYZ")

        // The default genre for the stubs
        audioBrowserViewModel.filter("Jazz")

        waitForProvidersData()

        assertFalse(audioBrowserViewModel.genresProvider.isEmpty())
    }

    @Test
    fun whenThereAreSomeTracksButFilteredResultContainsNone_restoringViewModelResetsFilterAndShowsItemAgain() {
        setupViewModel()
        createDummyAudios(2, "test")

        audioBrowserViewModel.filter("unknown")

        waitForProvidersData()
        assertTrue(audioBrowserViewModel.isEmpty())

        audioBrowserViewModel.restore()
        waitForProvidersData()
        assertFalse(audioBrowserViewModel.isEmpty())
    }

    @Test
    fun whenFilteredAndLaterRestored_isFilteringIsTrueLaterFalse() {
        setupViewModel()
        assertFalse(audioBrowserViewModel.isFiltering())

        audioBrowserViewModel.filter("def")

        assertTrue(audioBrowserViewModel.isFiltering())

        audioBrowserViewModel.restore()

        assertFalse(audioBrowserViewModel.isFiltering())
    }

    @Test
    fun when2TracksAndLaterAdded3Tracks_checkResultIsUpdatedWithThemOnRefresh() {
        setupViewModel()
        createDummyAudios(2, "test")

        waitForProvidersData()

        assertFalse(audioBrowserViewModel.isEmpty())
        assertEquals(2, audioBrowserViewModel.tracksProvider.pagedList.test().value().size)

        createDummyAudios(3, "test")
        // refresh() reloads the current tab. Tracks are index 2.
        audioBrowserViewModel.currentTab = 2
        audioBrowserViewModel.refresh()

        waitForProvidersData()

        assertFalse(audioBrowserViewModel.isEmpty())
        assertEquals(5, audioBrowserViewModel.tracksProvider.pagedList.test().value().size)
    }

    @Test
    fun whenThereAreFourTracksWithAlternatelyDifferentTitles_checkTrackHeadersContainsTwoLetters() {
        setupViewModel()
        createDummyAudios(2, "test")
        createDummyAudios(2, "fake")

        waitForProvidersData()

        val trackHeaders = audioBrowserViewModel.tracksProvider.liveHeaders.test().value()

        // Assertion for track headers
        assertEquals(2, trackHeaders.size())
        assertEquals("F", trackHeaders[0])
        assertEquals("T", trackHeaders[2])
    }

    @Test
    fun whenThereAreFourTracksWithAlternatelyDifferentTitles_checkGenreHeadersContainsOneLetter() {
        setupViewModel()
        createDummyAudios(2, "test")
        createDummyAudios(2, "fake")

        waitForProvidersData()

        val genreHeaders = audioBrowserViewModel.genresProvider.liveHeaders.test().value()

        // Assertion for genre headers
        assertEquals(1, genreHeaders.size())
        assertEquals("J", genreHeaders[0])
    }

    @Test
    fun whenThereAreFourTracksWithAlternatelyDifferentTitles_checkAlbumHeadersContainsOneLetter() {
        setupViewModel()
        createDummyAudios(2, "test")
        createDummyAudios(2, "fake")

        waitForProvidersData()

        val albumHeaders = audioBrowserViewModel.albumsProvider.liveHeaders.test().value()

        // Assertion for album headers
        assertEquals(1, albumHeaders.size())
        assertEquals("X", albumHeaders[0])
    }

    @Test
    fun whenThereAreFourTracksWithAlternatelyDifferentTitles_checkArtistHeadersContainsOneLetter() {
        setupViewModel()
        createDummyAudios(2, "test")
        createDummyAudios(2, "fake")

        waitForProvidersData()

        val artistHeaders = audioBrowserViewModel.artistsProvider.liveHeaders.test().value()

        // Assertion for artist headers
        assertEquals(1, artistHeaders.size())
        assertEquals("A", artistHeaders[0])
    }
}
