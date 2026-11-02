package io.github.hangillab.hangil

import androidx.lifecycle.ViewModel
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Navigation memory survives configuration changes, never process recreation. */
class FinishedReviewNavigationViewModel : ViewModel() {
    private val current = MutableStateFlow<Entry?>(null)
    val entry = current.asStateFlow()

    fun open(source: PracticeSession): String? {
        if (source.isActive) return null
        val next = Entry(UUID.randomUUID().toString(), source)
        current.value = next
        return next.token
    }

    fun clear(token: String?) {
        val expected = current.value ?: return
        if (expected.token == token) current.compareAndSet(expected, null)
    }

    data class Entry(
        val token: String,
        val source: PracticeSession,
    )
}
