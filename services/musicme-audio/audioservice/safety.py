"""Request screening for audio generation.

Copyright and voice-imitation rules mirror the songwriting service (an independent copy, so the services share
no code). On top of those, audio adds:
* no reference audio, voice samples, covers, timbre/speaker conditioning or voice LoRAs: such fields are refused
  by name before any other validation, so a client can't smuggle them in under the generic error;
* no "in the style of <artist>" style or vocal prompts.
"""
import re

_NAME = r"(?:[A-Z][\w'’.-]+(?:\s+[A-Z][\w'’.-]+){0,3})"

COPYRIGHT_PATTERNS = [
    re.compile(r"\b(copy|reproduce|use|paste|quote|write out|give me|include)\b[^.\n]{0,40}\b(exact|original|actual|real|full|same)\b[^.\n]{0,20}\b(lyrics|words|verses?|chorus)\b", re.I),
    re.compile(r"\b(the|its|their)\s+(exact|original|actual|real|full)\s+lyrics\b", re.I),
    re.compile(r"\blyrics\s+(to|of|from)\s+[\"“'‘]", re.I),
    re.compile(r"\bsame\s+lyrics\s+as\b", re.I),
    re.compile(r"\bword[\s-]for[\s-]word\b", re.I),
]

VOICE_PATTERNS = [
    re.compile(r"\b(?i:(?:in|with|using)\s+(?:the\s+)?(?:exact\s+)?voice\s+of)\s+" + _NAME),
    re.compile(r"\b(?i:(?:sound|sing|sings|sounding|singing)\s+(?:exactly\s+|just\s+)?like)\s+" + _NAME),
    re.compile(r"\b(?i:(?:clone|copy|imitate|mimic|replicate|impersonate)\s+(?:the\s+)?(?:voice|vocals|singing)\s+of)\s+" + _NAME),
    re.compile(r"\b(?i:clone|copy|imitate|mimic|replicate|impersonate)\s+" + _NAME + r"(?i:'s|’s)\s+(?i:voice|vocals)"),
    re.compile(r"\b(voice[\s-]?clone|deepfake)\b", re.I),
]

# Applied to style and vocal fields only: lyrics may legitimately say "like Ada".
ARTIST_STYLE_PATTERNS = [
    re.compile(r"\b(?i:(?:in\s+the\s+)?style\s+of)\s+\S", re.I),
    re.compile(r"\b(?i:like|similar\s+to|inspired\s+by|a\s+la|à\s+la)\s+" + _NAME),
    re.compile(r"\b(cover|remake|remix)\b", re.I),
]

# Field names that would condition the model on someone's voice or recording. Never accepted.
FORBIDDEN_FIELDS = frozenset({
    "reference_audio", "reference_audio_url", "reference", "audio", "audio_url", "audio_base64", "source_audio",
    "voice_sample", "voice_samples", "voice_clone", "voice_id", "clone", "timbre", "timbre_reference", "speaker",
    "speaker_embedding", "cover", "cover_of", "cover_song", "lora", "lora_id", "adapter", "artist", "artist_voice",
    "in_the_style_of", "recording", "recording_id", "upload", "upload_id", "melody_audio", "vocal_reference",
})

COPYRIGHT_MESSAGE = ("MusicMe creates original songs and can't reproduce an existing song's words. "
                     "Describe the feeling or message you want instead.")
VOICE_MESSAGE = ("MusicMe can't imitate a specific artist's voice. Describe the vocal qualities instead, "
                 "for example \"warm, raspy baritone\".")
ARTIST_STYLE_MESSAGE = ("MusicMe can't copy a specific artist or song. Describe the genre, mood and instruments "
                        "you want instead.")
REFERENCE_AUDIO_MESSAGE = ("MusicMe doesn't accept reference recordings, covers or voice cloning. "
                           "Choose a vocal style instead.")


def forbidden_fields(value, _depth=0):
    """Returns the sorted forbidden field names found anywhere in a JSON value."""
    found = set()
    if _depth > 6:
        return found
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str) and key.strip().lower() in FORBIDDEN_FIELDS:
                found.add(key.strip().lower())
            found |= forbidden_fields(child, _depth + 1)
    elif isinstance(value, list):
        for child in value:
            found |= forbidden_fields(child, _depth + 1)
    return found


def screen(lyric_texts, style_texts):
    """Returns (code, message) for the first policy problem, else None."""
    for text in list(lyric_texts) + list(style_texts):
        if not text:
            continue
        if any(p.search(text) for p in COPYRIGHT_PATTERNS):
            return "POLICY_REJECTED", COPYRIGHT_MESSAGE
        if any(p.search(text) for p in VOICE_PATTERNS):
            return "POLICY_REJECTED", VOICE_MESSAGE
    for text in style_texts:
        if text and any(p.search(text) for p in ARTIST_STYLE_PATTERNS):
            return "POLICY_REJECTED", ARTIST_STYLE_MESSAGE
    return None
