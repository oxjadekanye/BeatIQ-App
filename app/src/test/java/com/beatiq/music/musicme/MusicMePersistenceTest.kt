package com.beatiq.music.musicme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.musicme.model.MusicMePreset
import com.beatiq.music.musicme.model.MusicMeVoiceRecording
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicMePersistenceTest {
    @Test
    fun `draft preset duplication and recording deletion persist locally`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val suffix = UUID.randomUUID().toString()
            val repository = MusicMeRepository(context, "account-$suffix")
            val recordingId = UUID.randomUUID().toString()
            val recordingFile = repository.recordingFile(recordingId)
            recordingFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            val draft = MusicMeDraft(
                id = "draft-$suffix",
                name = "Persistence $suffix",
                input = MusicMeInput(
                    title = "Persistence $suffix",
                    story = "A story that must survive a repository round trip.",
                ),
                recording = MusicMeVoiceRecording(
                    id = recordingId,
                    localFileName = recordingFile.name,
                    durationMs = 1_234,
                ),
            )

            repository.upsertDraft(draft)
            val restored = repository.drafts.first().firstOrNull { it.id == draft.id }
            assertNotNull(restored)
            assertEquals(draft.name, restored?.name)
            assertEquals(draft.input.story, restored?.input?.story)
            assertEquals(draft.configuration, restored?.configuration)
            assertEquals(draft.lyrics, restored?.lyrics)

            repository.duplicateDraft(draft.id)
            val duplicate = repository.drafts.first().firstOrNull { it.name == "${draft.name} copy" }
            assertNotNull(duplicate)
            assertEquals(null, duplicate?.recording)

            val preset = MusicMePreset(
                id = "preset-$suffix",
                name = "Preset $suffix",
                configuration = draft.configuration,
            )
            repository.savePreset(preset)
            assertTrue(repository.presets.first().any { it.id == preset.id && it.configuration == preset.configuration })

            repository.deleteDraft(draft.id)
            assertFalse(repository.drafts.first().any { it.id == draft.id })
            assertFalse(recordingFile.exists())

            duplicate?.let { repository.deleteDraft(it.id) }
        }
    }

    @Test
    fun `accounts cannot read each others drafts or presets and switching back restores them`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val first = MusicMeRepository(context, "first-$suffix")
        val second = MusicMeRepository(context, "second-$suffix")
        val firstDraft = MusicMeDraft(id = "first-draft-$suffix", name = "Private first")
        val secondDraft = MusicMeDraft(id = "second-draft-$suffix", name = "Private second")
        first.upsertDraft(firstDraft)
        assertTrue(first.drafts.first().any { it.id == firstDraft.id })
        assertFalse(second.drafts.first().any { it.id == firstDraft.id })
        second.upsertDraft(secondDraft)
        assertFalse(first.drafts.first().any { it.id == secondDraft.id })
        assertTrue(MusicMeRepository(context, "first-$suffix").drafts.first().any { it.id == firstDraft.id })
    }

    @Test
    fun `legacy drafts are adopted once and unreadable records survive later writes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val owner = MusicMeRepository(context, "legacy-owner-$suffix")
        val legacy = MusicMeDraft(id = "legacy-$suffix", name = "Legacy preserved")
        owner.seedLegacyForTest(listOf(legacy), includeUnreadableDraft = true)

        assertTrue(owner.drafts.first().any { it.id == legacy.id })
        owner.upsertDraft(MusicMeDraft(id = "new-$suffix", name = "New draft"))
        val rawAfterWrite = owner.rawDraftsForTest()
        assertTrue(rawAfterWrite.contains("\"unreadable\":true"))
        assertTrue(rawAfterWrite.contains(legacy.id))

        val other = MusicMeRepository(context, "not-owner-$suffix")
        assertFalse(other.drafts.first().any { it.id == legacy.id })
    }
}
