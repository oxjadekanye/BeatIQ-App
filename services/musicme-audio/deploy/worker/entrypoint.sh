#!/bin/sh
# Prepare (download only if missing, then verify) the pinned weights, then serve. Exits non-zero on any mismatch.
set -eu
cd /app
python -s -B -m audioservice.gpu_worker.weights --home "${MUSICME_WORKER_ACESTEP_HOME:-/opt/acestep}" --checkpoints /data/checkpoints
exec python -s -B -m audioservice.gpu_worker.server
