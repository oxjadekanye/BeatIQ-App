package com.beatiq.music.musicme

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.renderRoot
import com.beatiq.music.ui.theme.BeatIQTheme
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

/** MusicMe Home and Input Studio on a compact 360x640dp phone at default and enlarged font sizes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h900dp-xxhdpi")
class MusicMeScreenLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var previousUserId: String? = null

    @Before
    fun signIn() {
        previousUserId = AuthPreferences(app).userId
        AuthPreferences(app).userId = "musicme-layout-${UUID.randomUUID()}"
    }

    @After
    fun restore() {
        AuthPreferences(app).userId = previousUserId
    }

    @Test
    fun homeHeroIsCompactAndPrimaryActionVisibleWithoutScrolling() {
        val vm = MusicMeViewModel(app, SavedStateHandle())
        var fontScale by mutableStateOf(1f)
        show(vm) { fontScale }

        for (scale in listOf(1f, 1.3f)) {
            fontScale = scale
            compose.waitForIdle()
            val hero = compose.onNodeWithTag(MusicMeTestTags.HERO).fetchSemanticsNode().boundsInRoot
            val heroHeightDp = hero.height / compose.density.density
            val limit = if (scale == 1f) 230f else 290f
            assertTrue("Hero height $heroHeightDp dp at x$scale", heroHeightDp <= limit)
            assertTrue("Hero bottom within first screen", hero.bottom / compose.density.density < 340f)
            compose.onNode(hasText("Create") and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText("Start your session")).assertIsDisplayed()
            screenshot("musicme_home_360x640_x$scale.png")
        }
    }

    @Test
    fun inputStudioKeepsSaveAndContinueReachableWithoutScrolling() {
        val vm = MusicMeViewModel(app, SavedStateHandle())
        var fontScale by mutableStateOf(1f)
        show(vm) { fontScale }
        compose.runOnIdle { vm.newDraft(occasionId = "birthday") }

        for (scale in listOf(1f, 1.3f)) {
            fontScale = scale
            compose.waitForIdle()
            val bar = compose.onNodeWithTag(MusicMeTestTags.INPUT_ACTIONS).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Action bar ends within the 640dp viewport", bar.bottom / compose.density.density <= 640.5f)
            compose.onNode(hasText("Customise") and hasClickAction()).assertIsDisplayed().assertIsNotEnabled()
            compose.onNode(hasText("Save") and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithTag(MusicMeTestTags.STORY_FIELD).assertIsDisplayed()
            screenshot("musicme_input_360x640_x$scale.png")
        }

        compose.onNodeWithTag(MusicMeTestTags.STORY_FIELD).performTextInput("Ada turns thirty on Saturday")
        compose.onNode(hasText("Customise") and hasClickAction()).assertIsEnabled()
        screenshot("musicme_input_with_story_360x640.png")
    }

    private fun show(vm: MusicMeViewModel, fontScale: () -> Float) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale())) {
                BeatIQTheme(darkTheme = true, dynamicColor = false) {
                    androidx.compose.material3.Surface {
                        Box(Modifier.requiredSize(360.dp, 640.dp)) { MusicMeRoot(vm) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File("build/reports/ui-screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { renderRoot(compose.activity).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
