"""Original MusicMe production descriptors. They describe musical traits, never living artists or songs."""

GENRE_PRESETS = {
    "afrobeats": "contemporary Nigerian Afrobeats, polished syncopated percussion, warm melodic bassline, memorable hook, expressive arrangement",
    "afro-pop": "contemporary Afropop, bright melodic hook, crisp layered percussion, warm bass, polished vocal-forward arrangement",
    "afropop": "contemporary Afropop, bright melodic hook, crisp layered percussion, warm bass, polished vocal-forward arrangement",
    "amapiano": "Amapiano, resonant log drums, deep bass, atmospheric chords, spacious groove, danceable rhythmic progression",
    "afro-fusion": "Afro-fusion, West African rhythmic foundation, contemporary soul harmony, organic percussion, expressive dynamic arrangement",
    "afro-r&b": "Afro-R&B, intimate vocals, warm electric keys, restrained syncopated percussion, rich bass, spacious modern mix",
    "highlife": "highlife-inspired, interlocking guitars, buoyant bass, live percussion, horn accents, flowing melodic phrasing",
    "gospel": "Nigerian contemporary gospel, uplifting harmony, call-and-response, live drums, expressive choir layers, reverent build",
    "r&b": "contemporary R&B, expressive vocals, rich harmony, warm bass, polished drums, spacious modern production",
    "pop": "contemporary pop, clear melodic hook, focused verse-to-chorus lift, polished rhythm section, balanced arrangement",
    "hip-hop": "contemporary hip-hop, focused rhythmic pocket, strong low end, detailed percussion, original melodic motif",
    "dancehall": "contemporary dancehall, syncopated Caribbean groove, deep bass, bright percussion, memorable vocal hook",
}


def production_prompt(caption, genres):
    additions = []
    for genre in genres:
        normal = genre.strip().lower()
        for key, value in GENRE_PRESETS.items():
            if key in normal and value not in additions:
                additions.append(value)
    base = caption.strip()
    quality = "coherent full-song arrangement, clean dynamics, no clipping, original melody, no artist imitation"
    return ", ".join(part for part in [base, *additions[:2], quality] if part)
