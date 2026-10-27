package io.github.hangillab.hangil.core.database

import androidx.room.Room
import io.github.hangillab.hangil.core.domain.PracticeSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPracticeRepositoryTest {
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")

    private fun open(name: String) =
        Room.databaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java, name).build()

    @Test fun `selection survives database close and reopen with all versions`() =
        runBlocking {
            val name = "restore-test.db"
            RuntimeEnvironment.getApplication().deleteDatabase(name)
            open(name).useDatabase { assertTrue(RoomPracticeRepository(it).saveSelection(selection) { true }) }
            open(name).useDatabase { assertEquals(selection, RoomPracticeRepository(it).observeSelection().first()) }
        }

    @Test fun `replacement updates the singleton selection atomically`() =
        runBlocking {
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build().useDatabase {
                val repository = RoomPracticeRepository(it)
                repository.saveSelection(selection) { true }
                val updated =
                    selection.copy(
                        goalId = "shopping",
                        courseId = "intersections",
                        courseVersion = 4,
                        briefingVersion = 5,
                        taskId = "scene",
                    )
                repository.saveSelection(updated) { true }
                assertEquals(updated, repository.observeSelection().first())
            }
        }

    @Test fun `permission lost before or during write preserves previous selection`() =
        runBlocking {
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build().useDatabase {
                val repository = RoomPracticeRepository(it)
                repository.saveSelection(selection) { true }
                assertFalse(repository.saveSelection(selection.copy(taskId = "arrival")) { false })
                var checks = 0
                assertFalse(repository.saveSelection(selection.copy(taskId = "arrival")) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(selection, repository.observeSelection().first())
            }
        }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
