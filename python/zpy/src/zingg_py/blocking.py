"""Blocking — port of ``Blocking.scala``.

A learned decision tree over deterministic hash functions (not LSH/minhash).
The tree application is a per-row map over a pandas frame (the Spark UDF in the
original); the learner is pure.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Optional, Sequence

import pandas as pd

from . import hashing
from .hashing import Hash
from .schema import ZinggConf

BLOCK_COL = "z_block"


class BlockingTree:
    def key_for(self, row) -> Optional[str]:
        if isinstance(self, Leaf):
            return self.id
        segs = self.segments(row)
        return None if not segs else "|".join(segs)

    def segments(self, row) -> list[str]:  # pragma: no cover - overridden
        raise NotImplementedError


@dataclass(frozen=True)
class Leaf(BlockingTree):
    id: str

    def segments(self, row) -> list[str]:
        return []


class Node(BlockingTree):
    def __init__(self, field: str, hash: Hash, children: dict, fallback: BlockingTree):
        self.field = field
        self.hash = hash
        self.children = children
        self.fallback = fallback

    def __eq__(self, other):
        return (
            isinstance(other, Node)
            and self.field == other.field
            and self.hash == other.hash
            and self.children == other.children
            and self.fallback == other.fallback
        )

    def __hash__(self):
        return hash((self.field, self.hash, tuple(sorted(self.children.keys())), self.fallback))

    def __repr__(self):
        return f"Node({self.field!r}, {self.hash.name}, {self.children!r}, {self.fallback!r})"

    def segments(self, row) -> list[str]:
        v = _row_get(row, self.field)
        k = self.hash(v)
        sub = self.fallback if k is None else self.children.get(k, self.fallback)
        if k is None:
            return sub.segments(row)
        return [f"{self.hash.name}:{self.field}={k}"] + sub.segments(row)


def _row_get(row, field) -> Any:
    try:
        v = row[field]
    except (KeyError, IndexError):
        return None
    if v is None:
        return None
    # pandas surfaces missing string cells as float NaN
    if isinstance(v, float) and pd.isna(v):
        return None
    return v


# ── serialization ────────────────────────────────────────────────────────────

def to_json(tree: BlockingTree) -> str:
    return json.dumps(_to_node(tree), indent=2)


def from_json(s: str) -> BlockingTree:
    return _from_node(json.loads(s))


def _to_node(t: BlockingTree) -> dict:
    if isinstance(t, Leaf):
        return {"type": "leaf", "id": t.id}
    return {
        "type": "node",
        "field": t.field,
        "hash": t.hash.name,
        "children": {k: _to_node(v) for k, v in t.children.items()},
        "fallback": _to_node(t.fallback),
    }


def _from_node(n: dict) -> BlockingTree:
    if n["type"] == "leaf":
        return Leaf(n["id"])
    if n["type"] == "node":
        return Node(
            field=n["field"],
            hash=hashing.by_name(n["hash"]),
            children={k: _from_node(v) for k, v in n["children"].items()},
            fallback=_from_node(n["fallback"]),
        )
    raise ValueError(f"unknown blocking tree node type '{n.get('type')}'")


# ── canopies + application ─────────────────────────────────────────────────────

def cold_start_blockers(cfg: ZinggConf) -> list[BlockingTree]:
    """One single-field canopy per (blockable field, matcher, cold-start hash)."""
    out: list[BlockingTree] = []
    for f in cfg.fields:
        if not f.blockable:
            continue
        seen: set[str] = set()
        hs: list[Hash] = []
        for mt in f.match_types:
            for h in hashing.cold_start_hashes(mt):
                if h.name not in seen:
                    seen.add(h.name)
                    hs.append(h)
        for h in hs:
            out.append(Node(f.name, h, {}, Leaf("seed")))
    return out


def assign_blocks(df: pd.DataFrame, tree: BlockingTree, block_col: str = BLOCK_COL) -> pd.DataFrame:
    out = df.copy()
    out[block_col] = [tree.key_for(row) for _, row in df.iterrows()]
    return out


# ── learner ────────────────────────────────────────────────────────────────────

def learn(
    pos_pairs: Sequence[dict],
    cfg: ZinggConf,
    max_depth: int = 4,
) -> BlockingTree:
    candidates = hashing.candidates_for(cfg.fields)

    def build(pairs: list[dict], depth: int, used: set[str]) -> BlockingTree:
        if len(pairs) <= cfg.block_size or depth >= max_depth or not candidates:
            return Leaf(f"leaf_{depth}_{len(pairs)}")

        scored = []
        for (f, h) in candidates:
            if f"{f.name}|{h.name}" in used:
                continue
            agree = 0
            groups: set = set()
            for row in pairs:
                if f.name in row:
                    a, b = row[f.name]
                    ha, hb = h(a), h(b)
                    if ha is not None and ha == hb:
                        agree += 1
                    groups.add(ha)
                else:
                    groups.add(None)
            score = float(agree) - 0.1 * len(groups)
            scored.append(((f, h), score, agree))
        scored.sort(key=lambda t: -t[1])  # stable, descending by score

        if not scored:
            return Leaf(f"leaf_{depth}_{len(pairs)}")
        if scored[0][2] == 0:  # best agreement is zero
            return Leaf(f"leaf_{depth}_{len(pairs)}")

        (f, h), _, _ = scored[0]
        grouped: dict = {}
        for row in pairs:
            key = None
            if f.name in row:
                a, b = row[f.name]
                ha, hb = h(a), h(b)
                if ha is not None and ha == hb:
                    key = ha
            grouped.setdefault(key, []).append(row)

        new_used = used | {f"{f.name}|{h.name}"}
        children = {
            k: build(ps, depth + 1, new_used)
            for k, ps in grouped.items()
            if k is not None and ps
        }
        fallback_pairs = grouped.get(None, [])
        if len(fallback_pairs) == len(pairs):
            fb: BlockingTree = Leaf(f"leaf_{depth}_fallback")
        else:
            fb = build(fallback_pairs, depth + 1, new_used)
        return Node(f.name, h, children, fb)

    return build(list(pos_pairs), 0, set())
