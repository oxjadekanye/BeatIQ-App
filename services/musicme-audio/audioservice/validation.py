"""Automated checks on a rendered file before anyone can hear it.

These are technical gates only (format, length, silence, clipping). They say nothing about musical quality,
lyric intelligibility or originality: those need ASR checks and human listening (Phase 2.3A §3.7), which arrive
with a real model.
"""
import array
import operator
import os
import struct
import sys
import wave

SAMPLE_RATES = (44100, 48000)
SILENCE_RMS = 100  # 16-bit PCM; about -50 dBFS
MAX_CLIPPED_FRACTION = 0.01


class AudioValidationError(Exception):
    def __init__(self, code, message):
        super().__init__(code)
        self.code = code
        self.message = message


def validate_wav(path, target_seconds, tolerance, max_bytes):
    """Returns {duration_ms, sample_rate, channels} or raises AudioValidationError."""
    size = os.path.getsize(path) if os.path.exists(path) else 0
    if size <= 44:
        raise AudioValidationError("AUDIO_INVALID", "The render produced no audio.")
    if size > max_bytes:
        raise AudioValidationError("AUDIO_INVALID", "The rendered file is too large.")
    try:
        with wave.open(path, "rb") as w:
            channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
            if width != 2 or channels not in (1, 2) or rate not in SAMPLE_RATES:
                raise AudioValidationError("AUDIO_INVALID", "The render isn't 16-bit PCM at 44.1 or 48 kHz.")
            if frames <= 0:
                raise AudioValidationError("AUDIO_INVALID", "The render produced no audio.")
            duration = frames / rate
            low, high = target_seconds * (1 - tolerance), target_seconds * (1 + tolerance)
            if not low <= duration <= high:
                raise AudioValidationError("AUDIO_DURATION", "The song length was outside the accepted range.")
            peak_count = total = 0
            sum_squares = 0.0
            chunk = rate * channels  # about one second per read
            while True:
                data = w.readframes(chunk // channels)
                if not data:
                    break
                samples = array.array("h")
                samples.frombytes(data[: len(data) - len(data) % 2])
                if sys.byteorder == "big":
                    samples.byteswap()
                sum_squares += sum(map(operator.mul, samples, samples))
                peak_count += samples.count(32767) + samples.count(-32768)
                total += len(samples)
    except AudioValidationError:
        raise
    except (wave.Error, EOFError, OSError, RuntimeError, ValueError, struct.error):  # wave raises all of these on malformed files
        raise AudioValidationError("AUDIO_INVALID", "The render isn't a readable WAV file.")
    if total == 0 or (sum_squares / total) ** 0.5 < SILENCE_RMS:
        raise AudioValidationError("AUDIO_SILENT", "The render was silent.")
    if peak_count / total > MAX_CLIPPED_FRACTION:
        raise AudioValidationError("AUDIO_CLIPPED", "The render was badly clipped.")
    return {"duration_ms": int(duration * 1000), "sample_rate": rate, "channels": channels}
