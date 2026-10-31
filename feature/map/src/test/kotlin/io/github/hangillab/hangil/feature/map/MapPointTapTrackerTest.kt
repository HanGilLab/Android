package io.github.hangillab.hangil.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapPointTapTrackerTest {
    private fun tracker() = MapPointTapTracker<String>(8f, 500)

    private fun MapPointTapTracker<String>.down(action: String = "original") = begin(3, 100, 100, 0, 40f, 60f, action)

    private fun MapPointTapTracker<String>.move(
        epoch: Long = 3,
        downTime: Long = 100,
        time: Long = 120,
        now: Long = time,
        pointers: Int = 1,
        pointerId: Int = 0,
        x: Float = 40f,
        y: Float = 60f,
    ) = update(epoch, downTime, time, now, pointers, pointerId, x, y)

    @Test fun shortTapConsumesCapturedActionExactlyOnce() {
        val tracker = tracker()
        tracker.down()
        tracker.move(x = 44f, y = 63f)
        assertEquals("original", tracker.take()?.action)
        assertNull(tracker.take())
    }

    @Test fun movingOutsideSlopAndReturningCannotBecomeTap() {
        val tracker = tracker()
        tracker.down()
        tracker.move(x = 49f)
        tracker.move()
        assertNull(tracker.take())
    }

    @Test fun diagonalDistanceIsUsedInsteadOfIndependentAxisLimits() {
        val tracker = tracker()
        tracker.down()
        tracker.move(x = 46f, y = 66f)
        assertNull(tracker.take())
    }

    @Test fun slopBoundaryRemainsAValidTap() {
        val tracker = tracker()
        tracker.down()
        tracker.move(x = 48f)
        assertEquals("original", tracker.take()?.action)
    }

    @Test fun secondPointerPermanentlyRevokesTheTap() {
        val tracker = tracker()
        tracker.down()
        tracker.move(pointers = 2)
        tracker.move()
        assertNull(tracker.take())
    }

    @Test fun anotherPointerIdCannotFinishOriginalTap() {
        val tracker = tracker()
        tracker.down()
        tracker.move(pointerId = 1)
        assertNull(tracker.take())
    }

    @Test fun changedEpochCannotBorrowFreshPermission() {
        val tracker = tracker()
        tracker.down()
        tracker.move(epoch = 4)
        tracker.move()
        assertNull(tracker.take())
    }

    @Test fun anotherDownTimeCannotFinishOriginalTap() {
        val tracker = tracker()
        tracker.down()
        tracker.move(downTime = 101)
        assertNull(tracker.take())
    }

    @Test fun longPressBoundaryIsRejected() {
        val tracker = tracker()
        tracker.down()
        tracker.move(time = 600)
        assertNull(tracker.take())
    }

    @Test fun delayedQueuedUpIsRejectedEvenIfItsEventTimestampWasShort() {
        val tracker = tracker()
        tracker.down()
        tracker.move(time = 120, now = 600)
        assertNull(tracker.take())
    }

    @Test fun freshTapBelowDurationBoundaryCanFinish() {
        val tracker = tracker()
        tracker.down()
        tracker.move(time = 599)
        assertEquals("original", tracker.take()?.action)
    }

    @Test fun futureAndPreDownSamplesAreRejected() {
        for ((time, now) in listOf(99L to 120L, 121L to 120L)) {
            val tracker = tracker()
            tracker.down()
            tracker.move(time = time, now = now)
            assertNull(tracker.take())
        }
    }

    @Test fun nonFiniteCoordinatesCannotSelect() {
        for (coordinate in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val tracker = tracker()
            tracker.down()
            tracker.move(x = coordinate)
            assertNull(tracker.take())
            tracker.begin(3, 100, 100, 0, coordinate, 60f, "invalid")
            assertNull(tracker.take())
        }
    }

    @Test fun cancellationDiscardsActionAndFreshDownGetsNewAction() {
        val tracker = tracker()
        tracker.down()
        tracker.cancel()
        tracker.move()
        assertNull(tracker.take())
        tracker.begin(4, 700, 700, 0, 40f, 60f, "fresh")
        tracker.update(4, 700, 720, 720, 1, 0, 40f, 60f)
        assertEquals("fresh", tracker.take()?.action)
    }

    @Test fun newDownReplacesUnfinishedActionWithoutRetainingOldIntent() {
        val tracker = tracker()
        tracker.down()
        tracker.begin(3, 200, 200, 0, 40f, 60f, "new")
        tracker.update(3, 200, 220, 220, 1, 0, 40f, 60f)
        assertEquals("new", tracker.take()?.action)
    }

    @Test fun staleOrInvalidDownDoesNotCreatePendingAction() {
        val tracker = tracker()
        for ((down, now) in listOf(-1L to 0L, 101L to 100L, 100L to 600L)) {
            tracker.begin(3, down, now, 0, 40f, 60f, "invalid")
            assertNull(tracker.take())
        }
    }

    @Test fun oldDispatchCleanupCannotDiscardAReentrantNewDown() {
        val tracker = tracker()
        tracker.down()
        val old = tracker.current()
        tracker.begin(3, 200, 200, 0, 40f, 60f, "reentrant")
        tracker.discard(old)
        assertEquals("reentrant", tracker.take()?.action)
    }

    @Test fun currentFailedDispatchDiscardsOnlyItsIntent() {
        val tracker = tracker()
        tracker.down()
        tracker.discard(tracker.current())
        assertNull(tracker.take())
    }

    @Test fun consumedUpCannotTakeAReentrantDownsAction() {
        val tracker = tracker()
        tracker.down()
        val up = tracker.take()
        tracker.begin(3, 200, 200, 0, 40f, 60f, "reentrant")
        assertEquals("original", up?.action)
        tracker.discard(up)
        assertEquals("reentrant", tracker.take()?.action)
    }
}
