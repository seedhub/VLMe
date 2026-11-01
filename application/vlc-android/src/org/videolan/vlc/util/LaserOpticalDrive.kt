/*****************************************************************************
 * LaserOpticalDrive.kt
 *****************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
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
 *****************************************************************************
 *
 * USB-host side of the laser optical-drive support: finding candidate
 * drives, asking permission, opening and closing their connections, the MRL
 * and tile-location helpers built around the descriptor, and the environment
 * the native libraries read.
 *
 * Classifying the disc - browsing "laser-dir://<fd>" through VLC's laser
 * access module - is LaserDiscProvider's job. The descriptor is the liblaser
 * token: nothing is registered from here, and every native module releases
 * the token in its own Close().
 *****************************************************************************/

package org.videolan.vlc.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.util.parcelable
import org.videolan.tools.KEY_HARDWARE_ACCELERATION
import org.videolan.tools.Settings
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.providers.LaserDiscProvider
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume

internal const val LASER_TAG = "VLC/LaserDrive"
private const val ACTION_USB_PERMISSION = "org.videolan.vlc.laser.USB_PERMISSION"

/** How many reconciliations a just-opened playback connection is spared - see
 * LaserConnections.pendingConnections. */
private const val LASER_PENDING_GRACE = 2

/** The package whose APK carries libaacs - see [configureLaserEnvironment].
 * VLC's manifest must name it in a <queries> element, or getApplicationInfo()
 * reports it missing on API 30 and later even when it is installed. */
private const val LIBAACS_PACKAGE = "org.libaacs.provider"

/** A USB Mass Storage interface speaking Bulk-Only Transport: the only
 * transport liblaser implements. Matched on class and protocol only, as
 * liblaser's usb.c does - the subclass names a command set (SCSI
 * transparent, ATAPI, SFF-8070i...), all of which an optical drive speaks as
 * SCSI-MMC, and USB keys and card readers declare the same values anyway.
 * Whether the device is an optical drive is answered natively, by INQUIRY. */
private const val USB_CLASS_MASS_STORAGE = 0x08
private const val USB_PROTOCOL_BULK_ONLY = 0x50

/**
 * Result of classifying a disc via the laser access module.
 *
 * @param deviceName the [UsbDevice.getDeviceName] of the drive - the
 *                   "/dev/bus/usb/BBB/DDD" path - so that a tap reopens THIS
 *                   drive rather than the first candidate of a fresh scan,
 *                   which differs with two drives attached. Stable for as
 *                   long as the device stays attached.
 * @param mrl the playback MRL exactly as laser.c's DirRead() emitted it:
 *            "laser/dvd://<fd>", "laser/dvdsimple://<fd>",
 *            "laser/dvda://<fd>" or "laser/bluray://<fd>" in VLC's
 *            "[access][/demux]://location" form, or "cdda://laser/<fd>",
 *            "vcd://laser/<fd>" or "svcd://laser/<fd>" for the discs read
 *            through cdrom.c. Kept whole: "laser/dvd" is not a scheme.
 *
 *            THE FD IN IT IS STALE: the classification connection is closed
 *            as soon as the browse ends. Never play this string as-is; pass
 *            it through [laserMrlWithFd] with a fresh connection.
 * @param title display name for the disc, never empty: DirRead() falls back
 *              to a generic label when the disc has no volume name, which
 *              an audio CD never has.
 */
data class LaserDiscInfo(val deviceName: String, val mrl: String, val title: String)

/* ============================================================================
 * Tile location <-> (mrl, deviceName, title) encoding.
 *
 * A tile's location carries what a tap needs: the MRL classification
 * produced, the drive's deviceName and the disc title, as percent-encoded
 * query parameters - the MRL contains "://" and has no other place in a URI.
 * It is never handed to native code.
 *
 * The scheme is not "laser", which is a real access module's shortcut: a
 * tile location leaked into openMedia() must not look like something libVLC
 * could open.
 * ========================================================================== */

const val LASER_TILE_SCHEME = "lasertile"
private const val LASER_TILE_DEV_QUERY = "dev"
private const val LASER_TILE_MRL_QUERY = "mrl"
private const val LASER_TILE_TITLE_QUERY = "title"

/** What a tile location carries. [title] is the disc's, without the
 *  "(No menus)" suffix the tile label may add. */
data class LaserTile(val mrl: String, val deviceName: String, val title: String)

/**
 * Builds the tile location, e.g. "lasertile://disc?mrl=laser%2Fdvd%3A%2F%2F186
 * &dev=%2Fdev%2Fbus%2Fusb%2F001%2F004&title=MY_MOVIE".
 */
fun laserTileLocation(mrl: String, deviceName: String, title: String): String =
    Uri.Builder()
        .scheme(LASER_TILE_SCHEME)
        .authority("disc")
        .appendQueryParameter(LASER_TILE_MRL_QUERY, mrl)
        .appendQueryParameter(LASER_TILE_DEV_QUERY, deviceName)
        .appendQueryParameter(LASER_TILE_TITLE_QUERY, title)
        .build()
        .toString()

/** True if [location] is one of our synthetic laser tiles. */
fun isLaserTileLocation(location: String?): Boolean =
    location != null && location.toUri().scheme == LASER_TILE_SCHEME

/**
 * Parses a tile location built by [laserTileLocation], or returns null if
 * [location] is not one of ours or is malformed. A missing title is empty.
 */
fun parseLaserTileLocation(location: String?): LaserTile? {
    val uri = location?.toUri() ?: return null
    if (uri.scheme != LASER_TILE_SCHEME) return null
    val mrl = uri.getQueryParameter(LASER_TILE_MRL_QUERY) ?: return null
    val deviceName = uri.getQueryParameter(LASER_TILE_DEV_QUERY) ?: return null
    val title = uri.getQueryParameter(LASER_TILE_TITLE_QUERY) ?: ""
    return LaserTile(mrl, deviceName, title)
}

/**
 * Returns [mrl] with its trailing fd replaced by [fd], or null if [mrl]
 * does not end in one.
 *
 * Every disc MRL laser.c emits ends with the fd - "laser/dvd://186",
 * "cdda://laser/186" - so the substitution needs to know neither shape.
 * Disc MRLs only: a CD-Audio track MRL made later by cdda.c,
 * "cdda://laser/6/Track%2003", ends with other digits, and never comes this
 * way ([laserFdOf] handles those).
 */
fun laserMrlWithFd(mrl: String, fd: Int): String? {
    val prefix = mrl.dropLastWhile { it.isDigit() }
    if (prefix.length == mrl.length) return null // no trailing fd at all
    return "$prefix$fd"
}

/** The access module's name, which is what every location below is recognised by. */
private const val LASER_ACCESS = "laser"

/**
 * Does [location] name a USB optical drive - a playback MRL laser.c emits,
 * or one of our tiles?
 *
 * Such a media must never enter a persistent store: it names a descriptor,
 * or a device path, meaningful for one connection in one process. See the
 * call sites in PlaylistManager.
 *
 * Matched on the whole location, since none of these shapes has a usable
 * scheme:
 *
 *     laser/<demux>://<fd>   "laser/dvd" is not a scheme
 *     cdda://laser/<fd>      cdda, vcd and svcd are shared with every other
 *     vcd://laser/<fd>       source of the same medium; the drive is the
 *     svcd://laser/<fd>      authority, which the last test matches
 *     lasertile://disc?…     our tile
 *
 * Allocation-free: this runs on every pause, track change and ten seconds of
 * playback.
 */
fun isLaserLocation(location: String?): Boolean {
    if (location == null) return false

    return location.startsWith("$LASER_ACCESS/") ||        // laser/<demux>://<fd>
            location.startsWith("$LASER_TILE_SCHEME:") ||  // lasertile://disc?…
            location.contains("://$LASER_ACCESS/")         // cdda://laser/<fd>
}

/**
 * Which kind of disc an MRL names. Derived from the MRL, which already
 * encodes it, rather than carried as a field of its own.
 */
enum class LaserDiscType { CD_AUDIO, DVD_VIDEO, DVD_AUDIO, BLURAY, VIDEO_CD }

/**
 * Disc type of a playback MRL, or null if [mrl] is not one of ours.
 *
 * One arm per MRL shape, spelled out to the "://": "laser/dvdsimple://"
 * does not start with "laser/dvd:", and a looser prefix once left the
 * no-menus rows without an icon and without [applyLaserPlaybackFlags].
 *
 * DVD_AUDIO is kept apart from DVD_VIDEO because the icon is what tells a
 * universal disc's two rows apart. VCD and SVCD share VIDEO_CD, as dvd and
 * dvdsimple share DVD_VIDEO: nothing acts on the difference, and the MRL
 * still carries it (see [isLaserNoMenuMrl]).
 */
fun laserDiscTypeOfMrl(mrl: String?): LaserDiscType? = when {
    mrl == null -> null
    mrl.startsWith("cdda://$LASER_ACCESS/")        -> LaserDiscType.CD_AUDIO
    mrl.startsWith("$LASER_ACCESS/dvd://")         -> LaserDiscType.DVD_VIDEO
    mrl.startsWith("$LASER_ACCESS/dvdsimple://")   -> LaserDiscType.DVD_VIDEO
    mrl.startsWith("$LASER_ACCESS/dvda://")        -> LaserDiscType.DVD_AUDIO
    mrl.startsWith("$LASER_ACCESS/bluray://")      -> LaserDiscType.BLURAY
    mrl.startsWith("vcd://$LASER_ACCESS/")         -> LaserDiscType.VIDEO_CD
    mrl.startsWith("svcd://$LASER_ACCESS/")        -> LaserDiscType.VIDEO_CD
    else -> null
}

/**
 * Is [mrl] the no-menus way of playing a DVD, as opposed to the dvdnav one?
 * Both are [LaserDiscType.DVD_VIDEO]; this tells the two rows apart.
 */
fun isLaserNoMenuMrl(mrl: String?): Boolean =
    mrl != null && mrl.startsWith("$LASER_ACCESS/dvdsimple://")

/**
 * Disc type of a TILE location, or null if [location] is not one of our tiles.
 *
 * Called for every bound row of every browser screen, so anything that is not
 * a tile is rejected on a string comparison before any Uri parse.
 */
fun laserTileDiscType(location: String?): LaserDiscType? {
    if (location == null || !location.startsWith("$LASER_TILE_SCHEME:")) return null
    return laserDiscTypeOfMrl(parseLaserTileLocation(location)?.mrl)
}

/**
 * Is [mrl] a video disc? Such a media is typed TYPE_VIDEO before playback, so
 * that PlaylistManager opens the video player first and starts playback once
 * its surface exists. Left untyped, playback starts in the service and the
 * first pictures reach a video output with no window: VLC then falls back to
 * software decoding, and the hardware decoder it had opened stays allocated
 * for the life of the process.
 */
fun isLaserVideoMrl(mrl: String?): Boolean = when (laserDiscTypeOfMrl(mrl)) {
    LaserDiscType.DVD_VIDEO, LaserDiscType.BLURAY, LaserDiscType.VIDEO_CD -> true
    else -> false
}

/**
 * Is [mrl] a disc played whole, titled by the tile rather than by cdda.c?
 * A CD track's title comes from cdda.c, and may be CD-Text.
 */
fun isLaserWholeDiscMrl(mrl: String?): Boolean {
    val type = laserDiscTypeOfMrl(mrl)
    return type != null && type != LaserDiscType.CD_AUDIO
}

/**
 * Gives libVLC [mw]'s title, the tile's, as a fallback title meta. VLC applies
 * it before the demuxer's own, so a title dvdnav or libbluray reads from the
 * disc still wins; PlaylistManager then shows whichever libVLC reports.
 */
fun applyLaserTitleOption(media: IMedia, mw: MediaWrapper) {
    if (!isLaserWholeDiscMrl(mw.location)) return
    val title = mw.title
    if (title.isNullOrEmpty()) return
    media.addOption(":meta-title=$title")
}

/**
 * Value of KEY_HARDWARE_ACCELERATION meaning "Automatic" - restated because
 * VLCOptions keeps its own copy private.
 */
private const val HW_ACCELERATION_AUTOMATIC = -1

/**
 * Apply the playback flags a laser disc needs, before [mw] is handed to the
 * player.
 *
 * Older arm processor are often bad at hardware decoding a DVD's MPEG-2 stream.
 * To prevent a lot of user headaches (and bug reports), hardware decoding is
 * disabled if left to its "automatic" value by the user.
 */
fun applyLaserPlaybackFlags(context: Context, mw: MediaWrapper) {
    if (laserDiscTypeOfMrl(mw.location) != LaserDiscType.DVD_VIDEO) return

    val setting = try {
        Settings.getInstance(context)
            .getString(KEY_HARDWARE_ACCELERATION, "$HW_ACCELERATION_AUTOMATIC")!!
            .toInt()
    } catch (e: NumberFormatException) {
        HW_ACCELERATION_AUTOMATIC
    } catch (e: ClassCastException) {
        HW_ACCELERATION_AUTOMATIC
    }
    if (setting != HW_ACCELERATION_AUTOMATIC) return

    Log.d(LASER_TAG, "forcing software decoding for DVD playback (hardware " +
            "acceleration left on Automatic)")
    mw.addFlags(MediaWrapper.MEDIA_NO_HWACCEL)
}

/* ============================================================================
 * Environment of the native libraries
 * ========================================================================== */

/**
 * Sets the environment variables libdvdcss, libdvdread and libbluray read for
 * laser discs.
 *
 * Called once, from AppSetupDelegate.setupApplication(), before the LibVLC
 * instance exists: setenv(3) is not safe against a concurrent getenv(3), and
 * once libVLC runs, its threads may call getenv() at any time. Hence also
 * that a libaacs provider installed while VLC runs is only seen at the next
 * start.
 */
fun configureLaserEnvironment(context: Context) {
    configureLibdvdcss(context)
    configureLibaacs(context)
}

/**
 * Point libdvdcss at a key cache, keep libdvdread from prefetching every
 * title key, and in debug builds make libdvdcss talk.
 *
 * DVDCSS_CACHE: libdvdcss's default under $HOME cannot be created here,
 * and without a cache every playback of a disc pays its key retrieval
 * again - nine seconds on the drives seen so far.
 *
 * DVDREAD_NOKEYS: skips libdvdread's initAllCSSKeys(), which opens every
 * VOB at startup - nine seconds on a 7-VTS disc, nearly all of it seeking
 * to each VOB. The keys are then asked for on demand, each answered in
 * milliseconds since this drive authenticates. The libdvdcss README's
 * warning about undetected key changes concerns its "title" method, which
 * guesses keys and is not used here. If a hiccup ever shows at a VOB
 * boundary - "input: Buffering" mid-title - the answer is --disc-caching.
 *
 * DVDCSS_VERBOSE=1, errors only, in debug builds only.
 */
private fun configureLibdvdcss(context: Context) {
    val cache = File(context.cacheDir, "dvdcss")
    if (cache.isDirectory || cache.mkdirs()) {
        Os.setenv("DVDCSS_CACHE", cache.absolutePath, true)
        Log.d(LASER_TAG, "libdvdcss key cache at ${cache.absolutePath}")
    } else {
        /* Not fatal: CSS keys are then retrieved on every playback. */
        Log.w(LASER_TAG, "could not create ${cache.absolutePath}, " +
                "libdvdcss will retrieve CSS keys on every playback")
    }

    Os.setenv("DVDREAD_NOKEYS", "1", true)

    if (BuildConfig.DEBUG)
        Os.setenv("DVDCSS_VERBOSE", "1", true)
}

/**
 * Point libbluray at the libaacs carried by the [LIBAACS_PACKAGE] APK, if
 * that package is installed.
 *
 * libbluray dlopen()s libaacs, which cannot ship inside VLC, and an
 * unrooted device has no writable directory the linker will load from. A
 * second package can carry it, and the linker maps it straight out of that
 * APK - nothing is copied or extracted:
 *
 *     /data/app/~~<r>/org.libaacs.provider-<r>/base.apk!/lib/arm64-v8a/libaacs
 *
 * The "!/" form works since API 23 for any zip, provided the entry is
 * stored uncompressed and page-aligned, which the provider APK ensures.
 *
 * The ABI directory is VLC's own: the provider has no primary ABI, its
 * entries not ending in ".so", so the bitness of THIS process decides -
 * which [Build.SUPPORTED_ABIS] would get wrong for a 32-bit VLC on a 64-bit
 * device.
 *
 * The value stops at "libaacs": libbluray appends ".so.0" itself.
 *
 * Without the provider, or with nothing in it for this ABI, LIBAACS_PATH is
 * left unset and AACS discs do not play.
 */
private fun configureLibaacs(context: Context) {
    val apk = try {
        /* Needs <queries> naming the package in VLC's manifest, or from
         * API 30 it is reported missing. */
        context.packageManager.getApplicationInfo(LIBAACS_PACKAGE, 0).sourceDir
    } catch (e: PackageManager.NameNotFoundException) {
        Log.d(LASER_TAG, "$LIBAACS_PACKAGE not installed, AACS discs will not play")
        return
    }

    val abi = File(context.applicationInfo.nativeLibraryDir).name.let {
        when (it) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> {
                Log.w(LASER_TAG, "unknown native library directory \"$it\", " +
                        "cannot pick a libaacs for this ABI")
                return
            }
        }
    }

    val path = "$apk!/lib/$abi/libaacs"
    Os.setenv("LIBAACS_PATH", path, true)
    Log.d(LASER_TAG, "libaacs at $path")
    Os.setenv("XDG_CACHE_HOME", context.cacheDir.absolutePath, true)
}

/* ============================================================================
 * Ownership of the PLAYBACK connection.
 *
 * A classification connection is opened and closed within one call,
 * classifyOpticalDisc(). A playback connection is opened by a fragment and
 * used by a service that outlives it, so nobody is left holding it when it
 * should close. This object holds it.
 *
 * RECONCILED, NOT PAIRED. Closing on "playback ended" would leak on every
 * exit path not wired - playback that never starts, a demuxer rejecting the
 * disc, an error, the app swiped away. Instead the playlist is the source of
 * truth, and [retainOnly] closes whatever it no longer names: idempotent, and
 * unable to leak past the next reconciliation.
 *
 * WHEN IT IS SAFE TO CLOSE: every native module releases its token in its own
 * Close(), so reconciliation is driven from events libVLC emits once that is
 * done - MediaPlayer.Event.Stopped, and the service's teardown - never from a
 * decision taken in Kotlin. See the call sites in PlaylistManager.
 *
 * THREADING: synchronized on the object; it is touched from the main thread
 * and from libVLC's event thread.
 * ============================================================================ */
object LaserConnections {

    /** deviceName -> the connection opened for playback on that drive. */
    private val connections = HashMap<String, UsbDeviceConnection>()

    /* deviceNames whose connection has been opened but not yet seen in a
     * playlist, and how many reconciliations they are still spared.
     *
     * A connection never named YET looks like one whose entry has gone. With
     * drive A playing, a tap on drive B's tile opens B's connection, and A's
     * Stopped event then fires against A's playlist - which would close B's
     * before its media is ever loaded. So a fresh connection is spared until
     * a reconciliation names it, or until LASER_PENDING_GRACE of them have
     * passed: a count of events rather than a delay, and bounded, so that a
     * media that never loads does not keep the drive forever. */
    private val pendingConnections = HashMap<String, Int>()

    /* Connections replaced by a newer one on the same drive - see [open] -
     * as (deviceName, connection). Its input may still be running, so it is
     * closed only once no playlist names it, or by [closeAll] or a detach. */
    private val retiredConnections = ArrayList<Pair<String, UsbDeviceConnection>>()

    /** Set once the detach receiver is registered; it is never unregistered. */
    private var detachReceiverRegistered = false

    /**
     * Drop the cached connection of a drive that has left the bus.
     *
     * The map is keyed by deviceName - "/dev/bus/usb/001/004" - and that path
     * is reassigned: a replugged drive can land on the same one, and [open]
     * would hand back a connection to a device that has gone, failing natively
     * at libusb_wrap_sys_device(). [retainOnly] does not cover this, being
     * driven by the playlist rather than by the bus.
     *
     * Registered on the application context and never unregistered, as befits
     * an object with no lifecycle.
     */
    private fun ensureDetachReceiver(context: Context) {
        if (detachReceiverRegistered) return

        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device = intent.parcelable<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                dropDetached(device.deviceName)
            }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        /* After the registration, so that a throw leaves the next open() to
         * try again. */
        detachReceiverRegistered = true
        Log.d(LASER_TAG, "watching for USB detach")
    }

    /**
     * Closes and forgets the connection cached under [deviceName], if any.
     *
     * Notifies nobody, unlike [retainOnly] and [closeAll]: the release signal
     * means "re-classify this drive", and a drive that has left the bus can
     * only fail that.
     */
    @Synchronized
    private fun dropDetached(deviceName: String) {
        retiredConnections.removeAll { (name, connection) ->
            (name == deviceName).also { if (it) connection.close() }
        }
        val connection = connections.remove(deviceName) ?: return
        pendingConnections.remove(deviceName)
        Log.d(LASER_TAG, "dropping connection for $deviceName (fd=${connection.fileDescriptor}): device detached")
        connection.close()
    }

    /**
     * Opens a new playback connection to [device].
     *
     * ALWAYS A NEW ONE, even when one is already open for the drive: the
     * descriptor is part of every MRL of the disc, and a disc swapped while
     * paused would otherwise get the very MRLs of the previous one -
     * "cdda://laser/6/Track%2001" for both - which the playlist, matching
     * media by URI, then takes for the old tracks. The previous connection is
     * retired rather than closed: its input is only destroyed when the new
     * media loads, which then claims the interface the old one released.
     *
     * The returned connection is owned by this object. Callers read its
     * descriptor and MUST NOT close it.
     */
    @Synchronized
    fun open(context: Context, device: UsbDevice): UsbDeviceConnection? {
        ensureDetachReceiver(context)

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(device) ?: run {
            Log.w(LASER_TAG, "could not open playback connection for ${device.deviceName}")
            return null
        }
        connections.put(device.deviceName, connection)?.let { previous ->
            Log.d(LASER_TAG, "retiring playback connection for ${device.deviceName} (fd=${previous.fileDescriptor})")
            retiredConnections.add(device.deviceName to previous)
        }
        /* Spared by the next reconciliations - see pendingConnections. */
        pendingConnections[device.deviceName] = LASER_PENDING_GRACE
        Log.d(LASER_TAG, "opened playback connection for ${device.deviceName} (fd=${connection.fileDescriptor})")
        return connection
    }

    /**
     * The newest playback connection held for [device], or null.
     *
     * For classification, which must not open a SECOND connection to a drive
     * playback holds: a second descriptor is a second device to liblaser, and
     * its interface claim fails against the first. A paused playback still
     * holds its connection, and a paused drive is one whose disc can be
     * swapped.
     *
     * The returned connection is owned by this object. Read its descriptor,
     * do NOT close it.
     */
    @Synchronized
    fun heldForPlayback(device: UsbDevice): UsbDeviceConnection? =
        connections[device.deviceName]

    /**
     * Closes every playback connection whose descriptor is no longer named by
     * any of [locations].
     *
     * [locations] must be the WHOLE playlist, not the current media: an audio
     * CD is one item per track, all on the same drive.
     */
    fun retainOnly(locations: Collection<String>) {
        val released = retainOnlyLocked(locations)
        if (released.isEmpty()) return
        notifyReleased(released)
    }

    /** @return the deviceNames whose connection was closed. */
    @Synchronized
    private fun retainOnlyLocked(locations: Collection<String>): List<String> {
        if (connections.isEmpty() && retiredConnections.isEmpty()) return emptyList()

        val released = ArrayList<String>()
        val liveFds = locations.mapNotNullTo(HashSet()) { laserFdOf(it) }

        /* A retired connection has no grace: the playlist that named it is
         * being, or has been, replaced. Not reported as released - its drive
         * is held by the connection that replaced it. */
        retiredConnections.removeAll { (name, connection) ->
            val fd = connection.fileDescriptor
            (fd !in liveFds).also {
                if (it) {
                    Log.d(LASER_TAG, "closing retired playback connection for $name (fd=$fd)")
                    connection.close()
                }
            }
        }

        val it = connections.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val fd = entry.value.fileDescriptor

            if (fd in liveFds) {
                /* Named: judged on the playlist from now on. */
                pendingConnections.remove(entry.key)
                continue
            }

            val grace = pendingConnections[entry.key]
            if (grace != null) {
                /* Opened but not yet named - see pendingConnections. */
                if (grace > 1) {
                    pendingConnections[entry.key] = grace - 1
                    Log.d(LASER_TAG, "sparing just-opened connection for ${entry.key} (fd=$fd), $grace reconciliation(s) left")
                    continue
                }
                /* Out of grace: the media that would have named it never
                 * loaded. */
                pendingConnections.remove(entry.key)
                Log.d(LASER_TAG, "closing playback connection for ${entry.key} (fd=$fd), never named by any playlist")
            } else {
                Log.d(LASER_TAG, "closing playback connection for ${entry.key} (fd=$fd), no longer in the playlist")
            }

            entry.value.close()
            it.remove()
            released.add(entry.key)
        }
        return released
    }

    /**
     * The playback of [location] failed - on a laser MRL, most often because
     * the disc was taken out or swapped since its tile was made. The drive's
     * connection loses what is left of its grace, so that the reconciliation
     * of the Stopped event to come closes it if no playlist names it, and the
     * release listeners re-classify the drive. Does nothing for another MRL.
     */
    @Synchronized
    fun playbackFailed(location: String) {
        val fd = laserFdOf(location) ?: return
        val deviceName = connections.entries
            .firstOrNull { it.value.fileDescriptor == fd }?.key ?: return
        if (pendingConnections.remove(deviceName) != null)
            Log.d(LASER_TAG, "playback failed on $deviceName (fd=$fd), connection no longer spared")
    }

    /** Closes everything, for the service's own teardown. */
    fun closeAll() {
        val released = closeAllLocked()
        if (released.isEmpty()) return
        notifyReleased(released)
    }

    /** @return the deviceNames whose connection was closed. */
    @Synchronized
    private fun closeAllLocked(): List<String> {
        for ((_, connection) in retiredConnections) connection.close()
        retiredConnections.clear()
        if (connections.isEmpty()) return emptyList()
        for ((name, connection) in connections) {
            Log.d(LASER_TAG, "closing playback connection for $name (fd=${connection.fileDescriptor}) at teardown")
            connection.close()
        }
        val released = ArrayList(connections.keys)
        connections.clear()
        pendingConnections.clear()
        return released
    }

    /* ---- "a drive is free again" ----------------------------------------
     *
     * Listeners are told the deviceNames whose playback connection has been
     * CLOSED, so that they can re-classify those drives - a disc may have
     * been removed or swapped. Fired from the close rather than from a
     * playback error: re-classifying needs the interface, which the playback
     * connection holds until then - hence playbackFailed(), which only
     * hastens that close. Ordinary ends of playback fire it too.
     */
    private val releaseListeners =
        CopyOnWriteArraySet<(Collection<String>) -> Unit>()

    fun addReleaseListener(listener: (Collection<String>) -> Unit) {
        releaseListeners.add(listener)
    }

    fun removeReleaseListener(listener: (Collection<String>) -> Unit) {
        releaseListeners.remove(listener)
    }

    /* Called without the object's lock: a listener re-classifies, which ends
     * in open() on this same object. */
    private fun notifyReleased(deviceNames: Collection<String>) {
        for (listener in releaseListeners) {
            try {
                listener(deviceNames)
            } catch (e: Exception) {
                Log.w(LASER_TAG, "release listener threw", e)
            }
        }
    }
}

/**
 * The descriptor a laser MRL reads through, or null if [location] is not
 * one of ours.
 *
 * Read at its position, not from the end of the string: a CD-Audio track MRL,
 * "cdda://laser/6/Track%2003", ends with other digits. Two shapes:
 *
 *     laser/dvd://<fd>        after the "://"
 *     cdda://laser/<fd>…      after "://laser/", the drive being the
 *     vcd://laser/<fd>        authority, with anything after the fd
 *     svcd://laser/<fd>
 *
 * A tile location yields null: the descriptor in its mrl parameter is the
 * classification one, long closed.
 *
 * Allocation-free, like [isLaserLocation].
 */
private fun laserFdOf(location: String): Int? {
    val authority = "://$LASER_ACCESS/"
    val viaAuthority = location.indexOf(authority)
    if (viaAuthority >= 0) return fdAt(location, viaAuthority + authority.length)

    if (location.startsWith("$LASER_ACCESS/")) {
        val sep = location.indexOf("://")
        if (sep >= 0) return fdAt(location, sep + 3)
    }
    return null
}

/**
 * Reads the run of decimal digits starting at [from], or null if there is
 * none or if it is too long to be a descriptor.
 */
private fun fdAt(s: String, from: Int): Int? {
    var i = from
    var value = 0
    while (i < s.length && s[i].isDigit()) {
        value = value * 10 + (s[i] - '0')
        if (value > 1_000_000) return null
        i++
    }
    return if (i == from) null else value
}

private fun UsbDevice.isMassStorageCandidate(): Boolean =
    (0 until interfaceCount).any { i ->
        getInterface(i).let {
            it.interfaceClass == USB_CLASS_MASS_STORAGE &&
                    it.interfaceProtocol == USB_PROTOCOL_BULK_ONLY
        }
    }

/* ============================================================================
 * What the kernel already knows about a device
 *
 * Telling an optical drive from a USB key takes an INQUIRY, which liblaser can
 * only send after claiming the interface - detaching the kernel driver, which
 * makes Android unmount the key. But when usb-storage bound the device it sent
 * its own INQUIRY, and the answer is in sysfs, one "type" file per logical
 * unit: 5 for a CD/DVD/BD drive, 0 for a disk. Reading it needs no claim.
 * ========================================================================== */

private const val SCSI_TYPE_ROM = 5

/**
 * What sysfs says about a device. PROBING: usb-storage is bound but has not
 * scanned the logical units yet - it waits a second after attach (delay_use),
 * and again after liblaser gives the interface back.
 */
private enum class SysfsKind { OPTICAL, NOT_OPTICAL, PROBING, UNKNOWN }

private val SCSI_ADDRESS = Regex("""\d+:\d+:\d+:\d+""")

/* Drivers whose binding means the kernel will soon record the SCSI types.
 * Not usbfs, which is what a claim by liblaser binds. */
private val USB_STORAGE_DRIVERS = setOf("usb-storage", "uas")

/* How long a device may stay PROBING before INQUIRY decides after all, in
 * case the kernel never finishes. */
private const val PROBE_GRACE_MS = 5000L

/* How often [awaitUsbStorageProbe] re-reads a probing device. Not tied to
 * delay_use: the kernel's wait starts at attach, ours at the pass that first
 * saw the device, and its scan takes a while after the wait. */
private const val PROBE_POLL_MS = 250L

/* When each device was first seen PROBING, by deviceName. Forgotten once it
 * leaves that state, so that the next probe gets a full grace. */
private val probingSince = ConcurrentHashMap<String, Long>()

/**
 * Reads [device]'s state from sysfs: the SCSI types of its logical units, as
 * usb-storage recorded them, and whether usb-storage is bound at all. Neither
 * is there when sysfs is unreadable, or when liblaser holds the drive.
 *
 * The device is found by bus and device number, which its deviceName,
 * "/dev/bus/usb/BBB/DDD", carries.
 */
private fun sysfsKind(device: UsbDevice): SysfsKind {
    val parts = device.deviceName.split('/')
    val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return SysfsKind.UNKNOWN
    val dev = parts.lastOrNull()?.toIntOrNull() ?: return SysfsKind.UNKNOWN

    var types = emptyList<Int>()
    var storageBound = false
    try {
        val usbDir = File("/sys/bus/usb/devices").listFiles()?.firstOrNull { dir ->
            ':' !in dir.name &&
                File(dir, "busnum").readTextOrNull()?.trim()?.toIntOrNull() == bus &&
                File(dir, "devnum").readTextOrNull()?.trim()?.toIntOrNull() == dev
        }
        if (usbDir != null) {
            /* <device>/<device>:C.I/hostN/targetN:C:T/N:C:T:L/type, walked
             * by name only: sysfs is full of symlinks leading to other devices. */
            fun File.subdirs(match: (String) -> Boolean): List<File> =
                listFiles()?.filter { it.isDirectory && match(it.name) }.orEmpty()

            val interfaces = usbDir.subdirs { it.startsWith("${usbDir.name}:") }
            types = interfaces
                .flatMap { it.subdirs { name -> name.startsWith("host") } }
                .flatMap { it.subdirs { name -> name.startsWith("target") } }
                .flatMap { it.subdirs { name -> SCSI_ADDRESS.matches(name) } }
                .mapNotNull { File(it, "type").readTextOrNull()?.trim()?.toIntOrNull() }
            storageBound = interfaces.any {
                File(it, "driver").let { link ->
                    link.exists() && link.canonicalFile.name in USB_STORAGE_DRIVERS
                }
            }
        }
    } catch (e: SecurityException) {
        /* Unreadable: UNKNOWN, INQUIRY decides. */
    } catch (e: java.io.IOException) {
        /* From canonicalFile: likewise. */
    }

    if (types.isNotEmpty() || !storageBound) {
        probingSince.remove(device.deviceName)
        return when {
            SCSI_TYPE_ROM in types -> SysfsKind.OPTICAL
            types.isNotEmpty() -> SysfsKind.NOT_OPTICAL
            else -> SysfsKind.UNKNOWN
        }
    }
    val since = probingSince.getOrPut(device.deviceName) { SystemClock.elapsedRealtime() }
    return if (SystemClock.elapsedRealtime() - since < PROBE_GRACE_MS) SysfsKind.PROBING
    else SysfsKind.UNKNOWN
}

private fun File.readTextOrNull(): String? =
    try { readText() } catch (e: java.io.IOException) { null }

private fun SysfsKind.isCandidate(): Boolean =
    this == SysfsKind.OPTICAL || this == SysfsKind.UNKNOWN

/**
 * A Mass Storage / Bulk-Only device that sysfs shows to be an optical drive,
 * or cannot tell about. Then liblaser's INQUIRY decides, at the cost of a
 * permission prompt and of detaching the kernel driver for a moment - which
 * is why a device the kernel is still probing is not one yet.
 */
private fun UsbDevice.isOpticalDriveCandidate(): Boolean =
    isMassStorageCandidate() && sysfsKind(this).isCandidate()

/**
 * Whether a device was left out of the last candidates only because the
 * kernel is still probing it: the caller should look again shortly.
 */
fun isUsbStorageProbing(): Boolean = probingNames().isNotEmpty()

private fun probingNames(): List<String> {
    val now = SystemClock.elapsedRealtime()
    return probingSince.filterValues { now - it < PROBE_GRACE_MS }.keys.toList()
}

/**
 * Returns once a device the kernel was probing is identified, has run out of
 * grace or is gone - when a classification pass is worth running again. Only
 * those devices' sysfs is read, without logging, so it can poll often.
 */
suspend fun awaitUsbStorageProbe(context: Context): Unit = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    while (true) {
        val names = probingNames()
        if (names.isEmpty()) return@withContext
        delay(PROBE_POLL_MS)
        val devices = manager.deviceList
        if (names.any { name -> devices[name]?.let { sysfsKind(it) != SysfsKind.PROBING } ?: true })
            return@withContext
    }
}

/**
 * Logs every attached USB device and every interface of each, candidate or
 * not, so that "the drive is not detected" can be told from "the drive is not
 * enumerated", and a rejected device shows which descriptor field rejected
 * it.
 *
 * The alternate setting is logged too: a USB 3 drive often offers Bulk-Only
 * and UAS as two alternate settings of one interface, and liblaser only
 * inspects the first.
 *
 * Needs no permission: Android only gates opening a connection.
 */
private fun logUsbDevices(devices: Collection<UsbDevice>) {
    if (devices.isEmpty()) {
        Log.d(LASER_TAG, "usb scan: no device at all - if a drive is plugged in, it " +
                "is not being enumerated (OTG or power, not this code)")
        return
    }

    for (device in devices) {
        val massStorage = device.isMassStorageCandidate()
        val kind = if (massStorage) sysfsKind(device) else null
        Log.d(LASER_TAG, "usb scan: %04x:%04x %s, %d interface(s), candidate=%b%s".format(
            device.vendorId, device.productId, device.deviceName,
            device.interfaceCount, kind?.isCandidate() == true,
            when (kind) {
                null -> ""
                SysfsKind.OPTICAL -> " (sysfs: optical drive)"
                SysfsKind.NOT_OPTICAL -> " (sysfs: not an optical drive, left alone)"
                SysfsKind.PROBING -> " (sysfs: kernel still probing, looking again shortly)"
                SysfsKind.UNKNOWN -> " (sysfs: unknown, INQUIRY will tell)"
            }))

        for (i in 0 until device.interfaceCount) {
            device.getInterface(i).let {
                Log.d(LASER_TAG, "  iface %d alt %d: class=0x%02x subclass=0x%02x protocol=0x%02x, %d endpoint(s)".format(
                    it.id, it.alternateSetting, it.interfaceClass,
                    it.interfaceSubclass, it.interfaceProtocol,
                    it.endpointCount))
            }
        }
    }
}

/**
 * Lists the attached candidates worth offering to classification, as
 * UsbManager reports them - one binder call, needing no permission. Not from
 * ExternalMonitor, which empties its list when the process goes to the
 * background.
 *
 * A USB key, card reader or disk the kernel has identified is left out, and
 * so is any device it is still probing (see [isUsbStorageProbing]); one it
 * cannot tell about passes, and liblaser's INQUIRY then declines it.
 *
 * @param log log every device and interface first. For the classification
 *            pass only: the other callers look up one drive, several times
 *            per pass.
 */
fun findUsbOpticalDriveCandidates(context: Context, log: Boolean = false): List<UsbDevice> {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val devices = manager.deviceList.values
    probingSince.keys.retainAll(devices.map { it.deviceName }.toSet())
    if (log) logUsbDevices(devices)
    return devices.filter { it.isOpticalDriveCandidate() }
}

/**
 * Classifies ONE candidate and returns the rows its disc offers - empty when
 * it holds no recognised disc, or is not an optical drive at all. The caller
 * offers every candidate in turn.
 *
 * The browse is delegated to the caller-owned [provider]. The connection is
 * opened for it and closed right after: laser.c releases the token in its own
 * Close() when the browse ends. It is never reused for playback - a tap opens
 * a fresh one, and replaces the stale fd in the MRL through
 * [laserMrlWithFd].
 */
suspend fun classifyOpticalDisc(
    context: Context,
    device: UsbDevice,
    provider: LaserDiscProvider
): List<LaserDiscInfo> {
    /* A drive playback is holding is classified THROUGH that connection: a
     * second one would be a second device to liblaser, whose interface claim
     * fails against the first. Skipping the drive instead would miss a disc
     * swapped while paused. Classifying a drive that is actually playing may
     * cause a hiccup, which is the lesser problem. */
    LaserConnections.heldForPlayback(device)?.let { playback ->
        Log.d(LASER_TAG, "classifying ${device.deviceName} through the playback " +
                "connection (fd=${playback.fileDescriptor})")
        /* No finally: this connection belongs to LaserConnections. */
        return provider.classify(playback.fileDescriptor, device.deviceName)
    }

    val connection = requestUsbOpticalDriveAccess(context, device) ?: return emptyList()
    return try {
        provider.classify(connection.fileDescriptor, device.deviceName)
    } finally {
        connection.close()
    }
}

/**
 * Finds the currently attached candidate whose [UsbDevice.getDeviceName]
 * is [deviceName], or null if it is no longer attached - what a tap on a
 * tile uses, so that it opens the drive the tile describes.
 */
fun findUsbOpticalDriveByName(context: Context, deviceName: String): UsbDevice? =
    findUsbOpticalDriveCandidates(context).firstOrNull { it.deviceName == deviceName }

/**
 * Requests permission for [device] if needed, then opens a connection the
 * CALLER owns and must close - for classification, see [classifyOpticalDisc].
 * Playback uses [openUsbOpticalDrivePlayback] instead.
 *
 * @return an opened [UsbDeviceConnection], or null if permission was denied
 *         or the device could not be opened. liblaser never closes the fd.
 */
private suspend fun requestUsbOpticalDriveAccess(context: Context, device: UsbDevice): UsbDeviceConnection? {
    if (!requestUsbPermission(context, device)) return null

    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    return manager.openDevice(device) ?: run {
        Log.w(LASER_TAG, "Could not open USB connection for ${device.deviceName}")
        null
    }
}

/**
 * Opens a new PLAYBACK connection to [device], requesting
 * permission first if needed. Ownership stays with [LaserConnections],
 * since the fragment that starts playback is gone long before it ends.
 *
 * @return a connection owned by [LaserConnections] - read its descriptor,
 *         do NOT close it - or null if permission was denied or the device
 *         could not be opened.
 */
suspend fun openUsbOpticalDrivePlayback(context: Context, device: UsbDevice): UsbDeviceConnection? {
    if (!requestUsbPermission(context, device)) return null
    return LaserConnections.open(context, device)
}

/* deviceNames whose permission request the user refused. The automatic
 * classification pass skips them: the dialog's dismissal resumes the browser,
 * whose onResume() starts a pass, which would ask again at once, in a loop.
 * Forgotten when the drive is detached, or on an explicit refresh by the
 * user. Written from the IO dispatcher, read from Main. */
private val laserPermissionDenied: MutableSet<String> =
    Collections.synchronizedSet(HashSet())

/** True if the user refused permission for [deviceName] and has not asked
 *  again since. */
fun isLaserPermissionDenied(deviceName: String): Boolean =
    deviceName in laserPermissionDenied

/** Forgets the refusals, those of every drive not in [keep] if given. */
fun forgetLaserPermissionDenials(keep: Collection<String>? = null) {
    if (keep == null) laserPermissionDenied.clear()
    else laserPermissionDenied.retainAll(keep.toSet())
}

/**
 * Suspends until [device] may be opened: returns at once if permission was
 * granted earlier, otherwise shows the system dialog and waits for the answer.
 * A refusal is recorded, see [isLaserPermissionDenied].
 *
 * @return true if the device may now be opened.
 */
private suspend fun requestUsbPermission(context: Context, device: UsbDevice): Boolean {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    if (!manager.hasPermission(device)) {
        Log.d(LASER_TAG, "requestUsbPermission: requesting permission for ${device.deviceName}")
        val granted = suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    // Several requests may be in flight, all on the same
                    // action: match the device the system echoes back.
                    val d = intent.parcelable<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.action != ACTION_USB_PERMISSION) return
                    if (d != null && d.deviceName != device.deviceName) return
                    context.unregisterReceiver(this)
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }

            // FLAG_MUTABLE is required: the system writes EXTRA_DEVICE and
            // EXTRA_PERMISSION_GRANTED into this PendingIntent, and an
            // immutable one comes back empty, reading as a denial. The
            // requestCode is per device, so that two requests do not share
            // one PendingIntent.
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                context, device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), piFlags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
            }

            cont.invokeOnCancellation {
                Log.d(LASER_TAG, "requestUsbPermission: cancelled while awaiting permission for ${device.deviceName}")
                try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) {}
            }
            manager.requestPermission(device, permissionIntent)
        }
        if (!granted) {
            Log.w(LASER_TAG, "USB permission denied for ${device.deviceName}")
            laserPermissionDenied.add(device.deviceName)
            return false
        }
    }

    return true
}
