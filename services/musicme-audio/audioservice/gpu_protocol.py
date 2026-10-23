"""MusicMe GPU job protocol (musicme.gpu-job.v1): provider-neutral HTTP between the audio service and a GPU worker.

The same worker (audioservice/gpu_worker) runs on any GPU host: Modal (serverless, scale to zero), a RunPod or Vast.ai
container, or a rented VM. Only the URL and the credentials change. Every request is short (well under Modal's
150-second web request limit); long renders are polled.

    GET    /v1/health                  -> {"protocol", "state": "starting"|"ready"|"failed"|"draining", "busy"}
    GET    /v1/hello                   -> the runner's verified handshake (503 + Retry-After while starting)
    POST   /v1/jobs                    {"job_id", "params", "deadline_seconds"} -> 202 job (idempotent per job_id)
    GET    /v1/jobs/{job_id}           -> job
    GET    /v1/jobs/{job_id}/audio     -> audio/wav (succeeded jobs only; X-Content-SHA256 header)
    POST   /v1/jobs/{job_id}/cancel    -> job
    DELETE /v1/jobs/{job_id}           -> 204 (removes the output now; outputs also expire after a TTL)

    job = {"job_id", "state": queued|running|succeeded|failed|cancelled, "progress": 0..1,
           "result": {...runner result fields...} | null, "error": {"code"} | null}

Every request carries `Authorization: Bearer <worker token>`; providers may add their own gateway auth headers
(e.g. Modal proxy auth) in front. Lyrics travel only inside `params`; neither side logs them.
"""
import re

PROTOCOL = "musicme.gpu-job.v1"
JOB_ID = re.compile(r"^[0-9a-f]{32}$")
STATES = ("queued", "running", "succeeded", "failed", "cancelled")
FINAL_STATES = ("succeeded", "failed", "cancelled")
WORKER_STATES = ("starting", "ready", "failed", "draining")
MAX_BODY_BYTES = 64 * 1024
MAX_JSON_BYTES = 256 * 1024
RESULT_KEYS = {"sample_rate", "channels", "frames", "clipped_before_pcm", "render_ms", "peak_vram_gb", "dtype",
               "diagnostics", "sha256", "size_bytes"}
RESULT_REQUIRED = {"sample_rate", "channels", "frames", "clipped_before_pcm", "render_ms", "dtype", "diagnostics",
                   "sha256", "size_bytes"}


class ProtocolViolation(ValueError):
    pass


def check_job(job, job_id):
    """Validates a worker's job document strictly; anything unexpected is a protocol violation."""
    if not isinstance(job, dict) or set(job) - {"job_id", "state", "progress", "result", "error"}:
        raise ProtocolViolation("job shape")
    if job.get("job_id") != job_id or job.get("state") not in STATES:
        raise ProtocolViolation("job identity")
    progress = job.get("progress", 0)
    if isinstance(progress, bool) or not isinstance(progress, (int, float)) or not 0 <= progress <= 1:
        raise ProtocolViolation("progress")
    result, error = job.get("result"), job.get("error")
    if job["state"] == "succeeded":
        if (not isinstance(result, dict) or set(result) - RESULT_KEYS
                or not RESULT_REQUIRED.issubset(result)):
            raise ProtocolViolation("result")
        for name in ("sample_rate", "channels", "frames", "clipped_before_pcm", "render_ms", "size_bytes"):
            if isinstance(result.get(name), bool) or not isinstance(result.get(name), int) or result[name] < 0:
                raise ProtocolViolation("result numbers")
        if result["sample_rate"] not in (44100, 48000) or result["channels"] not in (1, 2) or result["frames"] <= 0:
            raise ProtocolViolation("result audio")
        if not isinstance(result.get("dtype"), str) or not isinstance(result.get("diagnostics"), dict):
            raise ProtocolViolation("result details")
        if not isinstance(result.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", result["sha256"]):
            raise ProtocolViolation("result digest")
        if not isinstance(result.get("size_bytes"), int) or result["size_bytes"] <= 44:
            raise ProtocolViolation("result size")
    elif result is not None:
        raise ProtocolViolation("result on unfinished job")
    if job["state"] == "failed":
        if (not isinstance(error, dict) or not isinstance(error.get("code"), str)
                or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,39}", error["code"]) or set(error) - {"code"}):
            raise ProtocolViolation("error")
    elif error is not None:
        raise ProtocolViolation("error on non-failed job")
    return job
