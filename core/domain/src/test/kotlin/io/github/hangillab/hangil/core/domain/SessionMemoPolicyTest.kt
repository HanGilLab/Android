package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoPolicyTest {
    @Test fun `basic text accepts the exact code point limit but not one extra`() {
        assertTrue(SessionMemoPolicy.isValidText("가".repeat(280)))
        assertFalse(SessionMemoPolicy.isValidText("가".repeat(281)))
        assertEquals(280, SessionMemoPolicy.countCodePoints("가".repeat(280)))
    }

    @Test fun `supplementary characters count once rather than by UTF16 storage units`() {
        val maximum = "🙂".repeat(280)
        assertEquals(560, maximum.length)
        assertEquals(280, SessionMemoPolicy.countCodePoints(maximum))
        assertTrue(SessionMemoPolicy.isValidText(maximum))
        assertFalse(SessionMemoPolicy.isValidText(maximum + "🙂"))
    }

    @Test fun `combining marks are separate code points and the limit is not a grapheme count`() {
        val maximum = "e\u0301".repeat(140)
        assertEquals(280, SessionMemoPolicy.countCodePoints(maximum))
        assertTrue(SessionMemoPolicy.isValidText(maximum))
        assertFalse(SessionMemoPolicy.isValidText(maximum + "e\u0301"))
    }

    @Test fun `blank values and embedded NUL are rejected without treating them as deletion`() {
        for (invalid in listOf("", " \n\t\r", "\u0000memo", "me\u0000mo", "memo\u0000")) {
            assertFalse(SessionMemoPolicy.isValidText(invalid))
        }
    }

    @Test fun `isolated surrogates are invalid while the input counter still remains defined`() {
        for (invalid in listOf("\uD800", "\uDC00", "memo\uD800", "\uDC00memo", "\uD800\uD800", "\uDC00\uDC00")) {
            assertFalse(SessionMemoPolicy.isValidText(invalid))
            assertTrue(SessionMemoPolicy.countCodePoints(invalid) > 0)
        }
        assertEquals(1, SessionMemoPolicy.countCodePoints("\uD83D\uDE42"))
        assertTrue(SessionMemoPolicy.isValidText("\uD83D\uDE42"))
    }

    @Test fun `line breaks spaces and mixed scripts stay valid within the same code point boundary`() {
        val text = " \n기억할 점\nPractice 🙂\t "
        assertTrue(SessionMemoPolicy.isValidText(text))
        assertEquals(text.codePointCount(0, text.length), SessionMemoPolicy.countCodePoints(text))
        assertTrue(SessionMemoPolicy.isValidText("x".repeat(279) + "\n"))
        assertFalse(SessionMemoPolicy.isValidText("x".repeat(280) + "\n"))
    }
}
