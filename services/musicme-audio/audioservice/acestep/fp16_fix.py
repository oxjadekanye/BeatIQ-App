"""float16 lyric-encoder overflow fix for ACE-Step 1.5 v0.1.8 (runner process only; needs torch).

Validated on a Tesla T4 in Phase 2.3E: in float16 the lyric encoder's last MLP projection reached 293,784 (float16's
maximum is 65,504) and turned every latent NaN for songs with lyrics. Computing only that projection in float32 fixed
it (self-test 8/8, encoder output finite, validated 30 s WAV). Adapted, MIT, from ACE-Step PR #1352 by
christopherrobertbrooks-tech (unmerged upstream).

The function bodies below are copied verbatim from the validated notebook harness; tests/test_acestep_adapter.py
checks that they stay identical.
"""
import torch


class Float32Linear(torch.nn.Linear):
    """A Linear that computes in float32 from weights stored in any dtype, autocast or not."""

    def forward(self, x):
        with torch.autocast(device_type=x.device.type, enabled=False):
            bias = None if self.bias is None else self.bias.float()
            return torch.nn.functional.linear(x.float(), self.weight.float(), bias)


def _cast_to_weight_dtype(module, args, output):
    return output.to(module.weight.dtype)


def _last_down_proj(encoder):
    layers = getattr(encoder, "layers", None)
    if not isinstance(layers, torch.nn.ModuleList):
        return None
    used = getattr(getattr(encoder, "config", None), "num_hidden_layers", None)  # forward runs layers[:num_hidden_layers]
    ran = layers[:used] if isinstance(used, int) else layers
    if len(ran) == 0:
        return None
    return getattr(getattr(ran[-1], "mlp", None), "down_proj", None)


def compute_lyric_encoder_tail_in_float32(model):
    """True if model.encoder.lyric_encoder has the expected v0.1.8 shape and is now (or already was) patched."""
    encoder = getattr(getattr(model, "encoder", None), "lyric_encoder", None)
    if not isinstance(encoder, torch.nn.Module):
        return False
    norm = getattr(encoder, "norm", None)
    down = _last_down_proj(encoder)
    if isinstance(down, Float32Linear):
        return True
    if type(down) is not torch.nn.Linear or not isinstance(norm, torch.nn.Module) or not hasattr(norm, "weight"):
        return False
    down.__class__ = Float32Linear
    norm.register_forward_hook(_cast_to_weight_dtype)
    return True


def fp16_fix_selftest(device):
    """Numeric regression test on the real GPU before any render: a float16 Linear that overflows must become finite
    once patched, the encoder output must come back as float16, and float32 models must be left alone."""
    from types import SimpleNamespace
    from transformers.models.qwen3.modeling_qwen3 import Qwen3RMSNorm

    def build(dtype):
        layer = torch.nn.Module(); layer.mlp = torch.nn.Module()
        layer.mlp.down_proj = torch.nn.Linear(8, 8, bias=True)
        enc = torch.nn.Module(); enc.layers = torch.nn.ModuleList([torch.nn.Module(), layer])
        enc.layers[0].mlp = torch.nn.Module(); enc.layers[0].mlp.down_proj = torch.nn.Linear(8, 8)
        enc.norm = Qwen3RMSNorm(8); enc.config = SimpleNamespace(num_hidden_layers=24)
        model = torch.nn.Module(); model.encoder = torch.nn.Module(); model.encoder.lyric_encoder = enc
        with torch.no_grad():
            layer.mlp.down_proj.weight.fill_(300.0); layer.mlp.down_proj.bias.zero_()
        return model.to(device=device, dtype=dtype), enc, layer

    def run(enc, layer, dtype):
        x = torch.full((1, 3, 8), 100.0, dtype=dtype, device=device)
        h = x + layer.mlp.down_proj(x)          # residual add, as in AceStepEncoderLayer
        return enc.norm(h)                      # final norm, as in AceStepLyricEncoder.forward

    results = {}
    model, enc, layer = build(torch.float16)
    control = run(enc, layer, torch.float16)
    results["control_overflows_without_fix"] = bool(not torch.isfinite(control).all())
    results["patched"] = compute_lyric_encoder_tail_in_float32(model)
    fixed = run(enc, layer, torch.float16)
    results["finite_with_fix"] = bool(torch.isfinite(fixed).all())
    results["output_dtype_float16"] = fixed.dtype == torch.float16
    results["patches_last_layer_only"] = isinstance(layer.mlp.down_proj, Float32Linear) and type(enc.layers[0].mlp.down_proj) is torch.nn.Linear
    results["idempotent"] = compute_lyric_encoder_tail_in_float32(model) and len(enc.norm._forward_hooks) == 1
    reference = run(*build(torch.float32)[1:], torch.float32)
    results["close_to_float32"] = bool(torch.allclose(fixed.float(), reference, rtol=1e-2, atol=1e-2))
    results["unrecognised_model_refused"] = compute_lyric_encoder_tail_in_float32(torch.nn.Linear(2, 2)) is False
    results["passed"] = all(v for k, v in results.items())
    return results


def install_diagnostics(model, record):
    """Records the lyric encoder's tail magnitude and output finiteness during the render (no lyrics are stored)."""
    encoder = model.encoder.lyric_encoder
    diag = record.setdefault("diagnostics", {"lyric_tail_max_abs": None, "lyric_encoder_output_finite": None,
                                             "lyric_encoder_output_dtype": None})

    def tail_hook(module, args, output):
        diag["lyric_tail_max_abs"] = float(output.detach().abs().max().float().item())

    def out_hook(module, args, output):
        diag["lyric_encoder_output_finite"] = bool(torch.isfinite(output).all().item())
        diag["lyric_encoder_output_dtype"] = str(output.dtype)

    _last_down_proj(encoder).register_forward_hook(tail_hook)
    encoder.norm.register_forward_hook(out_hook)
