"""CVE-cluster -> ops-ticket coverage pipeline.

Two stages:
  Stage 1  CLUSTER the StatistiX vulnerability tickets (deterministic CVE/category
           regex, or zingg-py record linkage) into one cluster per distinct vuln.
  Stage 2  LINK each cluster to the operations ticket(s) that remediate it, via
           retrieve (embedding) -> rerank (cross-encoder) -> judge (LLM).

The embedder, reranker and judge are pluggable behind small protocols
(`Embedder`, `Reranker`, `Judge`); see `config.py` for the knobs and `cli.py`
for the command-line entry point.
"""
