"""Stage 2a — embedding retrieval.

`Embedder` is the pluggable interface; `LocalEmbedder` runs a sentence-transformers
model on-device (no API needed — the reranker already pulls in
sentence-transformers / torch). Keeping embeddings local means only the judge
talks to the model API, so a chat-only gateway (e.g. GitHub Copilot) is enough.
"""

from __future__ import annotations

from typing import Protocol

from .device import autodetect_device


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one L2-normalised vector per input text, in order."""
        ...


class LocalEmbedder:
    def __init__(self, model: str, device: str | None = None, batch_size: int = 64):
        from sentence_transformers import SentenceTransformer
        self.device = device or autodetect_device()
        self.batch_size = batch_size
        self.model_name = model
        self.st = SentenceTransformer(model, device=self.device)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        vecs = self.st.encode(texts, batch_size=self.batch_size,
                              normalize_embeddings=True, convert_to_numpy=True)
        return vecs.tolist()


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def retrieve(query_vecs: list[list[float]], doc_vecs: list[list[float]], topn: int) -> list[list[int]]:
    """For each query vector, the indices of its top-N nearest doc vectors."""
    out = []
    for qv in query_vecs:
        sims = sorted(((cosine(qv, doc_vecs[i]), i) for i in range(len(doc_vecs))), reverse=True)
        out.append([i for _, i in sims[:topn]])
    return out
