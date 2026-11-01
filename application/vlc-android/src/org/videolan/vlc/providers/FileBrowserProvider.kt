/*****************************************************************************
 * FileBrowserProvider.kt
 *****************************************************************************
 * Copyright © 2018 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.providers

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.DummyItem
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AndroidDevices
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.ExternalMonitor
import org.videolan.vlc.R
import org.videolan.vlc.VlcMigrationHelper
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate
import org.videolan.vlc.gui.helpers.hf.getDocumentFiles
import org.videolan.vlc.repository.DirectoryRepository
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.LASER_TAG
import org.videolan.vlc.util.LaserConnections
import org.videolan.vlc.util.LaserDiscInfo
import org.videolan.vlc.util.awaitUsbStorageProbe
import org.videolan.vlc.util.classifyOpticalDisc
import org.videolan.vlc.util.findUsbOpticalDriveByName
import org.videolan.vlc.util.findUsbOpticalDriveCandidates
import org.videolan.vlc.util.forgetLaserPermissionDenials
import org.videolan.vlc.util.isLaserNoMenuMrl
import org.videolan.vlc.util.isLaserPermissionDenied
import org.videolan.vlc.util.isUsbStorageProbing
import org.videolan.vlc.util.laserTileLocation
import java.io.File

/* How long the laser classification pass waits for a burst of triggers to
 * finish before running once. Long enough to span onViewCreated() and the
 * onResume() that follows it, short enough that a deliberate return to the
 * screen still feels immediate. */
private const val LASER_COALESCE_MS = 300L

open class FileBrowserProvider(
        context: Context,
        dataset: LiveDataset<MediaLibraryItem>,
        url: String?, private val filePicker: Boolean = false,
        private val showDummyCategory: Boolean = true, sort:Int, desc:Boolean) : BrowserProvider(context, dataset,
        url, sort, desc), Observer<MutableList<UsbDevice>> {

    private var storagePosition = -1
    private var otgPosition = -1

    /* The laser optical-disc rows inserted into `dataset`, one group per
     * drive, keyed by its deviceName ("/dev/bus/usb/BBB/DDD"). A group has
     * several rows when a disc is offered several ways: a DVD-Video with and
     * without menus, a universal DVD by its Audio and its Video zone.
     *
     * THE ITEMS, NOT THEIR INDICES: browseRootImpl() replaces the list
     * wholesale, which would leave cached indices pointing into nothing. It
     * puts the groups back ([restoreLaserGroups]); any other replacement
     * drops them, and an item absent from the list yields -1 from
     * [laserTileIndex], meaning "no tile".
     *
     * A key here means "was inserted", NOT "is on screen": ask
     * [laserTileIndex] or [shownLaserDeviceNames]. Display order is by
     * deviceName, see [laserInsertPosition]. */
    private val laserTiles = LinkedHashMap<String, List<MediaLibraryItem>>()

    /* Classifies laser-dir://<fd> through libVLC's browsing seam (see
     * LaserDiscProvider). Created on first use, released in release(). */
    private var usbDiscProvider: LaserDiscProvider? = null

    /* Re-examine the drives whose playback connection was just closed - their
     * disc may have been removed or swapped, as when a disc is taken out
     * mid-playback. Only the named drives: re-examining the others would cost
     * a USB open and a SCSI exchange each, and drop tiles that are still
     * right. Fires on ordinary ends of playback too, which keeps a tile honest
     * about a disc swapped between two sessions. */
    private val usbReleaseListener: (Collection<String>) -> Unit = { deviceNames ->
        /* On the provider's Main scope: the callback arrives on libVLC's event
         * thread, and the dataset must be touched on Main. A released
         * provider's scope is cancelled, so a late callback does nothing. */
        launch { refreshLaser(deviceNames) }
    }

    /* Drives waiting to be classified. Classification is serial - two passes
     * would stack two permission dialogs - but a drive arriving mid-pass is
     * queued rather than dropped: its ATTACHED broadcast has already been
     * delivered, and nothing would call back for it.
     *
     * Touched only from the provider's Main scope. */
    private val laserQueue = LinkedHashSet<String>()
    private var laserDraining = false

    /* The drive being classified now. Its name has left the queue and its
     * group does not exist yet, so without this a pass arriving meanwhile -
     * as onResume() does after every permission dialog - would queue it a
     * second time. */
    private var laserInFlight: String? = null

    /* deviceNames of the candidates seen at the previous pass, so that only
     * NEW candidates are classified. A detach delivers a shrinking list - a
     * combo drive's functions leave one at a time - whose remaining
     * candidates must not trigger a classification, and a permission prompt,
     * on unplug. */
    private var lastLaserCandidateNames: Set<String> = emptySet()

    /* The watch for the kernel's probe to end, so that passes do not start
     * one each. */
    private var laserProbeRetry: Job? = null

    /**
     * Live index of the first row of [deviceName]'s group in [dataset], or -1
     * when that drive has no group displayed - including one dropped by a
     * wholesale replacement of the list, whose entry is then forgotten. The
     * first row is enough: a group's rows go together or not at all.
     */
    private fun laserTileIndex(deviceName: String): Int {
        val items = laserTiles[deviceName] ?: return -1
        val index = dataset.getList().indexOf(items.first())
        if (index == -1) laserTiles.remove(deviceName)
        return index
    }

    /** deviceNames of the DISPLAYED groups - the stale-safe reading. */
    private fun shownLaserDeviceNames(): Set<String> =
        laserTiles.keys.toList().filterTo(HashSet()) { laserTileIndex(it) != -1 }

    /**
     * Forget the cached indices, on every path that replaces dataset.value
     * wholesale. otgPosition and storagePosition are read by
     * [laserInsertPosition], and storagePosition is only assigned when the
     * internal memory is found, so a stale value would otherwise survive.
     */
    private fun resetDatasetPositions() {
        storagePosition = -1
        otgPosition = -1
    }

    private fun getUsbDiscProvider(): LaserDiscProvider {
        return usbDiscProvider ?: LaserDiscProvider(
            context, LiveDataset()
        ).also { usbDiscProvider = it }
    }

    init {
        fetch()
    }

    private lateinit var storageObserver : Observer<Boolean>

    override suspend fun browseRootImpl() {
        resetDatasetPositions()
        loading.postValue(true)
        var storageAccess = false
        val internalmemoryTitle = context.getString(R.string.internal_memory)
        val browserStorage = context.getString(R.string.browser_storages)
        val storages = DirectoryRepository.getInstance(context).getMediaDirectories()
        val devices = mutableListOf<MediaLibraryItem>()
        if (!filePicker && showDummyCategory) devices.add(DummyItem(browserStorage))
        for (mediaDirLocation in storages) {
            val file = File(mediaDirLocation)
            if (!file.exists() || !file.canRead()) continue
            storageAccess = true
            val directory = MLServiceLocator.getAbstractMediaWrapper(AndroidUtil.PathToUri(mediaDirLocation))
            directory.type = MediaWrapper.TYPE_DIR
            if (AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY == mediaDirLocation) {
                directory.setDisplayTitle(internalmemoryTitle)
                storagePosition = devices.size
            } else {
                val deviceName = FileUtils.getStorageTag(directory.title)
                if (deviceName != null) directory.setDisplayTitle(deviceName)
                directory.addStateFlags(MediaLibraryItem.FLAG_STORAGE)
            }
            devices.add(directory)
        }
        if (AndroidUtil.isMarshMallowOrLater && !storageAccess) {
            storageObserver = Observer { if (it == true) launch { browseRoot() } }
            StoragePermissionsDelegate.storageAccessGranted.observeForever(storageObserver)
        }
        // For first launch, storage access may not already be granted
        if (!storageAccess) {
            loading.postValue(false)
            dataset.value = arrayListOf()
            restoreLaserGroups()
            return
        }
        if (VlcMigrationHelper.isLolliPopOrLater && !ExternalMonitor.devices.isEmpty()) {
            val otg = MLServiceLocator.getAbstractMediaWrapper("otg://".toUri()).apply {
                title = context.getString(R.string.otg_device_title)
                type = MediaWrapper.TYPE_DIR
            }
            otgPosition = devices.size
            devices.add(otg)
        }
        dataset.value = devices
        restoreLaserGroups()
        // observe devices & favorites
        ExternalMonitor.devices.observeForever(this@FileBrowserProvider)
        LaserConnections.addReleaseListener(usbReleaseListener)
        loading.postValue(false)
        //no headers in root
        headers.clear()
    }

    /**
     * Re-run the laser classification pass, forgetting which drives were
     * already seen. Called from MainBrowserFragment.onResume(): a disc may
     * have been inserted since. Prompts nobody for a drive whose permission
     * was already granted, or refused (see reclassifyAllLaser()).
     *
     * [dropTilesFor] names drives whose group is dropped first. A displayed
     * drive is otherwise skipped, which is right on attach or detach but not
     * when its disc may have been taken out: the device list is unchanged, so
     * the group would suppress the pass that finds the disc gone.
     */
    fun refreshLaser(dropTilesFor: Collection<String> = emptyList()) {
        if (url != null) return   // only meaningful for the root provider
        lastLaserCandidateNames = emptySet()
        for (deviceName in dropTilesFor) removeLaserEntry(deviceName)
        updateLaserEntry()
    }

    /**
     * Classify every drive again, those with a group displayed included,
     * whose groups are dropped first - for an explicit refresh by the user,
     * which also asks again for the drives whose permission was refused.
     */
    fun reclassifyAllLaser() {
        forgetLaserPermissionDenials()
        refreshLaser(shownLaserDeviceNames())
    }

    override suspend fun requestBrowsing(url: String?, eventListener: MediaBrowser.EventListener, interact : Boolean) = withContext(coroutineContextProvider.IO) {
        initBrowser()
        mediabrowser?.let {
            it.changeEventListener(eventListener)
            if (url != null) it.browse(url.toUri(), getFlags(interact))
        }
    }

    override fun browse(url: String?) {
        when {
            url == "otg://" || url?.startsWith("content:") == true -> launch {
                loading.postValue(true)
                dataset.value = withContext(coroutineContextProvider.IO) {
                    @Suppress("UNCHECKED_CAST")
                    getDocumentFiles(context, url.toUri().path?.substringAfterLast(':') ?: "") as? MutableList<MediaLibraryItem> ?: mutableListOf()
                }
                loading.postValue(false)
            }
            url == "root" -> launch { browseRootImpl() }
            else -> super.browse(url)
        }
    }

    suspend fun browseByUrl(url: String): List<MediaWrapper> {
        return when {
            url == "otg://" || url.startsWith("content:") -> {
                val result = ArrayList<MediaWrapper>()
                launch {
                    val files = withContext(coroutineContextProvider.IO) {
                        @Suppress("UNCHECKED_CAST")
                        getDocumentFiles(context, url.toUri().path?.substringAfterLast(':')
                                ?: "") as? MutableList<MediaLibraryItem> ?: mutableListOf()
                    }.map { it as MediaWrapper }

                    result.addAll(files.filter { it.itemType == MediaWrapper.TYPE_MEDIA })
                    files.filter { it.itemType == MediaWrapper.TYPE_DIR }.forEach {
                        result.addAll(browseByUrl(it.uri.toString()))
                    }
                }
                result.toList()
            }

            else -> super.browseUrl(url).toList().map { it as MediaWrapper }
        }
    }

    override fun release() {
        if (url == null) {
            ExternalMonitor.devices.removeObserver(this)
            LaserConnections.removeReleaseListener(usbReleaseListener)
            if (this::storageObserver.isInitialized) {
                StoragePermissionsDelegate.storageAccessGranted.removeObserver(storageObserver)
            }
        }
        usbDiscProvider?.let {
            it.stop()
            it.release()
        }
        usbDiscProvider = null
        super.release()
    }

    override fun onChanged(list: MutableList<UsbDevice>) {
        // OTG entry: shown whenever any USB device is present.
        if (list.isNullOrEmpty()) {
            if (otgPosition != -1) removeOtgEntry()
        } else if (otgPosition == -1) {
            addOtgEntry()
        }

        // Laser groups: one per attached drive holding a recognised disc.
        updateLaserEntry()
    }

    /* ---------------------------------------------------------------------
     * Synthetic entries: the OTG row, at most one, and the laser groups, one
     * per drive.
     *
     * otgPosition is a CACHED index, set by browseRootImpl() with the list it
     * indexes and reset whenever that list is replaced. Laser groups are
     * always inserted after it, so they never shift it.
     *
     * A laser group's index is DERIVED from the list at each use, so that a
     * wholesale replacement cannot leave it pointing into nothing, and so
     * that groups need no bookkeeping about each other.
     * ------------------------------------------------------------------- */

    private fun addOtgEntry() {
        val otg = MLServiceLocator.getAbstractMediaWrapper("otg://".toUri()).apply {
            title = context.getString(R.string.otg_device_title)
            type = MediaWrapper.TYPE_DIR
        }
        otgPosition = (storagePosition + 1).coerceIn(0, dataset.getList().size)
        dataset.add(otgPosition, otg)
    }

    private fun removeOtgEntry() {
        if (otgPosition < 0 || otgPosition >= dataset.getList().size) {
            otgPosition = -1
            return
        }
        dataset.remove(otgPosition)
        otgPosition = -1
    }

    /**
     * Where [deviceName]'s group belongs in [dataset]: after storage and the
     * OTG row, among the other groups by deviceName - not by classification
     * order, which depends on which permission dialog is answered first.
     */
    private fun laserInsertPosition(deviceName: String): Int {
        val base = when {
            otgPosition != -1 -> otgPosition + 1
            else -> storagePosition + 1
        }
        /* Step over the displayed groups that sort before this one. */
        var offset = 0
        for (name in shownLaserDeviceNames().sorted()) {
            if (name >= deviceName) break
            offset += laserTiles[name]?.size ?: 0
        }
        /* Clamped: the cached base can point past the end of a list that
         * shrank, and dataset.add() would throw. */
        return (base + offset).coerceIn(0, dataset.getList().size)
    }

    private fun addLaserEntry(discs: List<LaserDiscInfo>) {
        if (discs.isEmpty()) return
        /* Every row of a group describes the same drive. */
        val deviceName = discs.first().deviceName

        val tiles = discs.map { disc ->
            MLServiceLocator.getAbstractMediaWrapper(
                laserTileLocation(disc.mrl, disc.deviceName, disc.title).toUri()
            ).apply {
                /* The "(No menus)" suffix is added here because Android
                 * resources are the only localisation this application has:
                 * VLC is built with --disable-nls. */
                title = if (isLaserNoMenuMrl(disc.mrl))
                    context.getString(R.string.laser_no_disc_menus, disc.title)
                else disc.title
                type = MediaWrapper.TYPE_DIR
            }
        }

        val insertAt = laserInsertPosition(deviceName)
        /* Contiguous, in the order laser.c listed them. */
        tiles.forEachIndexed { offset, tile -> dataset.add(insertAt + offset, tile) }
        laserTiles[deviceName] = tiles
    }

    /**
     * Puts the laser groups back after browseRootImpl() has replaced the
     * list, at the positions it has just recomputed.
     *
     * browseRootImpl() suspends between its first line and that replacement,
     * and runs twice per entry to the screen: a classification finishing in
     * between adds its group to a list about to be discarded. The drive is
     * then not classified again, being already seen, so its tile would be
     * lost until the next forced pass.
     *
     * In deviceName order, so that each group lands after those sorting
     * before it, as [laserInsertPosition] expects.
     */
    private fun restoreLaserGroups() {
        if (laserTiles.isEmpty()) return
        val groups = laserTiles.toSortedMap()
        laserTiles.clear()
        for ((deviceName, tiles) in groups) {
            val insertAt = laserInsertPosition(deviceName)
            tiles.forEachIndexed { offset, tile -> dataset.add(insertAt + offset, tile) }
            laserTiles[deviceName] = tiles
        }
    }

    private fun removeLaserEntry(deviceName: String) {
        val tiles = laserTiles[deviceName] ?: return
        if (laserTileIndex(deviceName) == -1) {
            // Already gone with a wholesale replacement of the list.
            return
        }
        /* One row at a time, by identity: each removal shifts the rest. */
        for (item in tiles) {
            val index = dataset.getList().indexOf(item)
            if (index == -1) continue
            dataset.remove(index)
        }
        laserTiles.remove(deviceName)
    }

    /**
     * Reconciles the laser groups against the attached devices: removes the
     * groups of detached drives, queues the candidates that are new since the
     * last pass, and drains the queue. Removal never prompts; only a new
     * candidate can.
     */
    private fun updateLaserEntry() = laserActor.trySend(Unit)

    /**
     * Coalesces the burst of [updateLaserEntry] calls one entry to the screen
     * produces - observeForever()'s first dispatch and onResume() ->
     * refreshLaser(), 66ms apart, and ExternalMonitor's clear and refill on
     * every background round trip - so that it costs one pass rather than a
     * USB open and a SCSI exchange per drive per trigger. refreshLaser()
     * clears lastLaserCandidateNames, so that guard alone cannot absorb them.
     *
     * CONFLATED: a trigger only says "look again", the reconciliation reading
     * the attached devices itself.
     *
     * Runs on the provider's Main.immediate scope, which the body needs, and
     * stops with it.
     */
    @OptIn(ObsoleteCoroutinesApi::class)
    private val laserActor = actor<Unit>(capacity = Channel.CONFLATED) {
        for (trigger in channel) {
            delay(LASER_COALESCE_MS)
            /* A trigger that came during the delay is covered by this pass. */
            channel.tryReceive()
            updateLaserEntryImpl()
        }
    }

    private fun updateLaserEntryImpl() {
        /* The only place the devices are logged: this pass is where "the drive
         * is not detected" gets diagnosed. */
        val candidates = findUsbOpticalDriveCandidates(context, log = true)
        val currentNames = candidates.map { it.deviceName }.toSet()

        /* No broadcast tells when the kernel is done: watch for it. */
        if (isUsbStorageProbing() && laserProbeRetry?.isActive != true)
            laserProbeRetry = launch {
                awaitUsbStorageProbe(context)
                updateLaserEntry()
            }

        /* Drives whose group is displayed but which are no longer attached. */
        for (deviceName in shownLaserDeviceNames()) {
            if (deviceName !in currentNames) removeLaserEntry(deviceName)
        }

        /* Only candidates NEW since the last pass may trigger a
         * (permission-prompting) classification; a detach yields none.
         * Detached names are forgotten, so that a replug is new again. New
         * names are recorded where they are queued, not here, so that none is
         * consumed without being looked at. */
        lastLaserCandidateNames = lastLaserCandidateNames intersect currentNames
        /* A refused drive is not asked again by this pass. */
        forgetLaserPermissionDenials(keep = currentNames)
        val shown = shownLaserDeviceNames()
        val queued = currentNames
            .filter {
                it !in lastLaserCandidateNames && it !in shown &&
                        it !in laserQueue && it != laserInFlight &&
                        !isLaserPermissionDenied(it)
            }
            .sorted()
        if (queued.isEmpty()) return

        laserQueue.addAll(queued)
        lastLaserCandidateNames = lastLaserCandidateNames + queued
        drainLaserQueue()
    }

    /**
     * Classifies queued drives one at a time until the queue is empty.
     *
     * Serial, so that two permission dialogs never stack, and so that a
     * candidate's connection is closed before the next is opened on a combo
     * drive sharing one bridge. [laserDraining] keeps the queue to one
     * consumer; drives queued meanwhile are picked up on the next turn.
     *
     * Terminates: every turn removes a name, and a name is recorded in
     * [lastLaserCandidateNames] as it is queued, so it is not queued again.
     */
    private fun drainLaserQueue() {
        if (laserDraining) return
        laserDraining = true

        /* Built here, on Main: LaserDiscProvider's constructor calls
         * observeForever, which throws on a background thread. */
        val provider = getUsbDiscProvider()

        launch {
            try {
                while (true) {
                    val deviceName = laserQueue.firstOrNull() ?: break
                    laserQueue.remove(deviceName)
                    /* For the whole turn - see laserInFlight. */
                    laserInFlight = deviceName

                    /* Re-resolved at each turn: the drive may have been
                     * unplugged while queued. */
                    val device = findUsbOpticalDriveByName(context, deviceName) ?: continue

                    val discs: List<LaserDiscInfo> = try {
                        withContext(coroutineContextProvider.IO) {
                            classifyOpticalDisc(context, device, provider)
                        }
                    } catch (e: Exception) {
                        Log.e(LASER_TAG, "classification threw for $deviceName", e)
                        emptyList()
                    }

                    /* Only if the drive is still attached and has no group
                     * yet: either may have changed during the classification. */
                    if (discs.isNotEmpty() && laserTileIndex(deviceName) == -1 &&
                        findUsbOpticalDriveByName(context, deviceName) != null)
                        addLaserEntry(discs)

                    laserInFlight = null
                }
            } finally {
                laserInFlight = null
                laserDraining = false
            }
        }
    }
}
