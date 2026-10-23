"""Production fal.ai engine using the reviewed ACE-Step queue endpoints.

The application submits at most once. A provider request id is persisted immediately and reused after process
restarts; status polling retries are safe because they do not create another generation.
"""
import time

from .cost_guard import FalBudgetExceeded
from .engines import AudioEngine, Cancelled, EngineError, RenderResult
from .presets import production_prompt
from .providers import FalProvider, FalProviderError


class FalAudioEngine(AudioEngine):
    kind = "fal"
    model_id = "fal-ai/ace-step"
    model_version = "provider-managed-unpinned"
    model_revision = "not-published-by-provider"
    is_mock = False
    output_format = "wav"

    def __init__(self, settings, ledger=None, provider_state=None, client=None, clock=time.monotonic,
                 sleeper=time.sleep):
        self.s = settings
        self.ledger = ledger
        self.provider_state = provider_state
        self.client = client or FalProvider(settings.fal_key, settings.fal_queue_base_url,
                                            settings.fal_download_allowed_hosts)
        self.clock = clock
        self.sleep = sleeper

    def limits(self):
        return {"min_duration_seconds": 5, "max_duration_seconds": 240, "max_takes": 1,
                "max_lyric_chars": 6000, "max_caption_chars": 1000}

    def check_spec(self, spec):
        if spec["takes"] != 1:
            return "fal.ai generation supports one take per request so cost stays explicit."
        if not 5 <= spec["target_duration_seconds"] <= 240:
            return "Choose a duration from 5 to 240 seconds."
        return None

    def cancel_request(self, endpoint, request_id):
        return self.client.cancel(endpoint, request_id)

    def settle_cost(self, job_id, actual_seconds, requested_seconds=0):
        # fal bills generated seconds; never settle below the requested duration in case it bills the request.
        if self.ledger is not None:
            self.ledger.settle(job_id, max(actual_seconds, requested_seconds))

    def render(self, request, out_path, progress, deadline):
        started = self.clock()
        custom_lyrics = any(section.lyrics for section in request.sections)
        endpoint = self.s.fal_custom_lyrics_endpoint if custom_lyrics else self.s.fal_prompt_endpoint
        if request.provider_endpoint and request.provider_endpoint != endpoint:
            raise EngineError("PROVIDER_STATE_INVALID", "The saved provider request cannot be resumed.")
        provider_id = request.provider_request_id
        reserved = None
        if not provider_id:
            # Re-checked at execution time: a job queued before the kill switch was set or before the account left
            # the allowlist must never start a new paid request. An already-saved request is resumed (it is paid).
            if not self.s.generation_allowed(request.account):
                raise EngineError("GENERATION_DISABLED", "Song generation is currently disabled for this account.")
            if self.ledger is None or self.provider_state is None:
                raise EngineError("BUDGET_UNAVAILABLE", "Song creation spending controls are unavailable.")
            try:
                # Reserve the longest output that the validator would accept. This keeps the application cap
                # conservative if provider output runs slightly past the requested target; success is reconciled
                # to measured duration and every uncertain/failure outcome keeps the full reservation charged.
                reserve_seconds = request.duration_seconds * (1 + self.s.duration_tolerance)
                reserved = self.ledger.reserve(request.job_id, request.account, reserve_seconds)
            except FalBudgetExceeded:
                raise EngineError("BUDGET_EXCEEDED", "The MusicMe daily generation budget has been reached.")
            try:
                provider_id = self.client.submit(endpoint, self._arguments(request, custom_lyrics), timeout=30)
            except FalProviderError as error:
                # The application never issues a second generation request when submission is uncertain.
                raise self._provider_error(error)
            if not self.provider_state.set_provider_request(request.job_id, request.worker_id, provider_id, endpoint):
                self.client.cancel(endpoint, provider_id)
                raise Cancelled()
        if not progress(0.05):
            self.client.cancel(endpoint, provider_id)
            raise Cancelled()

        safe_poll_failures = 0
        while self.clock() < deadline:
            if not progress(0.15):
                self.client.cancel(endpoint, provider_id)
                raise Cancelled()
            try:
                status = self.client.status(endpoint, provider_id, timeout=self._timeout(deadline, 15))
                safe_poll_failures = 0
            except FalProviderError as error:
                if not error.transient or safe_poll_failures >= 4:
                    raise self._provider_error(error)
                safe_poll_failures += 1
                self.sleep(min(5.0, 0.5 * (2 ** safe_poll_failures)))
                continue
            if status["status"] == "COMPLETED":
                break
            fraction = 0.15 if status["status"] == "IN_QUEUE" else 0.55
            if not progress(fraction):
                self.client.cancel(endpoint, provider_id)
                raise Cancelled()
            self.sleep(min(2.0, max(0.1, deadline - self.clock())))
        else:
            self.client.cancel(endpoint, provider_id)
            raise EngineError("TIMEOUT", "Song creation exceeded its deadline.")

        if not progress(0.8):
            self.client.cancel(endpoint, provider_id)
            raise Cancelled()
        try:
            result = self.client.result(endpoint, provider_id, timeout=self._timeout(deadline, 30))
            audio = result.get("audio")
            url = audio.get("url") if isinstance(audio, dict) else None
            if not isinstance(url, str) or not isinstance(result.get("seed"), int):
                raise FalProviderError("PROVIDER_RESPONSE_INVALID")
            self.client.download_audio(url, out_path, self.s.max_audio_bytes,
                                       timeout=self._timeout(deadline, 60))
        except FalProviderError as error:
            raise self._provider_error(error)
        return RenderResult("wav", 0, 0, int((self.clock() - started) * 1000), {
            "provider": "fal.ai", "endpoint": endpoint, "provider_request_id": provider_id,
            "provider_seed": result["seed"], "estimated_cost_usd": reserved,
            "pricing_basis": "generated_audio_seconds", "price_per_second_usd": self.s.fal_price_per_second_usd,
            "model_version_pinned": False,
        })

    def _arguments(self, request, custom_lyrics):
        prompt = production_prompt(request.prompt or request.caption, request.genres)
        common = {
            "duration": request.duration_seconds,
            "number_of_steps": 27,
            "seed": request.seed,
            "scheduler": "euler",
            "guidance_type": "apg",
            "granularity_scale": 10,
            "guidance_interval": 0.5,
            "guidance_interval_decay": 0,
            "guidance_scale": 15,
            "minimum_guidance_scale": 3,
            "tag_guidance_scale": 5,
            "lyric_guidance_scale": 1.5,
        }
        if custom_lyrics:
            return {"tags": prompt, "lyrics": request.tagged_lyrics(), **common}
        return {"prompt": prompt, "instrumental": request.vocal_type == "none", **common}

    def _timeout(self, deadline, maximum):
        remaining = deadline - self.clock()
        if remaining <= 0:
            raise EngineError("TIMEOUT", "Song creation exceeded its deadline.")
        return max(1, min(maximum, int(remaining)))

    @staticmethod
    def _provider_error(error):
        if error.code == "PROVIDER_UNAUTHORIZED":
            return EngineError("PROVIDER_UNAVAILABLE", "Song creation is not configured correctly.")
        if error.code == "PROVIDER_REJECTED":
            return EngineError("PROVIDER_REJECTED", "The music provider could not accept this song request.")
        return EngineError("PROVIDER_UNAVAILABLE", "The music provider could not complete this song.")
