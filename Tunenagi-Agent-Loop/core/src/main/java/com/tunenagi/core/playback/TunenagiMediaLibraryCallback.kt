package com.tunenagi.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * How the session turns the media items a controller asks for into items the player can play —
 * owned.
 *
 * A controller in another process cannot send a file path or a content URI: only the media id and
 * the metadata cross the boundary. The session therefore has to resolve every requested item
 * itself, and *that* is where an unavailable or unplayable source must become a typed failure
 * rather than a crash or a silently empty queue.
 *
 * Phase 02 has no library behind it yet, so [LocalUriOnly] is the honest implementation: an item
 * that already carries a playable local URI is passed through (this is what makes playback work for
 * a file the app itself selected), and anything else fails with [UnresolvedMediaItemException].
 * The MediaStore/SAF-backed resolver replaces this object in TUN-0203, and the callback that uses
 * it does not change.
 */
fun interface MediaItemResolution {

    /** Returns a playable item, or throws [UnresolvedMediaItemException]. */
    fun resolve(requested: MediaItem): MediaItem

    companion object {
        /**
         * Accepts items that carry a local configuration URI and rejects the rest.
         *
         * It never invents a URI: guessing a path from a media id would turn a "not found" into a
         * playback of something else, and a wrong song is worse than an error message.
         */
        val LocalUriOnly = MediaItemResolution { requested ->
            if (requested.localConfiguration?.uri != null) {
                requested
            } else {
                throw UnresolvedMediaItemException(requested.mediaId)
            }
        }
    }
}

/**
 * Thrown when the session cannot turn a requested media item into something playable.
 *
 * Typed rather than a bare `IllegalStateException` so the controller side receives a failure it can
 * report verbatim, and so TUN-0207 can map it to a user-visible state without parsing a message.
 */
class UnresolvedMediaItemException(
    val mediaId: String,
    val source: String? = null
) : RuntimeException(
    buildString {
        append("Tunenagi cannot play media item '")
        append(mediaId)
        append("': no playable local source is attached")
        if (source != null) append(" (source: ").append(source).append(')')
    }
)

/**
 * The library session callback — owned.
 *
 * It serves the content tree a browser asks for and resolves items a controller wants to play.
 * Anything not implemented here keeps Media3's default (`RESULT_ERROR_NOT_SUPPORTED`) instead of
 * being stubbed with a plausible-looking empty answer: a browser that receives an explicit
 * "not supported" is told the truth, while an empty list would look like an empty library.
 *
 * The tree is deliberately a root with no children in phase 02 — local discovery is TUN-0202, and a
 * root whose children are invented would be a lie in the one place a system UI displays it.
 */
class TunenagiMediaLibraryCallback(
    private val resolution: MediaItemResolution = MediaItemResolution.LocalUriOnly
) : MediaLibraryService.MediaLibrarySession.Callback {

    // The two-argument `LibraryResult.ofError` overloads that echo the caller's params are Media3's
    // opt-in surface. The AndroidX opt-in annotation is used rather than Kotlin's `kotlin.OptIn`
    // because `UnstableApi` is a Java marker: the compiler does not police it, the lint gate does,
    // and lint only recognises `androidx.annotation.OptIn`.
    // The opt-in is per function rather than per class: at class scope it would silently cover
    // every later addition to this file, which is how an unstable API gets adopted by accident.
    @OptIn(UnstableApi::class)
    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        when {
            // The error codes are `SessionError`'s rather than `LibraryResult`'s deprecated aliases:
            // they are the same integers, and the named ones are what Media3's own annotations and
            // docs use.
            !browser.isTrusted -> Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED, params)
            )
            else -> Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))
        }

    @OptIn(UnstableApi::class)
    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        when {
            !browser.isTrusted -> Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED, params)
            )
            parentId != ROOT_MEDIA_ID -> Futures.immediateFuture(
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            )
            else -> Futures.immediateFuture(LibraryResult.ofItemList(emptyList(), params))
        }

    /**
     * Resolves requested items, failing the whole request when any of them cannot be resolved.
     *
     * Failing the request is Media3's own contract for this callback and it is also the safer
     * behaviour: a partly-resolved queue would play some of what the user asked for and leave a
     * broken entry that only surfaces later, mid-playback. The resolver reports which item failed.
     */
    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> =
        try {
            Futures.immediateFuture(mediaItems.map(resolution::resolve))
        } catch (unresolved: UnresolvedMediaItemException) {
            Futures.immediateFailedFuture(unresolved)
        }

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ROOT_TITLE)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    companion object {
        /** The single root node of the content tree. */
        const val ROOT_MEDIA_ID = "tunenagi:root"

        /** Shown by system browsers (Android Auto, media resumption) for the root node. */
        const val ROOT_TITLE = "Tunenagi"
    }
}
