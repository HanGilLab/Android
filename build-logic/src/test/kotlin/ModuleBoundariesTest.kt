import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBoundariesTest {
    @Test
    fun `feature can depend on domain and design system`() {
        assertTrue(
            ModuleBoundaries
                .violations(
                    mapOf(
                        ":feature:home" to setOf(":core:domain", ":core:designsystem"),
                        ":feature:course" to setOf(":core:domain", ":core:designsystem"),
                        ":feature:map" to setOf(":core:domain", ":core:designsystem"),
                        ":app" to setOf(":feature:home", ":feature:course", ":feature:map", ":core:database"),
                    ),
                ).isEmpty(),
        )
    }

    @Test
    fun `feature cannot access another feature or implementation`() {
        val violations =
            ModuleBoundaries.violations(
                mapOf(":feature:home" to setOf(":feature:review", ":core:vehicle", ":core:database")),
            )
        assertEquals(3, violations.size)
    }

    @Test
    fun `domain cannot depend on Android adapters`() {
        assertEquals(1, ModuleBoundaries.violations(mapOf(":core:domain" to setOf(":core:vehicle"))).size)
    }

    @Test
    fun `unknown module needs an explicit dependency policy`() {
        assertEquals(1, ModuleBoundaries.violations(mapOf(":core:network" to emptySet())).size)
    }
}
