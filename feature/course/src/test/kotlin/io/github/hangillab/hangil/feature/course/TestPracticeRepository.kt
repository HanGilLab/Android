package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import kotlinx.coroutines.flow.MutableStateFlow

internal class TestPracticeRepository : PracticeRepository {
    val selection = MutableStateFlow<PracticeSelection?>(null)
    var calls = 0
    var beforeWrite: suspend () -> Unit = {}

    override fun observeSelection() = selection

    override suspend fun saveSelection(
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): Boolean {
        calls++
        beforeWrite()
        if (!canCommit()) return false
        this.selection.value = selection
        return true
    }
}
