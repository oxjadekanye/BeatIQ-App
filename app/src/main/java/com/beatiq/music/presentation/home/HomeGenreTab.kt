package com.beatiq.music.presentation.home

/** Mini genre tabs on the AI Home screen. */
enum class HomeGenreTab(val label: String, val searchTerm: String) {
    GOSPEL("Gospel", "gospel music"),
    AFROBEATS("Afrobeats", "afrobeats"),
    RNB("R&B", "r&b soul"),
    COUNTRY("Country", "country music"),
    HIP_HOP("Hip-Hop", "hip hop"),
    INSTRUMENTAL("Instrumental", "instrumental"),
    JAZZ("Jazz", "jazz"),
    PODCAST("Podcast", "music podcast"),
    POP("Pop", "pop hits"),
    INDIAN("Indian", "indian music"),
    SPANISH("Spanish", "latin spanish music"),
    ROCK("Rock", "rock"),
    LATIN("Latin", "latin reggaeton"),
    ELECTRONIC("Electronic", "electronic dance"),
    ;

    companion object {
        val all: List<HomeGenreTab> = entries
    }
}
