package com.beatiq.music.musicme

/**
 * User-facing statements about where MusicMe data lives and what is available today. Keep these
 * factual for the current build: no network calls except a lyric request the user starts and consents
 * to, no audio generation and no promised future features.
 * Storage facts: DataStore drafts are in Android cloud-backup scope; recordings are excluded from
 * cloud backup (data_extraction_rules.xml); both are included in an explicit account backup export.
 */
internal object MusicMeCopy {
    const val STORAGE_BADGE = "Saved on this device"
    const val STORAGE_SUMMARY =
        "Drafts and voice notes are saved in BeatIQ's storage on this device. " +
            "They aren't sent to BeatIQ servers or any AI service unless you choose to generate lyrics."
    const val STORAGE_DETAIL =
        "$STORAGE_SUMMARY Draft text can be included in your Android device backup if it's turned on; " +
            "voice notes are excluded from cloud backup. Both are included when you export an account backup."
    const val DRAFT_SAVED = "Draft saved on this device"

    const val TRANSCRIPTION_TITLE = "Transcription isn't available"
    const val TRANSCRIPTION_BODY =
        "Your voice note is kept on this device and isn't transcribed or uploaded. " +
            "Type anything you'd like in your story in the Write tab."

    const val AI_LYRICS_TITLE = "AI songwriting isn't connected"
    const val AI_LYRICS_BODY = "This build has no songwriting service. Write and arrange lyrics yourself; nothing is sent for AI processing."

    const val SONGWRITING_TITLE = "AI songwriting"
    const val SONGWRITING_BODY = "Writes original lyrics from your story, message and studio choices. Your own and edited sections are kept."
    const val SONGWRITING_CONSENT_TITLE = "Send this song to BeatIQ's songwriter?"
    const val SONGWRITING_CONSENT_BODY =
        "To write lyrics, your story, desired message, dedication, occasion, pronunciation notes, studio choices and any " +
            "lyrics you've written are sent to BeatIQ's songwriting service, linked to your account. Voice recordings aren't sent. " +
            "You'll only be asked once on this account."
    const val SONGWRITING_MOCK_ENGINE =
        "Written by the development mock engine, not a real AI model. Use it to test the flow only."

    const val GENERATION_BUTTON = "Song generation not available"
    const val GENERATION_BODY =
        "This version saves your complete creative brief on this device. " +
            "It doesn't queue a job, show simulated progress or create audio."

    // Availability is determined by the authenticated server-side generation mode and account allowlist.
    const val SONG_BUTTON = "Create song"
    const val SONG_DEV_NOTE = "Your prompt, lyrics and studio choices are sent to BeatIQ's private song service. AI results can vary; review them before use."
    const val SONG_CHECKING = "Checking whether song creation is available…"
    const val SONG_NOT_AUTHORISED = "Song creation is not enabled for this account. Your draft remains on this device."
    const val SONG_NOT_REAL_ENGINE = "The connected song service runs a development test engine that can't create songs."
    const val SONG_UNREACHABLE = "The song service can't be reached right now. Your draft is safe; try again later."
    const val SONG_CONSENT_TITLE = "Send this creative brief to the song service?"
    const val SONG_CONSENT_BODY =
        "To create a song, your title, prompt, lyrics and studio choices (genre, mood, tempo, instruments, voice and length) are sent to " +
            "BeatIQ's song service and fal.ai, linked to your BeatIQ account. Voice recordings aren't sent. AI output may not be unique, " +
            "and commercial or public use is not enabled during internal testing. You'll only be asked once on this account."
    const val SONG_STORAGE =
        "Songs are private to your account and saved in BeatIQ's storage on this device. They aren't included in device backups. " +
            "Download a private copy to keep one elsewhere; do not assume exclusive copyright or unrestricted commercial rights."

    const val HERO_TITLE = "Turn your story into a song brief"
    const val HERO_BODY = "Write or record your moment, then shape genre, mood and lyrics."
}
