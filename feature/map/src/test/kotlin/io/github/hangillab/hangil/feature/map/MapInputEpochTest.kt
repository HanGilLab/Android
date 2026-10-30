package io.github.hangillab.hangil.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInputEpochTest {
    @Test fun `a cold or stopped view cannot grant an owner or accept touch`() {
        val input = MapInputEpoch()
        assertFalse(input.owns(0))
        assertFalse(input.updatePermission(true, 100))
        assertFalse(input.beginTouch(100, 100, 100, 1, 2))
        assertEquals(0L, input.epoch)
    }

    @Test fun `repeated loading or denied checks do not create owners`() {
        val input = MapInputEpoch()
        input.start(100)
        repeat(20) { assertFalse(input.updatePermission(false, 100L + it)) }
        assertEquals(0L, input.epoch)
        assertFalse(input.owns(0))
    }

    @Test fun `a new allowance creates exactly one owner until a denial`() {
        val input = started()
        assertTrue(input.updatePermission(true, 110))
        val owner = input.epoch
        repeat(20) { assertFalse(input.updatePermission(true, 120L + it)) }
        assertEquals(owner, input.epoch)
        assertTrue(input.owns(owner))
        assertFalse(input.owns(owner + 1))
    }

    @Test fun `repeated start does not revoke a valid ongoing gesture or move its floor`() {
        val input = permitted()
        val owner = input.epoch
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        input.start(130)
        assertFalse(input.updatePermission(true, 140))
        assertTrue(input.owns(owner))
        assertTrue(input.ownsTouch(110, 150, 150, 1, 2))
    }

    @Test fun `denial revokes both the captured owner and its touch stream`() {
        val input = permitted()
        val owner = input.epoch
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        assertTrue(input.updatePermission(false, 120))
        assertFalse(input.owns(owner))
        assertFalse(input.ownsTouch(110, 130, 130, 1, 2))
    }

    @Test fun `P return creates a new owner but does not promote old MOVE or UP`() {
        val input = permitted()
        val old = input.epoch
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        input.updatePermission(false, 120)
        input.updatePermission(true, 130)
        assertNotEquals(old, input.epoch)
        assertFalse(input.owns(old))
        assertFalse(input.ownsTouch(110, 140, 140, 1, 2))
        assertTrue(input.beginTouch(150, 150, 150, 1, 2))
        assertTrue(input.ownsTouch(150, 160, 160, 1, 2))
    }

    @Test fun `a DOWN received during denial cannot be replayed as a fresh P gesture`() {
        val input = started()
        assertFalse(input.beginTouch(110, 110, 110, 1, 2))
        input.updatePermission(true, 120)
        assertFalse(input.beginTouch(110, 130, 130, 1, 2))
        assertFalse(input.ownsTouch(110, 140, 140, 1, 2))
        assertTrue(input.beginTouch(150, 150, 150, 1, 2))
    }

    @Test fun `same owner still requires the original device source and downTime`() {
        val input = permitted()
        assertTrue(input.beginTouch(110, 110, 110, 7, 2))
        assertFalse(input.ownsTouch(111, 120, 120, 7, 2))
        assertFalse(input.ownsTouch(110, 120, 120, 8, 2))
        assertFalse(input.ownsTouch(110, 120, 120, 7, 3))
        assertTrue(input.ownsTouch(110, 120, 120, 7, 2))
    }

    @Test fun `ending a gesture discards intent and requires a newer DOWN`() {
        val input = permitted()
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        input.endTouch()
        assertFalse(input.ownsTouch(110, 120, 120, 1, 2))
        assertFalse(input.beginTouch(110, 120, 120, 1, 2))
        assertTrue(input.beginTouch(130, 130, 130, 1, 2))
    }

    @Test fun `stop rejects callbacks before a new lifecycle owner starts`() {
        val input = permitted()
        val old = input.epoch
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        input.stop()
        assertFalse(input.owns(old))
        assertFalse(input.ownsTouch(110, 120, 120, 1, 2))
        assertFalse(input.updatePermission(true, 130))
    }

    @Test fun `restart needs fresh permission and cannot reuse the previous owner or touch`() {
        val input = permitted()
        val old = input.epoch
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        input.stop()
        input.start(200)
        assertFalse(input.owns(old))
        input.updatePermission(true, 210)
        assertFalse(input.owns(old))
        assertFalse(input.beginTouch(110, 220, 220, 1, 2))
        assertTrue(input.beginTouch(230, 230, 230, 1, 2))
    }

    @Test fun `a new native View rejects old timestamps even without a previous DOWN watermark`() {
        val input = MapInputEpoch()
        input.start(200)
        input.updatePermission(true, 210)
        assertFalse(input.beginTouch(150, 220, 220, 1, 2))
        assertFalse(input.ownsTouch(150, 230, 230, 1, 2))
        assertTrue(input.beginTouch(240, 240, 240, 1, 2))
    }

    @Test fun `same millisecond at the floor is allowed once but cannot identify two separate DOWNs`() {
        val input = MapInputEpoch()
        input.start(100)
        input.updatePermission(true, 100)
        assertTrue(input.beginTouch(100, 100, 100, 1, 2))
        input.endTouch()
        assertFalse(input.beginTouch(100, 100, 100, 1, 2))
        assertTrue(input.beginTouch(101, 101, 101, 1, 2))
    }

    @Test fun `negative inverted or future touch times fail without poisoning later input`() {
        val input = permitted()
        assertFalse(input.beginTouch(-1, 110, 110, 1, 2))
        assertFalse(input.beginTouch(120, 119, 130, 1, 2))
        assertFalse(input.beginTouch(1_000, 1_000, 130, 1, 2))
        assertTrue(input.beginTouch(140, 140, 140, 1, 2))
        assertFalse(input.ownsTouch(140, 139, 150, 1, 2))
        assertFalse(input.ownsTouch(140, 160, 150, 1, 2))
        assertTrue(input.ownsTouch(140, 150, 150, 1, 2))
    }

    @Test fun `new permission has its own floor even when a view was already started`() {
        val input = permitted()
        input.updatePermission(false, 150)
        input.updatePermission(true, 200)
        assertFalse(input.beginTouch(180, 210, 210, 1, 2))
        assertTrue(input.beginTouch(220, 220, 220, 1, 2))
    }

    @Test fun `discrete wheel events need a current owner and current event time`() {
        val input = permitted()
        assertTrue(input.allowsDiscreteEvent(0, 110, 110))
        assertFalse(input.allowsDiscreteEvent(0, 90, 110))
        assertFalse(input.allowsDiscreteEvent(0, 120, 110))
        assertFalse(input.allowsDiscreteEvent(120, 110, 130))
        input.updatePermission(false, 130)
        assertFalse(input.allowsDiscreteEvent(0, 140, 140))
        input.updatePermission(true, 150)
        assertFalse(input.allowsDiscreteEvent(0, 140, 160))
        assertTrue(input.allowsDiscreteEvent(0, 160, 160))
    }

    @Test fun `ownership checks do not create a reader or mutate an accepted stream`() {
        val input = permitted()
        val owner = input.epoch
        assertTrue(input.beginTouch(110, 110, 110, 1, 2))
        repeat(20) {
            assertTrue(input.owns(owner))
            assertTrue(input.ownsTouch(110, 120, 120, 1, 2))
            assertEquals(owner, input.epoch)
        }
    }

    private fun started(): MapInputEpoch = MapInputEpoch().also { it.start(100) }

    private fun permitted(): MapInputEpoch = started().also { it.updatePermission(true, 100) }
}
