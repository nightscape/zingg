"""Stage 2b — cross-encoder reranking.

`Reranker` is the pluggable interface; `CrossEncoderReranker` wraps a
sentence-transformers CrossEncoder (e.g. the Ettin reranker).
"""

from __future__ import annotations

from typing import Protocol

from .device import autodetect_device


class Reranker(Protocol):
    def score(self, pairs: list[tuple[str, str]]) -> list[float]:
        """Relevance score per (query, document) pair, higher = more relevant."""
        ...


class CrossEncoderReranker:
    """sentence-transformers CrossEncoder. The device MUST be mps/cuda — on CPU
    the reranker is so slow it looks hung."""

    def __init__(self, model: str, device: str | None = None, batch_size: int = 64):
        from sentence_transformers import CrossEncoder
        self.device = device or autodetect_device()
        self.batch_size = batch_size
        self.model_name = model
        self.ce = CrossEncoder(model, device=self.device)

    def score(self, pairs: list[tuple[str, str]]) -> list[float]:
        if not pairs:
            return []
        return [float(s) for s in self.ce.predict(pairs, batch_size=self.batch_size)]
