# TUN-0401 — merge design

**Goal:** one repository method that returns a paged `List<LibraryTrack>` mixing local + Audius by canonical Track/TrackSource, plus tests covering a negative/failure path.

**Why this shape (not more now):**
- TUN-0401 is "merge local+Audius catalog by canonical Track and TrackSource, with paged lists and tests." The smallest real slice that can be verified is a single repository query over the existing schema plus a JVM test against a real Room database, plus a failure-path case.
- The merged object is a local read model, not a new canonical entity: `Track` + its best source (availability, provider identity, variant) + derived fields the UI already names (playable, primary artist, artwork ref). This keeps the merge in the read layer and leaves writes untouched.
- Pagination here is a bounded "page" from a list, not a full cursor implementation: the task says "paged lists and tests", and the repo already has `TrackDao.searchByTitle` + `SourceDao.forTrack` as the building blocks. The merge method will accept `(term: String?, page: Int, pageSize: Int)` and return `(items, hasMore, total)`.
- Failure path: a search term that matches no track returns an empty page with `hasMore = false`, not null; a source lookup that finds no sources for a track still includes the track marked unavailable, so the merge never silently drops a track whose only source vanished.

**What this slice does not do (deferred to later tasks):**
- No UI widget, no ViewModel, no SearchScreen merge UI. That is TUN-0405 territory.
- No favorites/recently played merge, no playlist merge, no backup/restore. Those are TUN-0402/TUN-0404/TUN-0406.
- No Audius live call; the merge reads only what is already in the library.
- No Media3 integration here; this is a pure library-repository change.

**Concrete implementation plan:**
1. Add a small read model `LibraryTrack` into the library package (or reuse an existing projection if one exists — check first).
2. Add `MergedLibraryRepository`/`TrackCatalogRepository` interface with one method:
   `suspend fun pagedTracks(term: String?, page: Int, pageSize: Int): PagedTracks`
   where `PagedTracks` = `(items: List<LibraryTrack>, hasMore: Boolean, total: Int)`.
3. Implement it as a Room-backed class that:
   - queries tracks by `title LIKE` when term is present, else all non-tombstoned tracks;
   - for each track, finds its sources via `SourceDao.forTrack`, picks the preferred/available source for display, marks the track playable only if a source is playable;
   - applies the page slice server-side where reasonable, with explicit `hasMore`.
4. Tests (real Room):
   - empty library returns empty page;
   - local-only library returns only local rows;
   - remote-only library (persisted via `RemoteLibraryWriter`) returns only remote rows;
   - mixed library returns both, sorted by sortTitle/id, with correct source/availability per row;
   - a track with no playable source is included but marked unplayable;
   - pagination: pageSize smaller than total returns correct page + hasMore; last page hasMore false;
   - negative/failure path: search term with no match returns empty page + hasMore false + total 0;
   - tombstoned track is excluded from the merged list.
5. Regression: keep existing `SourceMatchRepositoryTest` / `LocalFolderImportTest` / `RemoteDiscoveryViewModelTest` green; run full `verify.py` at the end.

**Evidence I will attach:**
- `gradlew :core:testDebugUnitTest --tests <new suite> --rerun` manifest
- `python SCRIPTS/verify.py` full manifest
- failing manifests if any, kept as evidence

**Acceptance mapping:**
- "Merge local+Audius catalog by canonical Track and TrackSource" -> merged query over Track + TrackSource.
- "paged lists and tests" -> page/size params + page math + tests.
- "tests cover at least one negative/failure path" -> no-match + vanished-source cases.
- "real user/device evidence when applicable" -> this task's evidenceKind is `command`; the test is real Room on JVM, not a mock of the merge itself.
- "No new Critical/High issue; relevant regression tests remain passing" -> verified at commit time via `verify.py` + targeted suites.
