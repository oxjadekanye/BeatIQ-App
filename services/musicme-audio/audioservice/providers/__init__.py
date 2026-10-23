"""Server-side music generation providers. Provider credentials never cross this package boundary."""

from .fal import FalProvider, FalProviderError

__all__ = ["FalProvider", "FalProviderError"]
