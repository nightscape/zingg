"""Domain types shared across the pipeline stages."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field


@dataclass
class Cluster:
    """One group of duplicate vulnerability tickets that should be remediated
    together. `key` is the human-readable cluster id (a CVE id or category
    summary); `ticket_ids` are the member StatistiX issue keys; `title` / `desc`
    count the distinct vulnerability-title / misconfiguration-description strings
    seen in the group (the most common of each feeds the cluster text)."""

    key: str
    ticket_ids: list[str]
    title: Counter = field(default_factory=Counter)
    desc: Counter = field(default_factory=Counter)

    @property
    def size(self) -> int:
        return len(self.ticket_ids)

    def to_dict(self) -> dict:
        """Serialisable form for the cached cluster intermediate. Carries the
        title/desc counters, not just ids, so an isolated `link` step can rebuild
        the cluster's canonical text (the reranker's query)."""
        return {"key": self.key, "ticket_ids": self.ticket_ids,
                "title": dict(self.title), "desc": dict(self.desc)}

    @classmethod
    def from_dict(cls, d: dict) -> "Cluster":
        return cls(key=d["key"], ticket_ids=d["ticket_ids"],
                   title=Counter(d["title"]), desc=Counter(d["desc"]))
