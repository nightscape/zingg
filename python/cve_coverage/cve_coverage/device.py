"""Shared torch device selection for the local models (embedder + reranker)."""

from __future__ import annotations


def autodetect_device() -> str:
    import torch
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"
