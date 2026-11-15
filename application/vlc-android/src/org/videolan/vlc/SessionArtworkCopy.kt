package org.videolan.vlc

/**
 * One session-owned copy of the current artwork.
 *
 * The media session and notification can keep the bitmap they were given.
 * The image cache can drop or recycle its own instance. This copy is a
 * different bitmap, reused while the media and the source instance stay
 * the same. [drop] only forgets the reference. It does not recycle, because
 * the session may still be drawing the previous metadata.
 */
internal class SessionArtworkCopy<T : Any>(private val copyOf: (T) -> T) {
    private var mediaKey: String? = null
    private var source: T? = null
    private var published: T? = null

    @Synchronized
    fun publishedArt(mediaKey: String, source: T): T {
        val current = published
        if (current != null && this.mediaKey == mediaKey && this.source === source) return current
        val next = copyOf(source)
        this.mediaKey = mediaKey
        this.source = source
        published = next
        return next
    }

    @Synchronized
    fun drop() {
        mediaKey = null
        source = null
        published = null
    }
}
