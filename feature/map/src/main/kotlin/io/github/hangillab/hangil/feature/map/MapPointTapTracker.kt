package io.github.hangillab.hangil.feature.map

/** A canvas DOWN owns one short, single-pointer intent. No delayed SDK click is used. */
internal class MapPointTapTracker<A>(
    private val slopPx: Float,
    private val maxDurationMillis: Long,
) {
    private var pending: Tap<A>? = null

    init {
        require(slopPx.isFinite() && slopPx >= 0f && maxDurationMillis > 0)
    }

    fun begin(
        epoch: Long,
        downTime: Long,
        now: Long,
        pointerId: Int,
        x: Float,
        y: Float,
        action: A,
    ) {
        pending =
            if (x.isFinite() && y.isFinite() && downTime >= 0 && now >= downTime && now - downTime < maxDurationMillis) {
                Tap(epoch, downTime, pointerId, x, y, action)
            } else {
                null
            }
    }

    /** Historical moves must be checked too: leaving the slop cannot be undone by returning. */
    fun update(
        epoch: Long,
        downTime: Long,
        eventTime: Long,
        now: Long,
        pointerCount: Int,
        pointerId: Int,
        x: Float,
        y: Float,
    ) {
        val tap = pending ?: return
        if (tap.epoch != epoch || tap.downTime != downTime || tap.pointerId != pointerId || pointerCount != 1 ||
            !x.isFinite() || !y.isFinite() || eventTime < downTime || now < eventTime ||
            eventTime - downTime >= maxDurationMillis || now - downTime >= maxDurationMillis ||
            (x - tap.x) * (x - tap.x) + (y - tap.y) * (y - tap.y) > slopPx * slopPx
        ) {
            pending = null
        }
    }

    fun current(): Tap<A>? = pending

    /** A failing older dispatch cannot discard a reentrant DOWN's newer intent. */
    fun discard(expected: Tap<A>?) {
        if (pending === expected) pending = null
    }

    /** The caller checks the final UP before consuming. A duplicate UP cannot reuse this action. */
    fun take(): Tap<A>? = pending.also { pending = null }

    fun cancel() {
        pending = null
    }

    data class Tap<A>(
        val epoch: Long,
        val downTime: Long,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val action: A,
    )
}
