"""ACE-Step 1.5 integration: pinned manifest, JSON-lines protocol, float16 fix and the separate runner process.

The service side (`audioservice.engines.AceStepEngine`) imports only `manifest` and `protocol`, which use the standard
library. `fp16_fix` and `runner` need torch and ACE-Step and are only ever imported inside the runner process, which
runs under ACE-Step's own Python 3.12 environment.
"""
