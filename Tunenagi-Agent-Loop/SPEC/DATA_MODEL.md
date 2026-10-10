# Room Data Model & Backup Format

## Core IDs and invariants

`Track.id`: app UUID, immutable, never provider ID. `TrackSource.id`: app UUID; unique tuple `(providerId, externalId, sourceVariant)` where applicable; many sources per track; source can disappear without deleting Track/PlaylistEntry. `PlaylistEntry.id`: unique UUID to allow duplicates and stable drag/reorder. `ProviderId`: namespaced stable identifier (`builtin.local`, `builtin.audius`, `external.example`).

### Proposed Room tables

```
Track(id PK, title, sortTitle, durationMs?, albumId?, isrc?, mbid?, artworkRef?,
      createdAt, updatedAt, deletedTombstone BOOL DEFAULT false)
Artist(id PK, name, sortName, mbid?)
Album(id PK, title, albumArtistId?, year?, mbid?, artworkRef?)
TrackArtist(trackId FK, artistId FK, ord, role)
TrackSource(id PK, trackId FK, providerId, externalId, localUri?, sourceVariant?,
            contentType?, lastKnownAvailability, lastCheckedAt?, preferred BOOL, locked BOOL)
Playlist(id PK, name, description?, createdAt, updatedAt, isSystem BOOL)
PlaylistEntry(id PK, playlistId FK, trackId FK, position REAL/INTEGER, addedAt, sourceLockId?)
Favorite(trackId PK FK, addedAt)
PlaybackQueueItem(id PK, trackId FK, sourceOverrideId?, position, addedAt)
PlaybackState(singleton PK, currentQueueId?, progressMs, shuffleEnabled,
              shuffleSeed?, repeatMode, lastUpdatedAt)
PlaybackHistory(id PK, trackId FK, sourceId?, startedAt, listenedMs, completed BOOL)
LyricsCache(trackId, provider, language?, syncedLrc?, plainText?, provenanceUrl?,
            fetchedAt, etag?, matchConfidence)
PluginInstallation(id PK, version, manifestHash, enabled, approvedDomainJson,
                   installOrigin, installedAt, manifestJson)
MetadataMatch(trackId PK, provider, externalId, score, manuallyConfirmed BOOL)
```

Schema is **conceptual**; choose Room-compatible types and migrations; don't use `position REAL` if it risks unstable ordering without tie-breaker. Enforce foreign keys and indexes `(providerId,externalId)`, `(playlistId,position)`, `(trackId)`, `(title)`; FTS if needed after profiling. Sensitive URIs/tokens must not be exposed to plugins. Do not persist expiring audio URL as durable identity.

## Matching & duplicates

Prefer exact same physical file stable URI or verified hashes; then unique external provider ID; then MusicBrainz recording ID/ISRC with secondary artist/title/duration checks. A title/artist similarity alone is not enough to automatically merge editions. Source candidate scoring should produce explicit confidence/reasons; manual overrides trump heuristics. Missing local files retain their source row as unavailable; re-granted permission may restore them.

## Playback persistence

One Room transaction updates queue ordering and current item; queue items distinguish duplicate occurrences and Play Next inserts. Persist current position periodically (e.g. at 5–15-second intervals) and on suspend/seek/pause; do not issue DB writes every audio tick. Queue resumption after process death should not autoplay unexpectedly unless Android media-resumption contract is met.

## Portable backup (minimum schema)

```json
{
  "format": "tunenagi-library",
  "schemaVersion": 1,
  "exportedAt": "2026-10-11T00:00:00Z",
  "playlists": [{"externalKey": "p1", "name": "Favorites Mix", "entries": [
    {"track": {"title": "Example", "artists": ["Artist"], "durationMs": 190000},
     "source": {"providerId": "builtin.audius", "externalId": "example-id"}}
  ]}],
  "favorites": [], "sourcePreferences": []
}
```

Export must redact local absolute paths, expiring bearer tokens, authentication headers and unrelated private data; local paths/URIs may be included only in explicitly requested device-specific exports with clear warning. On import, validate length/schema, field types, numeric bounds, duplicates, unknown provider, and all-or-nothing rollback on corrupt data. Do not overwrite existing playlists without explicit choice. Test round trips and schema migrations from future versions.
