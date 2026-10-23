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
import com.google.common.util.concurrent.SettableFuture
import com.tunenagi.core.model.ProviderError
import com.tunenagi.core.model.SourceAvailability
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How the session turns the media items a controller asks for into items the player can play —
 * owned.
 *
 * A controller in another process cannot send a file path or a content URI: only the media id and
 * the metadata cross the boundary. The session therefore has to resolve every requested item
 * itself, and *that* is where an unavailable or unplayable source must become a typed failure
 * rather than a crash or a silently empty queue.
 *
 * [LocalUriOnly] is the honest floor and the default: an item that already carries a playable local
 * URI is passed through (this is what makes playback work for a file the app itself selected), and
 * anything else fails with [UnresolvedMediaItemException]. The library-backed implementation
 * (`LibraryMediaItemResolution`, TUN-0203) replaces it wherever a resolver and a database are
 * installed, and the callback below does not change either way.
 *
 * The seam is *list*-shaped and asynchronous because that is what the caller actually is: Media3
 * asks for a set of items, and answering it requires a database read and a provider resolve. A
 * synchronous per-item signature would have forced the lookup onto the session's thread — the same
 * mistake TUN-0202 refused to make with a MediaStore query.
 */
fun interface MediaItemResolution {

    /**
     * Returns the items the player should hold, or fails the whole request with
     * [UnresolvedMediaItemException].
     */
    fun resolve(requested: List<MediaItem>): ListenableFuture<List<MediaItem>>

    companion object {
        /**
         * Accepts items that carry a local configuration URI and rejects the rest.
         *
         * It never invents a URI: guessing a path from a media id would turn a "not found" into a
         * playback of something else, and a wrong song is worse than an error message. It also names
         * the first item that failed, so the caller can report *which* one it was rather than only
         * that something in the batch was unresolvable.
         */
        val LocalUriOnly = MediaItemResolution { requested ->
            val unresolved = requested.firstOrNull { it.localConfiguration?.uri == null }
            if (unresolved == null) {
                Futures.immediateFuture(requested)
            } else {
                Futures.immediateFailedFuture<List<MediaItem>>(
                    UnresolvedMediaItemException(unresolved.mediaId, NO_LOCAL_URI)
                )
            }
        }

        private const val NO_LOCAL_URI = "the item carries no playable local uri"
    }
}

/**
 * Thrown when the session cannot turn a requested media item into something playable.
 *
 * Typed rather than a bare `IllegalStateException` so the controller side receives a failure it can
 * report verbatim, and so TUN-0207 can map it to a user-visible state without parsing a message.
 *
 * The reason travels as *values*, not as prose: [error] is the provider taxonomy entry the resolver
 * produced and [availability] is the source state that needs a user action. A caller that only has
 * the message would have to string-match it to decide whether to offer a retry or a re-grant, and a
 * message is the one part of an exception this project is happy to change.
 */
class UnresolvedMediaItemException(
    val mediaId: String,
    /** A short, non-sensitive label for why this item could not be resolved. */
    val source: String? = null,
    /** The typed provider failure, when the resolver produced one. */
    val error: ProviderError? = null,
    /** The source state the user has to change, when the resolve needs an action. */
    val availability: SourceAvailability? = null
) : RuntimeException(
    buildString {
        append("Tunenagi cannot play media item '")
        append(mediaId)
        append("': ")
        when {
            error != null -> append(error.userMessage)
            availability == SourceAvailability.PermissionRequired ->
                append("access to it has to be granted again")
            availability == SourceAvailability.StorageGone ->
                append("the file is no longer on this device")
            else -> append("no playable local source is attached")
        }
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
    private val resolution: MediaItemResolution = MediaItemResolution.LocalUriOnly,
    /**
     * Where the queue the platform may resume lives, or `null` when the host installed no library.
     *
     * Optional so the callback remains usable (and testable) without a database, and read lazily by
     * [onPlaybackResumption] rather than captured per command: the queue it answers with must be the
     * one stored when the system asks, not the one that existed when the service was built.
     */
    private val queue: QueueRepository? = null,
    /** The scope the resumption read runs on; the service passes the scope it cancels with itself. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
     * broken entry that only surfaces later, mid-playback. The resolution reports which item failed
     * and why, and this method deliberately adds nothing to that: an adapter that re-wrote the
     * failure would be the second place the error taxonomy lives.
     */
    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> = resolution.resolve(mediaItems)

    /**
     * Answers the platform's "resume what this user was listening to" request — TUN-0206.
     *
     * This is the *only* path allowed to start sound without the user touching the app in this
     * slice, and it is the distinction `SPEC/DATA_MODEL.md` draws: the OS creating the service (or
     * the app being opened) is not a resumption request, while a media button, a Bluetooth AVRCP
     * play, or the system's media resumption card is. Media3 calls this only for those, hands the
     * returned items to the player and starts playback, so this method answers with the stored
     * queue - it does not touch the player itself, which would make two writers race for the same
     * queue.
     *
     * A user who never resumed sees nothing: an empty store resolves to `null`, which Media3 treats
     * as "nothing to resume" rather than as a failure. An untrusted controller is refused outright,
     * because answering would let any installed app start audio.
     */
    @OptIn(UnstableApi::class)
    override fun onPlaybackResumption(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (!controller.isTrusted) {
            return Futures.immediateFailedFuture(
                SecurityException("resumption is only answered for a trusted controller")
            )
        }
        val repository = queue ?: return Futures.immediateFuture(null)
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val snapshot = repository.snapshot()
                if (snapshot.queue.isEmpty()) {
                    // Only reached on a first launch: there is nothing to resume, and Media3 needs
                    // to hear that as an answer, not as an error the system UI would surface.
                    result.set(null)
                    return@launch
                }
                // The stored pointer can name an item a queue edit removed while the app was not
                // running. Starting the queue from its first item is the same rule TUN-0205's
                // restore applies, and for the same reason: a position belongs to one item.
                val index = snapshot.queue.indexOfFirst { it.id == snapshot.currentItemId }
                val startIndex = index.coerceAtLeast(0)
                val startPositionMs = if (index >= 0) snapshot.progressMs.coerceAtLeast(0) else 0L
                // Resolve late, off the caller's thread: a file can have moved since the queue was
                // written, and a resumption that fails is a typed failure Media3 reports rather
                // than a queue of items that will each fail silently once played.
                val items = withContext(Dispatchers.IO) {
                    resolution.resolve(snapshot.queue.map { it.requestedMediaItem() }).get()
                }
                result.set(
                    MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
                )
            } catch (failure: ExecutionException) {
                result.setException(failure.cause ?: failure)
            } catch (failure: Throwable) {
                result.setException(failure)
            }
        }
        return result
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
