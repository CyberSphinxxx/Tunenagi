package com.tunenagi.core.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The failure taxonomy and the bounded retry loop of TUN-0207.
 *
 * The scheduler is a controllable double so the tests assert the *policy* (how many attempts, what
 * delay, what cancels what) without sleeping; [PlaybackRecovery] itself is the production class and
 * the listener methods it overrides are the ones Media3 calls. The real-coplayer wiring is asserted
 * in `TunenagiPlaybackServiceTest`, and what a device adds is recorded in
 * `OPERATIONS/DEFERRED_VERIFICATION.md`.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackRecoveryTest {

    private class FakeScheduler : RetryScheduler {
        data class Task(val id: Int, val delayMillis: Long, val action: () -> Unit)

        val tasks = mutableListOf<Task>()
        var cancelled = 0
            private set
        private var nextId = 0

        override fun schedule(delayMillis: Long, retry: () -> Unit): PendingRetry {
            val task = Task(nextId++, delayMillis, retry)
            tasks += task
            return PendingRetry {
                cancelled++
                tasks.removeAll { it.id == task.id }
            }
        }

        fun runNext(): Boolean {
            val task = tasks.removeFirstOrNull() ?: return false
            task.action()
            return true
        }
    }

    private fun error(code: Int): PlaybackException =
        PlaybackException("test failure", null, code)

    private fun timeout() = error(PlaybackException.ERROR_CODE_TIMEOUT)

    private fun nextItem(): MediaItem = MediaItem.Builder().setMediaId("local:next").build()

    private fun recovery(
        scheduler: RetryScheduler,
        retries: AtomicInteger = AtomicInteger()
    ) = PlaybackRecovery(retry = { retries.incrementAndGet() }, scheduler = scheduler)

    @Test
    fun theTaxonomyGroupsCodesIntoWhatTheUserCanActOn() {
        assertEquals(
            PlaybackFailure.Timeout,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_TIMEOUT)
        )
        assertEquals(
            PlaybackFailure.Timeout,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
        assertEquals(
            PlaybackFailure.Network,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertEquals(
            PlaybackFailure.Network,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        )
        assertEquals(
            PlaybackFailure.MissingSource,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        )
        assertEquals(
            PlaybackFailure.AccessLost,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        )
        assertEquals(
            PlaybackFailure.UnsupportedFormat,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        )
        assertEquals(
            PlaybackFailure.UnsupportedFormat,
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        )
        assertEquals(
            PlaybackFailure.Unknown(PlaybackException.ERROR_CODE_UNSPECIFIED),
            PlaybackFailure.fromErrorCode(PlaybackException.ERROR_CODE_UNSPECIFIED)
        )

        // Only the transient failures may be retried without the user asking.
        assertTrue(PlaybackFailure.Timeout.retryable)
        assertTrue(PlaybackFailure.Network.retryable)
        assertFalse(PlaybackFailure.MissingSource.retryable)
        assertFalse(PlaybackFailure.AccessLost.retryable)
        assertFalse(PlaybackFailure.UnsupportedFormat.retryable)
        assertFalse(PlaybackFailure.Unknown(1).retryable)
    }

    @Test
    fun aTimeoutIsRetriedWithBackoffAndThenReportedAsExhausted() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)

        recovery.onPlayerError(timeout())

        val first = recovery.status.value as PlaybackStatus.Failed
        assertEquals(PlaybackFailure.Timeout, first.failure)
        assertEquals(1, first.automaticRetries)
        assertTrue("a retryable failure must be scheduled", first.willRetryAutomatically)
        assertEquals(1, scheduler.tasks.size)
        assertEquals(
            "the first retry waits one second",
            PlaybackRecovery.DEFAULT_BASE_BACKOFF_MS,
            scheduler.tasks.single().delayMillis
        )

        scheduler.runNext()
        assertEquals(1, retries.get())

        recovery.onPlayerError(timeout())
        val second = recovery.status.value as PlaybackStatus.Failed
        assertEquals(2, second.automaticRetries)
        assertTrue(second.willRetryAutomatically)
        assertEquals(
            "backoff doubles for the second attempt",
            PlaybackRecovery.DEFAULT_BASE_BACKOFF_MS * 2,
            scheduler.tasks.single().delayMillis
        )
        scheduler.runNext()
        assertEquals(2, retries.get())

        // The third failure is out of budget: no timer, and the UI is allowed to offer Retry.
        recovery.onPlayerError(timeout())
        val exhausted = recovery.status.value as PlaybackStatus.Failed
        assertEquals(2, exhausted.automaticRetries)
        assertFalse(exhausted.willRetryAutomatically)
        assertTrue("an exhausted budget must not leave a timer behind", scheduler.tasks.isEmpty())

        scheduler.runNext()
        assertEquals("the budget bounds the number of prepare() calls", 2, retries.get())
    }

    @Test
    fun aMissingFileOrUnsupportedCodecIsNotRetriedAutomatically() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)

        recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))

        val missing = recovery.status.value as PlaybackStatus.Failed
        assertEquals(PlaybackFailure.MissingSource, missing.failure)
        assertFalse(missing.willRetryAutomatically)
        assertTrue("a permanent failure must not schedule work", scheduler.tasks.isEmpty())

        recovery.onPlayerError(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))

        val codec = recovery.status.value as PlaybackStatus.Failed
        assertEquals(PlaybackFailure.UnsupportedFormat, codec.failure)
        assertFalse(codec.willRetryAutomatically)
        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(0, retries.get())
    }

    /**
     * The race the generation counter exists for: a retry scheduled for one track must never run
     * after the player has moved to another one. Otherwise a listener who pressed Next would hear
     * the new track interrupted by a `prepare()` belonging to the failed one.
     */
    @Test
    fun aScheduledRetryCannotPrepareTheNextItem() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)

        recovery.onPlayerError(timeout())
        val scheduled = scheduler.tasks.single().action

        recovery.onMediaItemTransition(nextItem(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(PlaybackStatus.Idle, recovery.status.value)
        assertTrue("the transition must cancel the timer", scheduler.tasks.isEmpty())

        // Even a callback that had already been dispatched is refused by the generation check.
        scheduled()
        assertEquals("a stale retry must not run", 0, retries.get())
    }

    @Test
    fun twoErrorsBeforeTheTimerFiresLeaveExactlyOnePendingRetry() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)

        recovery.onPlayerError(timeout())
        recovery.onPlayerError(timeout())

        assertEquals(
            "the previous timer must be cancelled, not left to fire as well",
            1,
            scheduler.tasks.size
        )
        assertEquals(1, scheduler.cancelled)
        val state = recovery.status.value as PlaybackStatus.Failed
        assertEquals("both errors count against the budget", 2, state.automaticRetries)

        scheduler.runNext()
        assertEquals(1, retries.get())

        // One more error now exhausts the budget rather than scheduling a third attempt.
        recovery.onPlayerError(timeout())
        assertFalse((recovery.status.value as PlaybackStatus.Failed).willRetryAutomatically)
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun reachingReadyClearsTheFailureAndRestoresTheRetryBudget() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)

        recovery.onPlayerError(timeout())
        scheduler.runNext()
        recovery.onPlayerError(timeout())
        scheduler.runNext()
        recovery.onPlayerError(timeout())
        assertEquals(2, (recovery.status.value as PlaybackStatus.Failed).automaticRetries)

        recovery.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(PlaybackStatus.Ready, recovery.status.value)

        // A later failure on the same item is a new episode with its own two attempts.
        recovery.onPlayerError(timeout())
        val afterRecovery = recovery.status.value as PlaybackStatus.Failed
        assertEquals(1, afterRecovery.automaticRetries)
        assertTrue(afterRecovery.willRetryAutomatically)
        assertEquals(1, scheduler.tasks.size)
    }

    @Test
    fun bufferingNeverHidesAFailureTheUserNeedsToSee() {
        val scheduler = FakeScheduler()
        val recovery = recovery(scheduler)

        recovery.onPlayerError(error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        val failed = recovery.status.value

        recovery.onPlaybackStateChanged(Player.STATE_BUFFERING)

        assertEquals(
            "Media3 reports BUFFERING while it tears a failed load down",
            failed,
            recovery.status.value
        )
    }

    @Test
    fun cancellingStopsTheTimerEvenIfItsCallbackWasAlreadyDispatched() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)

        recovery.onPlayerError(timeout())
        val dispatched = scheduler.tasks.single().action

        recovery.cancel()
        retries.set(0)
        dispatched()

        assertEquals(0, retries.get())
    }

    /**
     * Media3 delivers player callbacks on one thread, but the service can be destroyed while a
     * timer is being scheduled. Hammering the listener from several threads asserts the lock is
     * real: the budget is never exceeded and at most one timer survives.
     */
    @Test
    fun concurrentErrorsStillRespectTheBudget() {
        val scheduler = FakeScheduler()
        val retries = AtomicInteger()
        val recovery = recovery(scheduler, retries)
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    recovery.onPlayerError(timeout())
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("the threads must finish", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        val state = recovery.status.value as PlaybackStatus.Failed
        assertEquals(
            "eight errors must still spend exactly the two-attempt budget",
            PlaybackRecovery.DEFAULT_MAX_AUTOMATIC_RETRIES,
            state.automaticRetries
        )
        assertFalse(
            "the last error in the burst exhausts the budget",
            state.willRetryAutomatically
        )
        assertTrue("the exhausted burst must not leave a timer behind", scheduler.tasks.isEmpty())
        assertEquals("no prepare() may run from a burst that ended exhausted", 0, retries.get())
    }

    /**
     * The production scheduler, exercised through Robolectric's main looper: a delay that never
     * fires and a cancel that does not remove the callback would both pass the fake-scheduler tests
     * above, and both would be bugs on a device.
     */
    @Test
    fun theHandlerSchedulerHonoursItsDelayAndItsCancel() {
        val scheduler = HandlerRetryScheduler()
        val fired = AtomicInteger()

        val pending = scheduler.schedule(1_000) { fired.incrementAndGet() }
        val cancelled = scheduler.schedule(1_000) { fired.incrementAndGet() }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals("nothing may run early", 0, fired.get())

        cancelled.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertEquals("only the uncancelled retry may run", 1, fired.get())

        // Cancelling an already-run callback is harmless; the service can destroy twice.
        pending.cancel()
    }
}
