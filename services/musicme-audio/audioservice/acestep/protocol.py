"""JSON-lines protocol between the audio service and the ACE-Step runner (standard library only).

One JSON object per line, UTF-8, at most MAX_LINE_BYTES. Every message has a "type"; unknown types, unexpected or
missing fields and oversized lines are protocol errors, after which the service kills the runner. Lyrics only ever
travel inside a "render" message on the runner's stdin, never in argv, environment variables or logs.

Service -> runner:  init, render, shutdown
Runner -> service:  hello | fatal (reply to init), progress* then result | error (reply to render)
"""
import json

PROTOCOL_VERSION = 1
MAX_LINE_BYTES = 64 * 1024

# Field name -> allowed Python types. Optional fields are listed separately.
_SCHEMAS = {
    "init": ({"protocol": int, "home": str, "device": str, "manifest": dict}, {}),
    "render": ({"id": str, "params": dict, "out_path": str}, {}),
    "shutdown": ({}, {}),
    "hello": ({"protocol": int, "ok": bool, "code_commit": str, "weights_revision": str, "weights_sha256": dict,
               "config_path": str, "dtype": str, "fp16_fix": str},
              {"selftest": dict, "torch": str, "gpu": (str, type(None)), "load_seconds": (int, float), "python": str}),
    "fatal": ({"code": str}, {"message": str}),
    "progress": ({"id": str, "fraction": (int, float)}, {}),
    "result": ({"id": str, "ok": bool, "sample_rate": int, "channels": int, "frames": int, "clipped_before_pcm": int},
               {"render_ms": int, "peak_vram_gb": (int, float), "diagnostics": dict, "dtype": str}),
    "error": ({"id": str, "code": str}, {"message": str}),
}
RUNNER_TO_SERVICE = ("hello", "fatal", "progress", "result", "error")
SERVICE_TO_RUNNER = ("init", "render", "shutdown")
RENDER_PARAMS = {"caption": str, "lyrics": str, "instrumental": bool, "vocal_language": str, "bpm": (int, type(None)),
                 "duration": int, "seed": int, "inference_steps": int, "timesignature": str}
ERROR_CODES = ("NAN_LATENTS", "GENERATION_FAILED", "INVALID_PARAMS", "OUTPUT_FAILED", "CUDA_OOM")
FATAL_CODES = ("MANIFEST_MISMATCH", "CODE_MISMATCH", "FORBIDDEN_WEIGHTS", "INVALID_MODEL_CONFIG", "SELFTEST_FAILED",
               "FIX_NOT_APPLICABLE", "DOWNLOAD_PATH_CHANGED", "LOAD_FAILED", "PROTOCOL", "UNSUPPORTED_DTYPE")


class ProtocolError(Exception):
    pass


def _check_fields(kind, message):
    required, optional = _SCHEMAS[kind]
    allowed = set(required) | set(optional) | {"type"}
    unknown = set(message) - allowed
    if unknown:
        raise ProtocolError(f"{kind}: unexpected fields {sorted(unknown)}")
    for name, types in required.items():
        if name not in message:
            raise ProtocolError(f"{kind}: missing field {name}")
        if not isinstance(message[name], types) or (types is int and isinstance(message[name], bool)):
            raise ProtocolError(f"{kind}: field {name} has the wrong type")
    for name, types in optional.items():
        if name in message and not isinstance(message[name], types):
            raise ProtocolError(f"{kind}: field {name} has the wrong type")


def validate(message, direction):
    """direction: 'to_runner' or 'from_runner'. Returns the message or raises ProtocolError."""
    if not isinstance(message, dict) or not isinstance(message.get("type"), str):
        raise ProtocolError("message must be a JSON object with a type")
    allowed = SERVICE_TO_RUNNER if direction == "to_runner" else RUNNER_TO_SERVICE
    kind = message["type"]
    if kind not in allowed:
        raise ProtocolError(f"unexpected message type {kind!r}")
    _check_fields(kind, message)
    if kind == "render":
        params = message["params"]
        if set(params) != set(RENDER_PARAMS):
            raise ProtocolError("render: params must contain exactly the supported fields")
        for name, types in RENDER_PARAMS.items():
            if not isinstance(params[name], types) or (types is int and isinstance(params[name], bool)):
                raise ProtocolError(f"render: param {name} has the wrong type")
    if kind == "progress" and not 0 <= message["fraction"] <= 1:
        raise ProtocolError("progress: fraction must be between 0 and 1")
    if kind == "error" and message["code"] not in ERROR_CODES:
        raise ProtocolError("error: unknown code")
    if kind == "fatal" and message["code"] not in FATAL_CODES:
        raise ProtocolError("fatal: unknown code")
    return message


def encode(message, direction):
    validate(message, direction)
    line = (json.dumps(message, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")
    if len(line) > MAX_LINE_BYTES:
        raise ProtocolError("message too large")
    return line


def decode(line, direction):
    if len(line) > MAX_LINE_BYTES:
        raise ProtocolError("line too large")
    try:
        message = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ProtocolError("line is not valid JSON")
    return validate(message, direction)
