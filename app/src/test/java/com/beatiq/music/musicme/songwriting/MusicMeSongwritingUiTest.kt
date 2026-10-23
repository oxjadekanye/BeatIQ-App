package com.beatiq.music.musicme.songwriting

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.musicme.MusicMeCopy
import com.beatiq.music.musicme.MusicMeDestination
import com.beatiq.music.musicme.MusicMeRoot
import com.beatiq.music.musicme.MusicMeTestTags
import com.beatiq.music.musicme.MusicMeViewModel
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.renderRoot
import com.beatiq.music.ui.theme.BeatIQTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h900dp-xxhdpi")
class MusicMeSongwritingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var previousUser: String? = null
    private lateinit var account: String

    @Before
    fun setUp() {
        previousUser = AuthPreferences(app).userId
        account = "sw-ui-${UUID.randomUUID()}"
        AuthPreferences(app).userId = account
        SongwritingDependencies.sleep = { }
        SongwritingDependencies.log = SongwritingLog { }
    }

    @After
    fun tearDown() {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        SongwritingDependencies.reset()
        AuthPreferences(app).userId = previousUser
    }

    private fun showLyrics(): MusicMeViewModel {
        val draft = storyDraft()
        runBlocking { MusicMeRepository(app, account).upsertDraft(draft) }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        compose.setContent {
            BeatIQTheme(darkTheme = true, dynamicColor = false) {
                Surface { Box(Modifier.requiredSize(360.dp, 800.dp)) { MusicMeRoot(vm) } }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.drafts.isNotEmpty() }
        compose.runOnIdle { vm.openDraft(draft.id, MusicMeDestination.LYRICS) }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun `without a service the studio says so plainly and offers no AI controls`() {
        SongwritingDependencies.serviceFactory = { UnconfiguredSongwritingService }
        showLyrics()
        compose.onNode(hasText(MusicMeCopy.AI_LYRICS_TITLE)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Generate lyrics").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription("Rewrite", substring = true)).fetchSemanticsNodes().isEmpty())
        screenshot("musicme_lyrics_not_connected.png")
    }

    @Test
    fun `consent, generation progress, results and per-section controls`() {
        val fake = FakeSongwritingService()
        SongwritingDependencies.serviceFactory = { fake }
        val vm = showLyrics()

        compose.onNodeWithTag(MusicMeTestTags.GENERATE_LYRICS).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(MusicMeCopy.SONGWRITING_CONSENT_TITLE).fetchSemanticsNodes().isNotEmpty() }
        screenshot("musicme_lyrics_consent.png")
        compose.onNode(hasText("Send and write")).performClick()
        val deadline = System.currentTimeMillis() + 10_000
        fun finished(): Boolean {
            val sw = vm.state.value.songwriting
            return sw.error != null || (!sw.running && vm.state.value.draft!!.lyrics.sections.any { it.lyrics.isNotBlank() })
        }
        while (!finished() && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(25)
        }
        assertTrue("did not finish: ${vm.state.value.songwriting} submits=${fake.submits.size}", finished())
        compose.waitForIdle()
        org.junit.Assert.assertNull("songwriting state: ${vm.state.value.songwriting}", vm.state.value.songwriting.error)

        compose.onNode(hasText("Rewrite unedited sections")).assertIsDisplayed()
        compose.onNode(hasText("Show song plan", substring = true)).assertIsDisplayed()
        val firstVerse = vm.state.value.draft!!.lyrics.sections.first { it.label == "Verse 1" }
        compose.onNodeWithTag("musicme_lyrics_list").performScrollToNode(hasText("AI draft"))
        compose.onNode(hasContentDescription("Rewrite Verse 1 with AI")).assertExists()
        assertTrue(firstVerse.lyrics.contains("harbour"))
        screenshot("musicme_lyrics_generated.png")
    }

    @Test
    fun `mock engine output is labelled as mock everywhere and never as AI`() {
        val fake = FakeSongwritingService(capabilitiesResult = testCapabilities.copy(engine = "mock", modelId = "MOCK-not-an-ai-model"))
        fake.planFor = { spec -> groundedPlan(spec).let { it.copy(metadata = it.metadata.copy(engine = "mock", modelId = "MOCK-not-an-ai-model")) } }
        SongwritingDependencies.serviceFactory = { fake }
        runBlocking { MusicMeRepository(app, account).setSongwritingConsent(true) }
        val vm = showLyrics()
        compose.onNodeWithTag(MusicMeTestTags.GENERATE_LYRICS).performClick()
        val deadline = System.currentTimeMillis() + 10_000
        while ((vm.state.value.songwriting.running || vm.state.value.draft!!.lyrics.sections.all { it.lyrics.isBlank() }) && System.currentTimeMillis() < deadline) {
            compose.waitForIdle(); Thread.sleep(25)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(MusicMeTestTags.MOCK_ENGINE_BANNER).assertIsDisplayed()
        compose.onNodeWithTag("musicme_lyrics_list").performScrollToNode(hasText("Mock placeholder (not AI)"))
        assertTrue(compose.onAllNodesWithText("AI draft").fetchSemanticsNodes().isEmpty())
        assertTrue(vm.state.value.draft!!.songwriting!!.lastPlan!!.engine == "mock")
        screenshot("musicme_lyrics_mock_engine.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File("build/reports/ui-screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { renderRoot(compose.activity).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
