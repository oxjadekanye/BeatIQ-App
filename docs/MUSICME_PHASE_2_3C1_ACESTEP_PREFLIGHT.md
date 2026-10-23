# MusicMe Phase 2.3C-1 — ACE-Step 1.5 local preflight (21 Oct 2026)

> **Status: preflight only.** Nothing was downloaded, installed or run. Only metadata was read (GitHub and
> Hugging Face APIs, the project's licence, lock file, docs and source at the pinned tag). Labels:
> **✔ verified** = read from the pinned release / model repo / this Mac today; **(V)** = vendor claim;
> **(E)** = our estimate; **untested** = nobody has run it here.

Continues from commit `64423b2`. ACE-Step 1.5 is an evaluation candidate, not an approved production model.

## 1. This Mac (measured ✔)

| | Value |
|---|---|
| Machine | MacBook Pro (MacBookPro17,1), Apple **M1**, 8 cores (4P + 4E), 8-core GPU, Metal 4 |
| Memory | **8 GB unified** |
| macOS | 26.6.2 (25G83) |
| Disk | 460 GB volume, **29 GB free (94% used)** |
| Memory at inspection | **109 MB unused**, 2.1 GB wired, 3.0 GB in the compressor, **swap 10.4 GB used of 11.3 GB**; ~290 M swap-outs since boot |
| Notable residents | Docker Desktop's Linux VM (Virtualization.framework), Claude, Chrome, Cline, ChatGPT, a JDK (Gradle) |
| Python | System 3.9.6 (`/usr/bin/python3`, used by both MusicMe services); **uv 0.11.29** with a user-local **CPython 3.12.13** already installed |
| PyTorch / MLX | Not installed anywhere (good: nothing global to disturb) |
| ffmpeg, git-lfs, huggingface-cli | Not installed (none needed for the plan below) |
| Existing model cache | `~/.cache/musicme-models` 2.3 GB (Qwen3-4B for songwriting; untouched) |

**Risk:** the machine is already deep in swap. ACE-Step's minimum weights (6.3 GB, below) exceed the RAM that is
free today. Running it now would force heavy swapping of every open app, and Metal allocations under that pressure
can stall the system for minutes. Nothing was closed or deleted. Freeing memory is the user's call (§8).

## 2. Model release (verified ✔)

| Item | Pinned value |
|---|---|
| Code | `github.com/ace-step/ACE-Step-1.5`, release **v0.1.8** (18 May 2026), commit **`dce621408bee8c31b4fcf4811682eb9359e1bc94`**, package version 1.5.0, repo ~14 MB. `main` has moved on (last push 11 Oct 2026); we pin the tag. |
| Weights | Hugging Face **`ACE-Step/Ace-Step1.5`**, revision **`19671f406d603126926c1b7e2adc169acbcade22`** (3 Feb 2026), not gated |
| Licence | **MIT** for the code (`LICENSE`, `pyproject.toml`) and the weights (HF `license: mit`) ✔. Commercial use is permitted by the licence. Training-data provenance and output rights remain open (Phase 2.3A §5). |
| Full repo snapshot | **10.09 GB**, 28 files |

Files needed for the first test (no language model): **6.34 GB**, 17 files.

| File | Size | SHA-256 (from HF LFS metadata ✔) |
|---|---|---|
| `acestep-v15-turbo/model.safetensors` (2B DiT, turbo, 8 steps) | 4,787.8 MB | `3f6e0797fad420a39bd33979eb6e840e30989e34a3794e843d23b60ec6e422d7` |
| `Qwen3-Embedding-0.6B/model.safetensors` (text encoder) | 1,191.6 MB | `0437e45c94563b09e13cb7a64478fc406947a93cb34a7e05870fc8dcd48e23fd` |
| `vae/diffusion_pytorch_model.safetensors` (48 kHz stereo VAE) | 337.4 MB | `da17edb604c40deaf09e9b24974e590d1ca83a374070e5d0884cfa4bed9a99b0` |
| configs, tokenizers, `silence_latent.pt` (3.8 MB), model `.py` files | ~25 MB | checked after download |

Excluded: `acestep-5Hz-lm-1.7B` (3.71 GB). The optional `ACE-Step/acestep-5Hz-lm-0.6B` (1.37 GB, revision
`148d8ea0…`) is not needed for the first test either.

**Supply-chain notes ✔:**
* The DiT loads with `trust_remote_code=True`. ACE-Step overwrites the downloaded model `.py` files with the copies
  from its own pinned source tree before loading, so the code that runs comes from commit `dce6214`. We will diff
  those files once after download.
* `silence_latent.pt` is a pickle but is loaded with `torch.load(..., weights_only=True)`.
* **ACE-Step auto-downloads missing models** at initialisation (from Hugging Face, or ModelScope after a Google
  reachability probe). The evaluation must pre-place verified weights and run with `HF_HUB_OFFLINE=1`,
  `TRANSFORMERS_OFFLINE=1` and `HF_HUB_DISABLE_TELEMETRY=1`, so an incomplete download fails instead of silently
  fetching more.
* No telemetry modules exist in the source tree. Gradio's analytics stay off because we don't launch the UI
  (`GRADIO_ANALYTICS_ENABLED=False` is set anyway).

## 3. Apple Silicon support (verified in source ✔, untested here)

* Officially supported: the README and INSTALL list MPS. macOS launch scripts use MLX for the language model.
  v0.1.8 also ships native **MLX DiT and VAE** paths (`acestep/models/mlx/`), converted from the PyTorch weights at load.
* `gpu_config.py` detects MPS memory via `torch.mps.recommended_max_memory()`, roughly 5.3 GB on an 8 GB M1 (E). That
  selects **Tier 2 (4–6 GB): no language model, batch 1**. On macOS it forces no quantisation (torchao INT8 is
  "incompatible with MPS") and no CPU offload ("pointless" on unified memory).
* So on this Mac the DiT runs **unquantised**, about 4.7 GB of weights (V: "~4.7 GB for 2B").
* Untested: M1 has no native bfloat16. Which dtype ACE-Step picks on MPS, and whether the PyTorch → MLX conversion
  briefly holds two copies of the DiT, must be observed in the first run.
* Vendor speed claims (A100 under 2 s, RTX 3090 under 10 s per song) don't transfer to an M1.
  The one M1-family report found was an **M1 Max with 64 GB**: two 100 s songs in about 58 s (third party).

## 4. Expected memory and storage (estimates (E))

| | First test (2B turbo, no LM, 30 s, batch 1) |
|---|---|
| Resident weights | DiT ~4.8 GB + text encoder ~1.2 GB + VAE ~0.35 GB ≈ **6.3 GB** |
| Activations for 30 s (750 latent frames at 25 Hz) | ~0.3–1 GB |
| Load-time peak (possible PyTorch→MLX double copy) | up to ~9–11 GB |
| **Expected peak** | **~7–8 GB steady, possibly ~10 GB at load → swap on an 8 GB Mac even when idle** |
| Weights on disk | 6.34 GB |
| Isolated Python environment (torch 2.10.0, mlx 0.30.6, transformers 4.57.6, diffusers, gradio, scipy, …) | ~2–3 GB, plus ~1 GB uv download cache |
| Outputs | ~5.8 MB per 30 s 48 kHz stereo 16-bit WAV |
| **Total new disk** | **~10–11 GB**, leaving ~18 GB free; swap growth also uses this disk |
| Expected time on this M1 | **Unknown; could be minutes per 30 s under swap.** Abort limits in §7. |

## 5. Runtime environment (no global changes)

* **Location outside the repository:** `~/.cache/musicme-acestep/` containing
  * `src/`: a git checkout of tag v0.1.8, verified at commit `dce6214`;
  * `.venv/`: created by `uv sync --frozen` against the project's own `uv.lock`, using the existing uv-managed
    CPython 3.12.13 (`requires-python >=3.11,<3.13`). The lock pins macOS arm64 to torch 2.10.0 (PyPI), torchaudio
    2.10.0, mlx 0.30.6, mlx-lm 0.29.1, transformers 4.57.6, diffusers 0.37.1 and huggingface-hub 0.36.0;
  * `checkpoints/`: the 17 files above, downloaded with `huggingface_hub.snapshot_download(revision=19671f4…,
    allow_patterns=[...])` from inside the venv, then each `.safetensors` checked against the SHA-256 table.
* The system Python 3.9, the two MusicMe services, Gradle/Android and Homebrew stay untouched. No `pip install`
  outside the venv, no shell profile changes. Deleting `~/.cache/musicme-acestep/` removes everything.
* **Required dependency changes to BeatIQ: none.** `services/musicme-audio` stays standard-library Python 3.9 and
  never imports torch. The model runs in the separate venv process (§6).

## 6. Adapter integration (design; not implemented in this phase)

**Process boundary.** `AceStepEngine` stays in `audioservice`, but rendering happens in a long-lived **worker
subprocess** started with the venv's Python 3.12:
* `audioservice/acestep_runner.py` is a small script run by that interpreter, with no dependency on the service.
* It loads the pinned weights once, then reads one JSON request per line on stdin, writes the WAV to the temp path
  the service gives it, and replies with JSON metadata. Lyrics stay off the command line and out of logs.
* The service process keeps its stdlib-only, Python 3.9 runtime.
* Run it with the offline environment variables above plus `ACESTEP_SAVE_MEMORY=1`, which disables auto-LRC and
  auto-scoring to save RAM.

**Parameter mapping** (`RenderRequest` → ACE-Step `GenerationParams` / `GenerationConfig`, all ✔ from `docs/en/INFERENCE.md` at v0.1.8):

| MusicMe | ACE-Step | Notes |
|---|---|---|
| `tagged_lyrics()` (`[Verse 1]`, `[Chorus]`, …) | `lyrics` | Format matches the documented examples. **Max 4,096 chars** vs our contract's 6,000: the adapter must refuse longer lyrics (`INVALID_REQUEST`) or the capability must advertise 4,096. |
| `caption` (genres, moods, instruments, vocal type + descriptors, BPM) | `caption` | **Max 512 chars**. Vocal gender and timbre are steered only through caption text (no dedicated parameter); adherence is untested. |
| `vocal_type == "none"` | `instrumental=True`, lyrics `"[Instrumental]"` | |
| `language` (`english`) | `vocal_language="en"` | Map catalogue ids to ISO 639-1; only English is enabled. |
| `bpm` | `bpm` | ACE-Step accepts 30–300; ours 50–200. |
| `duration_seconds` | `duration` | 10–600 s; Tier 2 caps at 10 min without the LM. |
| `seed` | `seed`; `GenerationConfig(use_random_seed=False, seeds=[seed], batch_size=1)` | Determinism on MPS is untested. |
| fixed | `task_type="text2music"`, `reference_audio=None`, `src_audio=None` | The runner asserts these; cover, repaint, extract, lego and complete are never exposed. No LoRA is ever loaded. |
| fixed | `inference_steps=8` (turbo), `thinking=False`, `use_cot_caption=False`, `use_cot_metas=False`, `use_cot_lyrics=False` | With no LM, our own caption and metadata are used verbatim and the model never rewrites them. |
| fixed | `keyscale=""`, `timesignature="4"` | Key left automatic for now. |
| output | `save_dir=None`; take `audios[0]["tensor"]` (float32, `[channels, samples]`, 48 kHz) | The runner writes **16-bit PCM WAV** itself with clip counting, so the existing validator applies unchanged. 150 s ≈ 28.8 MB, within the 64 MB limit. |

**Service changes needed for the real engine (2.3C-2):**
* Model identity: `model_id="ace-step-1.5"`, `model_version="acestep-v15-turbo"`, `model_revision` = HF revision +
  code commit, per-file SHA-256 manifest checked at runner start; refuse to start on any mismatch.
* `is_mock=False` only for this engine. `real_audio=true` appears only when the runner reports a completed real
  render. The mock stays labelled `mock` and remains loopback- and development-only.
* **Heartbeat during blocking renders:** `generate_music` has no per-step callback. The worker must renew the lease
  from a side thread while the runner works, cancellation takes effect after the render finishes (the result is
  discarded), and the render timeout kills and restarts the runner.
* Capability limits for this engine: max lyrics 4,096, caption ≤ 512, max duration per tier, `max_takes=1` on 8 GB.
* Engine config via `MUSICME_AUDIO_ACESTEP_PYTHON`, `MUSICME_AUDIO_ACESTEP_HOME` and
  `MUSICME_AUDIO_ACESTEP_MANIFEST`. Production stays refused (local storage) regardless.

## 7. First-generation test procedure (after approval)

**Preconditions (user decides):** quit Docker Desktop and other heavy apps, then confirm with `memory_pressure` and
`vm_stat` that at least 5.5 GB is free plus inactive, and that swap use has dropped. Keep at least 15 GB of disk free
after downloads. Leave the Mac on mains power, with nothing else heavy running.

**Steps**
1. *(Needs download approval)* Clone v0.1.8 → verify commit `dce6214`; `uv sync --frozen` into
   `~/.cache/musicme-acestep/.venv`; record `pip list` and Python version.
2. *(Needs download approval)* Download the 17 files at revision `19671f4…` → verify the three SHA-256 hashes →
   write `manifest.json`. Diff the model `.py` files against the source tree.
3. Offline run (`HF_HUB_OFFLINE=1` …), standalone harness first (not the service), one render:
   * **caption:** `acoustic pop, warm, hopeful, acoustic guitar, soft piano, female vocal, warm, 96 bpm`
   * **lyrics** (original, written for this test):
     ```
     [Verse]
     Morning light on the kitchen floor
     Coffee steam and an open door
     [Chorus]
     Here we are, here we stay
     Little sparks that light the way
     ```
   * `vocal_language="en"`, `bpm=96`, `duration=30`, `seed=20261021`, `inference_steps=8`, batch 1, no LM.
   * Output only to `~/.cache/musicme-acestep/outputs/` (private, outside the repo, 0700). Never committed.
4. Monitor: wall time, peak footprint (`/usr/bin/time -l` max RSS and `footprint`), swap before and after
   (`sysctl vm.swapusage`), `memory_pressure` every 10 s.
   **Abort** if the memory-pressure level reaches critical, swap free drops below 300 MB, disk free drops below
   10 GB, or the run passes 20 minutes.
5. Repeat step 3 with the same seed (reproducibility) and once with seed 20261022.
6. If 3–5 succeed: the same request through `services/musicme-audio` with `MUSICME_AUDIO_ENGINE=ace_step` on
   loopback (after the 2.3C-2 adapter work), exercising jobs, validation, storage and signed links.

**Objective checks (automated)**
* The existing `validate_wav`: 16-bit PCM, 48 kHz, 1–2 channels, duration 22.5–37.5 s (±25%), RMS above the
  silence floor, ≤ 1% clipped samples, size limit, WAV magic bytes.
* SHA-256 of each output recorded, with model, code and weight hashes in a run manifest.
* Same-seed rerun: report whether hashes are identical. If not, report the correlation of the waveforms (MPS
  determinism is not guaranteed).
* Also record: wall time, peak memory, swap delta, and the ACE-Step tier and dtype logged at start.

**Listening checks (human; at least 2 listeners, scores 1–5 with notes)**
* Is there actual singing (not speech, not humming) and an instrumental arrangement?
* Are the lyrics sung and intelligible, in order, with no invented or missing lines? Is the chorus recognisable?
* Does it follow the requested genre, mood, tempo, instruments, and female warm vocal?
* Is the audio clean (no metallic artefacts, warbling, clicks, abrupt ending or noise)?
* Does the voice or melody resemble a recognisable existing artist or song? Any yes is recorded and blocks further use.
* None of this can be judged automatically. A pass here says nothing about other genres or languages.

## 8. GO / NO-GO

| Question | Answer |
|---|---|
| Release, licence and weights identified and pinned | **Yes** ✔ (v0.1.8 `dce6214`; HF `19671f4…`; MIT) |
| Apple Silicon supported by this version | **Yes, in source** ✔ (MPS + MLX DiT/VAE); **untested on an 8 GB M1** |
| Isolated environment possible without touching BeatIQ or global Python | **Yes** (uv venv on existing CPython 3.12) |
| Fits this Mac's memory | **Marginal at best**: ~6.3 GB of weights against 8 GB total; **not today** (109 MB free, 10.4 GB swap) |
| Disk | **OK** with ~10–11 GB new; ~18 GB left |

**Recommendation: NO-GO as the Mac is right now. CONDITIONAL GO for one controlled smoke test** (2B turbo, no LM,
30 s, batch 1, with the abort limits) once heavy apps such as Docker Desktop are closed and memory pressure is normal.
Expect it to be slow and possibly fail. A failure is a finding about this hardware, not about ACE-Step.

If it fails or is impractically slow, the realistic next step is the Phase 2.3D rented 24 GB GPU (needs paid
approval). No 8 GB Mac result should be used for latency or cost planning.

**Approval needed before anything happens:** (1) ~14 MB code clone; (2) ~2–3 GB of Python packages into the
isolated venv; (3) **6.34 GB of model weights** from `ACE-Step/Ace-Step1.5` at revision `19671f4…`. About 10–11 GB
of disk in total, all under `~/.cache/musicme-acestep/`.
