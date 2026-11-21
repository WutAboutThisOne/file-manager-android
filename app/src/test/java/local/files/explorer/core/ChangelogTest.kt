package local.files.explorer.core

import local.files.explorer.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {
    @Test fun parsesHeadersBulletsAndContinuations() {
        val log = Changelog.parse(
            """
            # comment line
            ## 1.2.0 — 2026-12-01
            - New thing
              that wraps
            - Second
            Some prose that is ignored.
            ## v1.1.0
            - Old
            """.trimIndent()
        )
        assertEquals(listOf("1.2.0", "1.1.0"), log.map { it.version })
        assertEquals("2026-12-01", log[0].date)
        assertEquals(listOf("New thing that wraps", "Second"), log[0].items)
        assertNull(log[1].date)
    }

    @Test fun bundledChangelogStartsWithThisVersionAndKeepsHistory() {
        val f = listOf(File("src/main/assets/CHANGELOG.md"), File("app/src/main/assets/CHANGELOG.md")).first { it.exists() }
        val log = Changelog.parse(f.readText())
        assertEquals(BuildConfig.VERSION_NAME, log.first().version)
        assertEquals("0.5.0", log[0].version)
        assertEquals("0.1.0", log.last().version)
        assertTrue(log.all { it.items.isNotEmpty() })
        assertTrue(log.size >= 11)
        assertTrue(log[0].items.any { it.contains("Renamed") })
        assertTrue(log[0].items.any { it.contains("Licences") })
        assertEquals("0.4.5", log[1].version)
        assertTrue(log[1].items.any { it.contains("Fold") })
        assertTrue(log[1].items.any { it.contains("API 35") })
        assertTrue(log.any { it.version == "0.4.4" && it.items.any { item -> item.contains("changelog", ignoreCase = true) } })
        assertTrue(log.any { it.version == "0.4.3" && it.items.any { item -> item.contains(".nomedia") } })
    }
}
