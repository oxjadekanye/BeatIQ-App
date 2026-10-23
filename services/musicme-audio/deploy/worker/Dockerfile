# MusicMe GPU worker image (musicme.gpu-job.v1). Provider-neutral: RunPod, Vast.ai or any NVIDIA GPU host.
# Build from services/musicme-audio:  docker build -f deploy/worker/Dockerfile -t musicme-gpu-worker:dev .
# The pins below must equal audioservice/acestep/manifest.json (a test enforces it). No secrets are baked in:
# the worker token arrives at run time (MUSICME_WORKER_TOKEN or a mounted MUSICME_WORKER_TOKEN_FILE).
FROM python:3.12.12-slim-bookworm

ARG ACESTEP_REPO=https://github.com/ace-step/ACE-Step-1.5.git
ARG ACESTEP_TAG=v0.1.8
ARG ACESTEP_COMMIT=dce621408bee8c31b4fcf4811682eb9359e1bc94

RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates ffmpeg libsndfile1 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home --uid 10001 worker

COPY deploy/worker/requirements-gpu.txt /tmp/requirements-gpu.txt
RUN pip install --no-cache-dir -r /tmp/requirements-gpu.txt && rm /tmp/requirements-gpu.txt

RUN git clone --quiet --depth 1 --branch "$ACESTEP_TAG" "$ACESTEP_REPO" /opt/acestep \
 && test "$(git -C /opt/acestep rev-parse HEAD)" = "$ACESTEP_COMMIT" \
 && ln -s /data/checkpoints /opt/acestep/checkpoints \
 && mkdir -p /data/checkpoints /data/work && chown -R worker:worker /data

# Only the service package (root-owned, read-only for the worker user); no tests, tools or local data.
COPY audioservice /app/audioservice
COPY deploy/worker/entrypoint.sh /app/entrypoint.sh
RUN find /app -name __pycache__ -prune -exec rm -rf {} + && chmod 0755 /app/entrypoint.sh

ENV MUSICME_WORKER_HOST=127.0.0.1 \
    MUSICME_WORKER_PORT=8080 \
    MUSICME_WORKER_DATA_DIR=/data/work \
    MUSICME_WORKER_ACESTEP_HOME=/opt/acestep \
    MUSICME_WORKER_IDLE_EXIT_SECONDS=900 \
    HF_HUB_DISABLE_TELEMETRY=1 \
    PYTHONDONTWRITEBYTECODE=1

USER worker
WORKDIR /app
# Loopback by default: reach it through an SSH tunnel (Vast.ai, VMs). For a provider HTTPS proxy (RunPod), set
# MUSICME_WORKER_HOST=0.0.0.0 and expose 8080 through that proxy only.
ENTRYPOINT ["/app/entrypoint.sh"]
