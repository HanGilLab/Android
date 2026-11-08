package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal class TestPracticeRepository : PracticeRepository {
    val selection = MutableStateFlow<PracticeSelection?>(null)
    var calls = 0
    var beforeWrite: suspend () -> Unit = {}
    var readCalls = 0
    var read: () -> Flow<PracticeSelection?> = { selection }

    override fun observeSelection(): Flow<PracticeSelection?> =
        flow {
            readCalls++
            emitAll(read())
        }

    override suspend fun replaceSelection(
        expectedCurrent: PracticeSelection?,
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): Boolean {
        calls++
        beforeWrite()
        if (!canCommit() || this.selection.value != expectedCurrent) return false
        this.selection.value = selection
        return true
    }

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
