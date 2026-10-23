# MusicMe GPU worker deployment (DEV)

One worker, three ways to host it. The audio service on the Mac only needs a URL and a private credentials file.

| Host | How it is reached | Scale to zero | Files |
|---|---|---|---|
| **Modal (first Samsung DEV test)** | `https://<workspace>--musicme-gpu-worker-dev-worker-serve.modal.run` (HTTPS, Modal proxy auth + worker token) | Yes: no warm containers, scale-down after 120 s idle | `modal/musicme_worker.py` |
| RunPod pod | `https://<pod>-8080.proxy.runpod.net` (set `MUSICME_WORKER_HOST=0.0.0.0`) or an SSH tunnel | Worker exits after 15 min idle; configure/verify provider stop-on-exit and still check billing | `worker/Dockerfile` |
| Vast.ai / any GPU VM | SSH tunnel to the container's loopback port: `http://127.0.0.1:<local port>` | Same idle exit; destroy the instance after the test | `worker/Dockerfile` |

## Mac side

The audio service settings are the same for every host:

```text
MUSICME_AUDIO_ENGINE=remote_gpu
MUSICME_AUDIO_ENVIRONMENT=development
MUSICME_AUDIO_WORKERS=1
MUSICME_AUDIO_RENDER_TIMEOUT_SECONDS=600
MUSICME_AUDIO_WORKER_URL=<url above>
MUSICME_AUDIO_WORKER_CREDENTIALS_FILE=$HOME/.cache/musicme-dev/gpu-worker.json   # chmod 600
MUSICME_AUDIO_GPU_DAILY_BUDGET_USD=0.75   # below the current $2 Modal workspace usage/spend limits
MUSICME_AUDIO_GPU_PRICE_PER_HOUR_USD=0.82   # Modal T4 + 2 cores + 16 GiB; use the host's real rate
```

The credentials file looks like this. The `headers` entry is only needed for Modal proxy auth:

```json
{"token": "<MUSICME_WORKER_TOKEN>", "headers": {"Modal-Key": "<proxy token id>", "Modal-Secret": "<proxy token secret>"}}
```

## Moving to a cheaper GPU later

The protocol (`audioservice/gpu_protocol.py`), the worker and the Mac engine don't depend on a provider. To move:

1. Build `worker/Dockerfile` and run it on the new host.
2. Give it a fresh worker token.
3. Change `MUSICME_AUDIO_WORKER_URL`, the credentials file and `MUSICME_AUDIO_GPU_PRICE_PER_HOUR_USD`.

Nothing in the Android app changes.

For Modal, keep every resource in the dedicated `musicme-dev` environment. The adapter explicitly resolves its
secret and volume there; invoke it with `modal run --env musicme-dev ...` and `modal deploy --env musicme-dev ...`.
The serving class mounts the verified weight volume read-only and configures zero Modal retries.

The container's idle exit is a safety backstop, not proof that a provider stopped billing the underlying VM or
storage. Do not use an automatic restart policy. For RunPod confirm the pod is stopped/terminated in its dashboard;
for Vast destroy the instance after the test. Configure provider-side spend limits/alerts wherever available.
