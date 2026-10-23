package com.beatiq.music.musicme

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.navigation.BeatIQBottomNavBar
import com.beatiq.music.navigation.BeatIQInnerRoutes
import com.beatiq.music.navigation.BottomNavEntry
import com.beatiq.music.renderRoot
import com.beatiq.music.ui.theme.BeatIQTheme
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * MusicMe inside the real shell arrangement (Scaffold + six-tab bar + consumed content insets) on a
 * 360x640dp phone, including a simulated on-screen keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class MusicMeShellLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var previousUserId: String? = null
    private lateinit var vm: MusicMeViewModel

    @Before
    fun setUp() {
        previousUserId = AuthPreferences(app).userId
        AuthPreferences(app).userId = "musicme-shell-${UUID.randomUUID()}"
        vm = MusicMeViewModel(app, SavedStateHandle())
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            BeatIQTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(
                    bottomBar = {
                        BeatIQBottomNavBar(
                            entries = listOf(
                                BottomNavEntry(BeatIQInnerRoutes.HOME, "Home", Icons.Filled.Home, false),
                                BottomNavEntry(BeatIQInnerRoutes.DISCOVER, "Discover", Icons.Filled.Search, false),
                                BottomNavEntry(BeatIQInnerRoutes.LIBRARY, "Library", Icons.Outlined.LibraryMusic, false),
                                BottomNavEntry(BeatIQInnerRoutes.DOWNLOADS, "Downloads", Icons.Outlined.CloudDownload, false),
                                BottomNavEntry(BeatIQInnerRoutes.MUSIC_ME, "MusicMe", Icons.Filled.AutoAwesome, true),
                                BottomNavEntry(BeatIQInnerRoutes.PROFILE, "Profile", Icons.Filled.Person, false),
                            ),
                            onSelect = {},
                            modifier = Modifier.testTag(NAV),
                        )
                    },
                ) { padding ->
                    // Same arrangement as BeatIQNavHost's MainShell.
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { MusicMeRoot(vm) }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun restore() {
        AuthPreferences(app).userId = previousUserId
    }

    @Test
    fun homeHeaderActionsAreLabelledAndCardsShowThereIsMore() {
        val creations = compose.onNode(hasContentDescription("My creations, none saved yet")).assertHasClickAction()
        assertTrue("creations target ${creations.bounds().size}", dp(creations.bounds().width) >= 48f && dp(creations.bounds().height) >= 48f)
        val create = compose.onNode(hasContentDescription("Start a new creation")).assertHasClickAction()
        assertTrue("create target ${create.bounds().size}", dp(create.bounds().width) >= 48f && dp(create.bounds().height) >= 48f)

        // The third card peeks in, and an arrow offers the rest.
        val row = compose.onNodeWithTag(MusicMeTestTags.QUICK_ACTIONS).bounds()
        val third = compose.onNode(hasText("Birthday Song")).fetchSemanticsNode()
        val thirdLeft = third.positionInRoot.x
        val thirdRight = thirdLeft + third.size.width
        assertTrue("third card partly visible ($thirdLeft..$thirdRight vs ${row.right})", thirdLeft < row.right && thirdRight > row.right)
        compose.onNode(hasContentDescription("Show previous ways to start")).assertIsDisplayed().assertIsNotEnabled()
        val more = compose.onNodeWithTag(MusicMeTestTags.QUICK_ACTIONS_MORE).assertIsDisplayed().assertIsEnabled()
        assertTrue("arrows sit above the cards, not over them", more.bounds().bottom <= row.top + 0.5f)
        more.performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Show previous ways to start")).assertIsEnabled()
        screenshot("musicme_shell_home_cards_scrolled.png")

        // Card actions are unchanged.
        compose.onNode(hasContentDescription("Show previous ways to start")).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Create from Text") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(MusicMeDestination.INPUT, vm.state.value.destination)
    }

    @Test
    fun inputFieldsAreCompactReachableAndNeverHiddenByTheActionBarOrNavigation() {
        compose.runOnIdle { vm.newDraft() }
        compose.waitForIdle()
        val nav = compose.onNodeWithTag(NAV).bounds()
        val bar = compose.onNodeWithTag(MusicMeTestTags.INPUT_ACTIONS).assertIsDisplayed().bounds()
        assertTrue("action bar sits above the six-tab bar", bar.bottom <= nav.top + 0.5f)

        val story = compose.onNodeWithTag(MusicMeTestTags.STORY_FIELD).bounds()
        assertTrue("empty story field is compact (${dp(story.height)}dp)", dp(story.height) <= 140f)
        screenshot("musicme_shell_input_empty.png")

        compose.onNodeWithTag(MusicMeTestTags.INPUT_LIST).performScrollToNode(hasText("More details: emotion, pronunciation"))
        compose.onNode(hasText("More details: emotion, pronunciation")).performClick()
        compose.waitForIdle()
        for (tag in listOf(
            MusicMeTestTags.TITLE_FIELD, MusicMeTestTags.RECIPIENT_FIELD, MusicMeTestTags.OCCASION_FIELD,
            MusicMeTestTags.MESSAGE_FIELD, MusicMeTestTags.EMOTION_FIELD, MusicMeTestTags.PRONUNCIATION_FIELD,
        )) {
            compose.onNodeWithTag(MusicMeTestTags.INPUT_LIST).performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            val field = compose.onNodeWithTag(tag).assertIsDisplayed().bounds()
            val actions = compose.onNodeWithTag(MusicMeTestTags.INPUT_ACTIONS).bounds()
            assertTrue("$tag fully above the action bar", field.bottom <= actions.top + 0.5f)
        }
        val message = compose.onNodeWithTag(MusicMeTestTags.MESSAGE_FIELD).bounds()
        assertTrue("empty message field is compact (${dp(message.height)}dp)", dp(message.height) <= 110f)

        // Long text grows the field but stays bounded so the form keeps scrolling naturally.
        compose.onNodeWithTag(MusicMeTestTags.INPUT_LIST).performScrollToNode(hasTestTag(MusicMeTestTags.STORY_FIELD))
        compose.onNodeWithTag(MusicMeTestTags.STORY_FIELD).performTextInput((1..40).joinToString("\n") { "Line $it of our story" })
        compose.waitForIdle()
        val grown = compose.onNodeWithTag(MusicMeTestTags.STORY_FIELD).bounds()
        assertTrue("story grows with content", grown.height > story.height)
        assertTrue("story height stays bounded (${dp(grown.height)}dp)", dp(grown.height) <= 360f)
    }

    @Test
    fun keyboardLiftsTheActionBarExactlyAndFocusedFieldStaysVisible() {
        compose.runOnIdle { vm.newDraft() }
        compose.waitForIdle()
        val imeDp = 280f
        val root = compose.activity.window.decorView
        val imePx = (imeDp * root.resources.displayMetrics.density).toInt()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                root,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag(MusicMeTestTags.INPUT_LIST).performScrollToNode(hasTestTag(MusicMeTestTags.MESSAGE_FIELD))
        compose.onNodeWithTag(MusicMeTestTags.MESSAGE_FIELD).performClick()
        compose.waitForIdle()
        val screenBottom = dp(root.height.toFloat())
        val bar = compose.onNodeWithTag(MusicMeTestTags.INPUT_ACTIONS).assertIsDisplayed().bounds()
        val keyboardTop = screenBottom - imeDp
        assertTrue("action bar above keyboard (${dp(bar.bottom)} <= $keyboardTop)", dp(bar.bottom) <= keyboardTop + 1f)
        assertTrue("no double gap above keyboard (${keyboardTop - dp(bar.bottom)}dp)", keyboardTop - dp(bar.bottom) <= 2f)
        val field = compose.onNodeWithTag(MusicMeTestTags.MESSAGE_FIELD).assertIsDisplayed().bounds()
        assertTrue("focused field above the action bar", field.bottom <= bar.top + 0.5f)
        compose.onNodeWithTag(MusicMeTestTags.MESSAGE_FIELD).performTextInput("Happy 30th, Ada")
        compose.onNode(hasText("Customise") and hasClickAction()).assertIsEnabled()
        screenshot("musicme_shell_input_keyboard.png")
    }

    @Test
    fun customiseButtonFollowsEachEntryPath() {
        compose.runOnIdle { vm.newDraft() }
        compose.waitForIdle()
        val customise = { compose.onNode(hasText("Customise") and hasClickAction()) }
        customise().assertIsNotEnabled()
        compose.onNodeWithTag(MusicMeTestTags.STORY_FIELD).performTextInput("    ")
        customise().assertIsNotEnabled()
        compose.onNodeWithTag(MusicMeTestTags.INPUT_LIST).performScrollToNode(hasTestTag(MusicMeTestTags.TITLE_FIELD))
        compose.onNodeWithTag(MusicMeTestTags.TITLE_FIELD).performTextInput("Thirty")
        customise().assertIsNotEnabled()
        compose.onNodeWithTag(MusicMeTestTags.INPUT_LIST).performScrollToNode(hasTestTag(MusicMeTestTags.MESSAGE_FIELD))
        compose.onNodeWithTag(MusicMeTestTags.MESSAGE_FIELD).performTextInput("Happy 30th, Ada")
        customise().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(MusicMeDestination.GUIDED, vm.state.value.destination)
        assertEquals("Happy 30th, Ada", vm.state.value.draft!!.input.desiredMessage)
        assertEquals("Thirty", vm.state.value.draft!!.input.title)
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun dp(px: Float) = px / compose.density.density

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File("build/reports/ui-screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { renderRoot(compose.activity).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val NAV = "shell_nav"
    }
}
