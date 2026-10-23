"""Modal adapter for the MusicMe GPU worker (DEV). PREPARED LOCALLY, NEVER DEPLOYED: deploying needs owner approval.

The container runs the same provider-neutral worker as deploy/worker/Dockerfile (audioservice/gpu_worker), which
drives the committed, GPU-validated ACE-Step runner unchanged. Modal only supplies the GPU, scaling and an HTTPS URL.

Cost controls (all explicit below):
* T4 (the GPU the pipeline was validated on, float16 path + lyric-encoder fix), one container at most, none kept warm:
  min_containers=0, buffer_containers=0, max_containers=1, scaledown after SCALEDOWN_WINDOW_SECONDS idle.
* Modal proxy auth (requires_proxy_auth): requests without a workspace proxy token are refused by Modal BEFORE a
  container starts, so nobody else can trigger GPU time. The worker's own bearer token is checked as well.
* Per-container timeout and the worker's own per-job deadline and bounded queue; weights preparation runs on CPU.

Secrets stay server-side: the worker token lives in the Modal secret TOKEN_SECRET (container env) and in a chmod 600
file on the audio-service Mac, together with the proxy token. Nothing reaches the Android app.

Owner steps (each gated): see docs/MUSICME_SAMSUNG_DEV_E2E_PLAN.md
    modal run --env musicme-dev deploy/modal/musicme_worker.py::prepare_weights  # CPU: pinned download + verify
    modal deploy --env musicme-dev deploy/modal/musicme_worker.py                 # scale-to-zero endpoint
"""
import json
import os
import pathlib
import subprocess
import sys

import modal
from modal.config import config as modal_config

CONTAINER_APP = pathlib.Path("/app")
MANIFEST_RELATIVE = pathlib.Path("audioservice/acestep/manifest.json")
REQUIREMENTS_RELATIVE = pathlib.Path("deploy/worker/requirements-gpu.txt")


def _source_root():
    """Find deployment inputs locally or at their explicit Modal mount point.

    Modal uploads this entrypoint as /root/musicme_worker.py, so __file__ is not a
    reliable way to find the repository in a running container. The audioservice
    and requirements file are mounted below /app by the image definition.
    """
    local_service = pathlib.Path(__file__).resolve().parent.parent.parent
    candidates = (local_service, CONTAINER_APP) if modal.is_local() else (CONTAINER_APP,)
    for candidate in candidates:
        if (candidate / MANIFEST_RELATIVE).is_file() and (candidate / REQUIREMENTS_RELATIVE).is_file():
            return candidate
    raise RuntimeError("MusicMe deployment sources are missing from both /app and the local repository")


SOURCE_ROOT = _source_root()
MANIFEST = json.loads((SOURCE_ROOT / MANIFEST_RELATIVE).read_text())
CODE = MANIFEST["code"]

APP_NAME = "musicme-gpu-worker-dev"
MODAL_ENVIRONMENT = "musicme-dev"
active_environment = modal_config.get("environment") if modal.is_local() else os.environ.get("MODAL_ENVIRONMENT")
if active_environment != MODAL_ENVIRONMENT:
    raise RuntimeError(
        "Refusing to load the DEV worker outside the musicme-dev Modal environment; use --env musicme-dev."
    )
GPU = "T4"
CPU_CORES = 2.0
MEMORY_MIB = 16384
MIN_CONTAINERS = 0
BUFFER_CONTAINERS = 0
MAX_CONTAINERS = 1
SCALEDOWN_WINDOW_SECONDS = 120
CONTAINER_TIMEOUT_SECONDS = 900
STARTUP_TIMEOUT_SECONDS = 120  # until the worker listens; the model then loads in the background (hello says "starting")
MAX_CONCURRENT_REQUESTS = 8  # status polls share the one container; renders stay one at a time inside the worker
WORKER_PORT = 8080
CHECKPOINTS = "/data/checkpoints"
ACESTEP_HOME = "/opt/acestep"
WEIGHTS_VOLUME = "musicme-acestep-weights"
TOKEN_SECRET = "musicme-gpu-worker-token"  # holds MUSICME_WORKER_TOKEN (32+ random characters)

app = modal.App(APP_NAME, tags={"project": "beatiq-musicme", "environment": "samsung-dev"})
weights = modal.Volume.from_name(WEIGHTS_VOLUME, environment_name=MODAL_ENVIRONMENT, create_if_missing=True)
read_only_weights = weights.with_mount_options(read_only=True)

image = (
    modal.Image.debian_slim(python_version="3.12.12")
    .apt_install("git", "ca-certificates", "ffmpeg", "libsndfile1")
    .pip_install_from_requirements(str(SOURCE_ROOT / REQUIREMENTS_RELATIVE))
    .run_commands(
        f"git clone --quiet --depth 1 --branch {CODE['tag']} {CODE['repository']}.git {ACESTEP_HOME}",
        f'test "$(git -C {ACESTEP_HOME} rev-parse HEAD)" = "{CODE["commit"]}"',
        f"ln -s {CHECKPOINTS} {ACESTEP_HOME}/checkpoints",
    )
    .env({"MUSICME_WORKER_HOST": "0.0.0.0", "MUSICME_WORKER_PORT": str(WORKER_PORT),
          "MUSICME_WORKER_DATA_DIR": "/tmp/musicme-work", "MUSICME_WORKER_ACESTEP_HOME": ACESTEP_HOME,
          "MUSICME_WORKER_IDLE_EXIT_SECONDS": "0", "MUSICME_WORKER_MAX_QUEUE": "1",
          "MUSICME_WORKER_MAX_JOB_SECONDS": "600", "HF_HUB_DISABLE_TELEMETRY": "1", "PYTHONDONTWRITEBYTECODE": "1"})
    # The requirements file is also mounted because this module is imported again as /root/musicme_worker.py.
    .add_local_file(str(SOURCE_ROOT / REQUIREMENTS_RELATIVE), "/app/deploy/worker/requirements-gpu.txt")
    .add_local_dir(str(SOURCE_ROOT / "audioservice"), "/app/audioservice", ignore=["**/__pycache__"])
)


@app.function(image=image, volumes={CHECKPOINTS: weights}, cpu=1.0, memory=2048, timeout=1800, retries=0)
def prepare_weights():
    """CPU only: downloads the pinned weights into the volume (if needed) and verifies every SHA-256."""
    subprocess.run([sys.executable, "-s", "-B", "-m", "audioservice.gpu_worker.weights", "--home", ACESTEP_HOME,
                    "--checkpoints", CHECKPOINTS], check=True, cwd="/app")
    weights.commit()


@app.cls(image=image, gpu=GPU, cpu=CPU_CORES, memory=MEMORY_MIB, volumes={CHECKPOINTS: read_only_weights},
         secrets=[modal.Secret.from_name(TOKEN_SECRET, environment_name=MODAL_ENVIRONMENT)], retries=0,
         min_containers=MIN_CONTAINERS,
         buffer_containers=BUFFER_CONTAINERS, max_containers=MAX_CONTAINERS,
         scaledown_window=SCALEDOWN_WINDOW_SECONDS, timeout=CONTAINER_TIMEOUT_SECONDS)
@modal.concurrent(max_inputs=MAX_CONCURRENT_REQUESTS)
class Worker:
    @modal.web_server(port=WORKER_PORT, startup_timeout=STARTUP_TIMEOUT_SECONDS, requires_proxy_auth=True)
    def serve(self):
        # The runner re-verifies the pinned commit and every weight hash before loading; nothing is downloaded here.
        subprocess.Popen([sys.executable, "-s", "-B", "-m", "audioservice.gpu_worker.server"], cwd="/app",
                         env=dict(os.environ))
