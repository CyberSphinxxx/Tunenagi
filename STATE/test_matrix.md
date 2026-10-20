# Test Matrix

Populate from `SPEC/EDGE_CASES.md` as tests are built. Each case needs scenario, priority, test ID/path, API/device, last result, evidence link, owner/agent. Do not mark NOT RUN as PASS.

| Scenario | Priority | Test | Environment | Last result | Evidence | Owner |
|---|---|---|---|---|---|---|
| Network timeout is retried twice with backoff, then reported exhausted with no timer left | P0 | `PlaybackRecoveryTest.aTimeoutIsRetriedWithBackoffAndThenReportedAsExhausted` | JVM / Robolectric, real recovery class, fake scheduler | PASS 2026-10-20 | `STATE/evidence/TUN-0207/20261020T061410Z-verify-all-steps-2.json` | Codebuff |
| Missing file, revoked permission and unsupported codec are typed and never retried automatically | P0 | `PlaybackRecoveryTest.aMissingFileOrUnsupportedCodecIsNotRetriedAutomatically`, `theTaxonomyGroupsCodesIntoWhatTheUserCanActOn` | JVM / Robolectric, real `PlaybackException` codes | PASS 2026-10-20 | same manifest | Codebuff |
| A retry scheduled for one item cannot prepare the next item, even if its callback was already dispatched | P0 | `PlaybackRecoveryTest.aScheduledRetryCannotPrepareTheNextItem` | JVM / Robolectric, generation guard | PASS 2026-10-20 | same manifest | Codebuff |
| Two errors before the timer fires leave one pending retry; eight concurrent errors cannot exceed the budget | P0 | `PlaybackRecoveryTest.twoErrorsBeforeTheTimerFiresLeaveExactlyOnePendingRetry`, `concurrentErrorsStillRespectTheBudget` | JVM / Robolectric, 8 threads | PASS 2026-10-20 | same manifest | Codebuff |
| Ready restores the retry budget; buffering never hides a failure; cancel invalidates a dispatched callback | P0 | `PlaybackRecoveryTest.reachingReadyClearsTheFailureAndRestoresTheRetryBudget`, `bufferingNeverHidesAFailureTheUserNeedsToSee`, `cancellingStopsTheTimerEvenIfItsCallbackWasAlreadyDispatched` | JVM / Robolectric | PASS 2026-10-20 | same manifest | Codebuff |
| Production main-looper scheduler honours both its delay and its cancel | P0 | `PlaybackRecoveryTest.theHandlerSchedulerHonoursItsDelayAndItsCancel` | JVM / Robolectric `ShadowLooper` | PASS 2026-10-20 | same manifest | Codebuff |
| The bar shows the reason and a working Retry control, and says Buffering without offering Retry | P0 | `TunenagiShellTest.aFailedTrackShowsTheReasonAndARetryControl`, `aBufferingTrackSaysSoAndDoesNotOfferARetry` | JVM / Robolectric Compose | PASS 2026-10-20 | same manifest | Codebuff |
| A real corrupt FLAC, a decoder rejection, a live stream timeout and a notification error surface | P1 | — | Device / live | NOT RUN — no device or live provider (ADR-008) | `OPERATIONS/DEFERRED_VERIFICATION.md` | Owner / future device session |
