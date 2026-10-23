"""Request contract musicme.audio.v1: strict parsing of what the app may ask for.

The app sends the final lyric plan (possibly edited by the user) plus structured musical choices. Unknown fields
are refused, so the request can't grow hidden channels (e.g. reference audio) without a contract change.
User lyrics are rendered as written: this module validates them but never rewrites them.
"""
import re

from . import CONTRACT_VERSION
from .errors import ServiceError
from .safety import REFERENCE_AUDIO_MESSAGE, forbidden_fields, screen

SECTION_TYPES = ("intro", "verse", "pre_chorus", "chorus", "hook", "refrain", "bridge", "interlude",
                 "instrumental", "breakdown", "outro")
WORDLESS_TYPES = ("instrumental", "interlude", "breakdown")  # never carry lyrics
VOCAL_TYPES = ("female", "male", "duet", "choir", "none")
MAX_SECTIONS = 16
MAX_SECTION_CHARS = 1200
MAX_SECTION_LINES = 24
MAX_TOTAL_LYRIC_CHARS = 6000
MAX_SEED = 2 ** 31 - 1

_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_TAG = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 &/'’.+-]{0,39}$")  # genres, moods, instruments
_DESCRIPTOR = re.compile(r"^[A-Za-z][A-Za-z -]{0,31}$")  # vocal qualities such as "warm", "raspy"
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")

_TOP_KEYS = {"contract_version", "request"}
_REQUEST_KEYS = {"draft_id", "title", "language", "sections", "style", "vocals", "target_duration_seconds",
                 "takes", "seed", "allow_explicit"}
_SECTION_KEYS = {"id", "type", "label", "lyrics"}
_STYLE_KEYS = {"genres", "moods", "bpm", "instrumentation"}
_VOCAL_KEYS = {"type", "descriptors"}


def _invalid(message):
    return ServiceError(422, "INVALID_REQUEST", message)


def _keys(value, allowed, where):
    if not isinstance(value, dict):
        raise _invalid(f"{where} must be an object.")
    unknown = set(value) - allowed
    if unknown:
        raise _invalid(f"{where} has unsupported fields.")


def _text(value, where, limit, required=True):
    if value is None and not required:
        return ""
    if not isinstance(value, str):
        raise _invalid(f"{where} must be text.")
    text = value.replace("\r\n", "\n").strip()
    if _CONTROL.search(text):
        raise _invalid(f"{where} contains unsupported characters.")
    if required and not text:
        raise _invalid(f"{where} is required.")
    if len(text) > limit:
        raise _invalid(f"{where} is too long.")
    return text


def _tags(value, where, limit, pattern=_TAG):
    if value is None:
        return []
    if not isinstance(value, list) or len(value) > limit:
        raise _invalid(f"{where} must be a list of at most {limit} items.")
    tags = []
    for item in value:
        if not isinstance(item, str) or not pattern.match(item.strip()):
            raise _invalid(f"{where} contains an unsupported value.")
        if item.strip() not in tags:
            tags.append(item.strip())
    return tags


def _int(value, where, low, high, default=None):
    if value is None and default is not None:
        return default
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise _invalid(f"{where} must be a whole number from {low} to {high}.")
    return value


def parse_request(body, settings):
    """Validates a request body; returns the normalised spec. Raises ServiceError."""
    if isinstance(body, dict) and forbidden_fields(body):
        raise ServiceError(422, "POLICY_REJECTED", REFERENCE_AUDIO_MESSAGE)
    _keys(body, _TOP_KEYS, "The request")
    if body.get("contract_version") != CONTRACT_VERSION:
        raise ServiceError(422, "INVALID_REQUEST", "This app version uses an unsupported audio contract.")
    request = body.get("request")
    _keys(request, _REQUEST_KEYS, "The song request")

    draft_id = request.get("draft_id")
    if not isinstance(draft_id, str) or not _ID.match(draft_id):
        raise _invalid("A draft id is required.")
    title = _text(request.get("title"), "The title", 120, required=False)
    language = request.get("language")
    if not isinstance(language, str) or not _ID.match(language):
        raise _invalid("A language is required.")
    if language not in settings.languages:
        raise ServiceError(422, "UNSUPPORTED_LANGUAGE", "Sung vocals aren't available in the chosen language yet.")

    vocals = request.get("vocals")
    _keys(vocals, _VOCAL_KEYS, "Vocals")
    vocal_type = vocals.get("type")  # required: the service never guesses a singer's voice
    if vocal_type not in VOCAL_TYPES:
        raise _invalid("Choose a supported vocal type.")
    descriptors = _tags(vocals.get("descriptors"), "Vocal qualities", 4, _DESCRIPTOR)

    sections = request.get("sections")
    if not isinstance(sections, list) or not 1 <= len(sections) <= MAX_SECTIONS:
        raise _invalid(f"A song needs 1 to {MAX_SECTIONS} sections.")
    parsed, seen, total_chars = [], set(), 0
    for index, section in enumerate(sections):
        _keys(section, _SECTION_KEYS, f"Section {index + 1}")
        sid = section.get("id")
        if not isinstance(sid, str) or not _ID.match(sid) or sid in seen:
            raise _invalid(f"Section {index + 1} needs a unique id.")
        seen.add(sid)
        kind = section.get("type")
        if kind not in SECTION_TYPES:
            raise _invalid(f"Section {index + 1} has an unsupported type.")
        label = _text(section.get("label"), f"Section {index + 1} label", 40)
        lyrics = _text(section.get("lyrics"), f"Section {index + 1} lyrics", MAX_SECTION_CHARS, required=False)
        if lyrics and len(lyrics.splitlines()) > MAX_SECTION_LINES:
            raise _invalid(f"Section {index + 1} has too many lines.")
        if lyrics and kind in WORDLESS_TYPES:
            raise _invalid(f"Section {index + 1} is instrumental and can't have lyrics.")
        if lyrics and vocal_type == "none":
            raise _invalid("An instrumental song can't have lyrics.")
        total_chars += len(lyrics)
        parsed.append({"id": sid, "type": kind, "label": label, "lyrics": lyrics})
    if total_chars > MAX_TOTAL_LYRIC_CHARS:
        raise _invalid("The lyrics are too long for one song.")
    if vocal_type != "none" and not any(s["lyrics"] for s in parsed):
        raise _invalid("Add lyrics to at least one section, or choose an instrumental song.")

    style = request.get("style") or {}
    _keys(style, _STYLE_KEYS, "Style")
    spec = {
        "draft_id": draft_id,
        "title": title,
        "language": language,
        "sections": parsed,
        "style": {
            "genres": _tags(style.get("genres"), "Genres", 3),
            "moods": _tags(style.get("moods"), "Moods", 3),
            "bpm": None if style.get("bpm") is None else _int(style.get("bpm"), "Tempo (BPM)", 50, 200),
            "instrumentation": _tags(style.get("instrumentation"), "Instruments", 8),
        },
        "vocals": {"type": vocal_type, "descriptors": descriptors},
        "target_duration_seconds": _int(request.get("target_duration_seconds"), "Target duration",
                                        settings.min_duration_seconds, settings.max_duration_seconds, default=150),
        "takes": _int(request.get("takes"), "Takes", 1, settings.max_takes, default=1),
        "seed": None if request.get("seed") is None else _int(request.get("seed"), "Seed", 0, MAX_SEED),
        "allow_explicit": request.get("allow_explicit", False) is True,
    }
    if not spec["style"]["genres"]:
        raise _invalid("Choose at least one genre.")

    # The title is screened like lyrics ("Shine Like Gold" is a fine title); only style fields get artist checks.
    lyric_texts = [title] + [s["lyrics"] for s in parsed] + [s["label"] for s in parsed]
    style_texts = spec["style"]["genres"] + spec["style"]["moods"] + spec["style"]["instrumentation"] + descriptors
    rejected = screen(lyric_texts, style_texts)
    if rejected:
        raise ServiceError(422, rejected[0], rejected[1])
    return spec
