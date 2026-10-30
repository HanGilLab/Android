package io.github.hangillab.hangil.feature.map

/** UI-thread ownership only. SDK input delivery and animation cancellation remain with the View. */
internal class MapInputEpoch {
    private var started = false
    private var permitted = false
    private var serial = 0L
    private var startedAt = Long.MAX_VALUE
    private var permittedAt = Long.MAX_VALUE
    private var lastDownTime = Long.MIN_VALUE
    private var touch: Touch? = null

    val epoch: Long get() = serial

    fun start(now: Long) {
        require(now >= 0)
        if (started) return
        started = true
        startedAt = now
        permitted = false
        touch = null
    }

    /** Repeated loading/denied checks do not create owners or move an accepted gesture's floor. */
    fun updatePermission(
        allowed: Boolean,
        now: Long,
    ): Boolean {
        require(now >= 0)
        val next = started && allowed
        if (permitted == next) return false
        permitted = next
        touch = null
        if (next) {
            serial++
            permittedAt = maxOf(startedAt, now)
        }
        return true
    }

    fun stop() {
        started = false
        permitted = false
        touch = null
    }

    fun owns(expectedEpoch: Long): Boolean = started && permitted && serial == expectedEpoch

    /** A denied DOWN is remembered too; past/future timestamps cannot become fresh input. */
    fun beginTouch(
        downTime: Long,
        eventTime: Long,
        now: Long,
        deviceId: Int,
        source: Int,
    ): Boolean {
        val validTime = validTime(downTime, eventTime, now)
        val fresh = validTime && downTime > lastDownTime
        if (validTime) lastDownTime = maxOf(lastDownTime, downTime)
        touch =
            if (started && permitted && downTime >= permittedAt && fresh) {
                Touch(serial, downTime, deviceId, source)
            } else {
                null
            }
        return touch != null
    }

    fun ownsTouch(
        downTime: Long,
        eventTime: Long,
        now: Long,
        deviceId: Int,
        source: Int,
    ): Boolean =
        validTime(downTime, eventTime, now) && downTime >= permittedAt &&
            touch?.let {
                owns(it.epoch) && it.downTime == downTime && it.deviceId == deviceId && it.source == source
            } == true

    fun allowsDiscreteEvent(
        downTime: Long,
        eventTime: Long,
        now: Long,
    ): Boolean = owns(serial) && validTime(downTime, eventTime, now) && eventTime >= permittedAt

    fun endTouch() {
        touch = null
    }

    private fun validTime(
        downTime: Long,
        eventTime: Long,
        now: Long,
    ): Boolean = downTime >= 0 && eventTime >= downTime && eventTime <= now

    private data class Touch(
        val epoch: Long,
        val downTime: Long,
        val deviceId: Int,
        val source: Int,
    )
}
