package local.files.explorer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import local.files.explorer.core.ChangelogEntry
import local.files.explorer.ui.settings.AboutContent
import local.files.explorer.ui.settings.Licences
import local.files.explorer.ui.settings.LicencesContent
import local.files.explorer.ui.settings.LicenceDetailContent
import local.files.explorer.ui.settings.LicenceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h1400dp-xhdpi")
class AboutLicencesUiTest {
    @get:Rule val compose = createComposeRule()

    private val log = listOf(
        ChangelogEntry("0.4.5", "2026-11-19", listOf("Newest change")),
        ChangelogEntry("0.4.4", "2026-11-17", listOf("Older change")),
        ChangelogEntry("0.4.3", null, listOf("Oldest change", "Another")),
    )

    @Test fun latestExpandedOlderCollapsedTapToExpand() {
        compose.setContent { AboutContent("0.4.5", log, 10) }
        compose.onNodeWithText("Newest change").assertIsDisplayed()
        compose.onNodeWithText("Older change").assertDoesNotExist()
        compose.onNodeWithText("2 changes").assertIsDisplayed()
        compose.onNodeWithTag("changelog_0.4.4").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Older change").assertIsDisplayed()
        // Collapsing the latest works too.
        compose.onNodeWithTag("changelog_0.4.5").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Newest change").assertDoesNotExist()
    }

    @Test fun aboutLinksToLicencesAndNoLongerListsThemInline() {
        var opened = 0
        compose.setContent { AboutContent("0.4.5", log, 10, onLicences = { opened++ }) }
        compose.onNodeWithText("Root support uses libsu by topjohnwu (Apache 2.0).").assertDoesNotExist()
        compose.onNodeWithTag("openLicences").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun licencesPageIsACompactListWithoutFullTexts() {
        compose.setContent { LicencesContent() }
        Licences.all.forEach { compose.onNodeWithText(it.title).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("XZ for Java 1.10").assertIsDisplayed()
        compose.onNodeWithText("BSD Zero Clause (0BSD)").assertIsDisplayed()
        compose.onAllNodesWithTag("licenceText").assertCountEquals(0)
    }

    @Test fun tappingAComponentOpensItsOwnPageAndBackReturnsToTheList() {
        var open by mutableStateOf<LicenceInfo?>(null)
        compose.setContent {
            val o = open
            if (o == null) LicencesContent(onOpen = { open = it })
            else LicenceDetailContent(o, { "LICENCE TEXT FOR $it" })
        }
        compose.onNodeWithTag("licence_XZ for Java").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("licenceDetail").assertIsDisplayed()
        compose.onNodeWithTag("licenceText").assertTextEquals("LICENCE TEXT FOR 0bsd.txt")
        compose.onAllNodesWithTag("licenceNotice").assertCountEquals(0)
        compose.onAllNodesWithTag("licencesPage").assertCountEquals(0)
        open = null // what Back does
        compose.waitForIdle()
        compose.onNodeWithTag("licencesPage").assertIsDisplayed()
        // Apache Commons shows its NOTICE as well as the Apache licence.
        compose.onNodeWithTag("licence_Apache Commons Compress, IO, Lang and Codec").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("licenceNotice").assertTextEquals("LICENCE TEXT FOR apache-commons-NOTICE.txt")
        compose.onNodeWithTag("licenceText").assertTextEquals("LICENCE TEXT FOR apache-2.0.txt")
    }

    @Test fun appItselfIsListedFirstUnderApache() {
        val first = Licences.all.first()
        assertEquals("File Manager", first.name)
        assertEquals("apache-2.0.txt", first.licenceFile)
    }

    @Test fun everyLicenceFileIsBundled() {
        val dir = listOf(File("src/main/assets/licenses"), File("app/src/main/assets/licenses")).first { it.exists() }
        Licences.all.forEach { l ->
            listOfNotNull(l.licenceFile, l.noticeFile).forEach { assertTrue(it, File(dir, it).readText().length > 200) }
        }
        assertTrue(File(dir, "apache-2.0.txt").readText().contains("Apache License"))
    }
}
