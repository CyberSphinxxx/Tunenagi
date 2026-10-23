package com.tunenagi.core.model

/**
 * Importing one folder the user picked — the provider-neutral half.
 *
 * The app may not reach `core.provider` or `core.database` (B7 and the layering rules), but it does
 * own the system folder picker: the grant is requested by an Activity, and only an Activity can
 * persist it. So the contract is split at the grant: the app hands over the granted tree as a string
 * uri, the local adapter turns it into documents, and the answer is domain types only.
 *
 * A string rather than an `android.net.Uri` on purpose: this package is Android-free (rule B1), and
 * a contract that needed the platform would drag the platform into the model layer.
 */
interface LocalFolderImporter {

    /**
     * Scans the granted folder and adds what it finds to the library.
     *
     * Idempotent: importing the same folder twice reports the same files as `unchanged` and creates
     * no duplicate tracks. Nothing is deleted when a file disappears from the folder; that is what a
     * rescan is for, and a folder the user removed is not evidence that they want their playlists
     * emptied.
     */
    suspend fun importFolder(treeUri: String): LocalImportResult
}

/**
 * What one folder import did.
 *
 * The counts are the indexer's own report rather than a second tally, so "12 songs imported" cannot
 * disagree with what the library actually stored. [Imported.trackIds] is what the caller queues:
 * tracks, not sources, because a source may be replaced while the recording stays.
 */
sealed interface LocalImportResult {

    /** The folder was read. [trackIds] contains the playable tracks it offers, in folder order. */
    data class Imported(
        val trackIds: List<TrackId>,
        val added: Int = 0,
        val updated: Int = 0,
        val restored: Int = 0,
        val unchanged: Int = 0,
        val gone: Int = 0,
        val duplicates: Int = 0
    ) : LocalImportResult

    /** The grant is gone or was never usable; only the system picker can restore it. */
    data object PermissionRequired : LocalImportResult

    /** The folder could not be read for a reason of its own; the reason is safe to show. */
    data class Failed(val reason: String) : LocalImportResult
}
