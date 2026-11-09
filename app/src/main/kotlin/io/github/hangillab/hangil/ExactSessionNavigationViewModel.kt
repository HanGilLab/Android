package io.github.hangillab.hangil

import androidx.lifecycle.ViewModel
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Keeps an exact finished source in memory across configuration changes. */
class ExactSessionNavigationViewModel : ViewModel() {
    private val current = MutableStateFlow<Entry?>(null)
    val entry = current.asStateFlow()

    fun open(source: PracticeSession): String? {
        if (source.isActive) return null
        val next = Entry(UUID.randomUUID().toString(), source)
        current.value = next
        return next.token
    }

    fun source(token: String?): PracticeSession? = current.value?.source(token)

    fun clear(token: String?) {
        val expected = current.value ?: return
        if (expected.token == token) current.compareAndSet(expected, null)
    }

    data class Entry(
        val token: String,
        val source: PracticeSession,
    ) {
        fun source(token: String?): PracticeSession? = source.takeIf { this.token == token }
    }

    companion object {
        const val LOCATION_HISTORY_KEY = "location-history"
    }
}
