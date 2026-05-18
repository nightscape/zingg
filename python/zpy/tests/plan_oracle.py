"""Plan-driven test oracle — port of ``PlanOracle.scala``.

A ``Plan`` defines N "true" entities, each with K noisy row variants carrying a
CVE-YYYY-NNNNN id in the description (sometimes the summary). The model never
sees ``entity_id``; it is ground truth and a perfect oracle labeller.
"""

from __future__ import annotations

import itertools
import random
from dataclasses import dataclass

import pandas as pd

from zingg_py import features
from zingg_py.labeller import OracleLabeller
from zingg_py.schema import ZinggConf

_PRODUCTS = ["nginx", "openssl", "log4j", "spring-boot", "kubernetes",
             "postgres", "redis", "kafka", "envoy", "grpc"]
_SEVERITIES = ["low", "medium", "high", "critical"]
_PRIORITIES = ["P1", "P2", "P3", "P4"]
_CORE_TEMPLATES = [
    "remote code execution in %s", "buffer overflow affecting %s",
    "authentication bypass for %s", "privilege escalation in %s",
    "denial of service in %s", "memory corruption in %s",
    "SQL injection via %s", "path traversal in %s",
]
_SUMMARY_PREFIXES = ["[SECURITY] ", "URGENT: ", "Vuln: ", "", "Fix needed - ", "Audit finding - "]
_DESC_PREFIXES = ["Reported by scanner. ", "Detected via dependabot. ", "Found in audit. ",
                  "Customer reported: ", "Internal scan: ", ""]


@dataclass(frozen=True)
class PlanEntity:
    entity_id: int
    cve_id: str
    product: str
    core_text: str
    severity: str


@dataclass(frozen=True)
class PlanRow:
    row_id: int
    entity_id: int
    summary: str
    description: str
    priority: str


@dataclass(frozen=True)
class Plan:
    entities: tuple
    rows: tuple

    @property
    def truth(self) -> dict:
        return {r.row_id: r.entity_id for r in self.rows}

    def to_df(self, cfg: ZinggConf) -> pd.DataFrame:
        return pd.DataFrame(
            [{cfg.id_col: r.row_id, "summary": r.summary,
              "description": r.description, "priority": r.priority} for r in self.rows],
            columns=[cfg.id_col, "summary", "description", "priority"],
        )

    @property
    def labeller(self) -> OracleLabeller:
        return OracleLabeller(self.truth)


def _perturb(text: str, rng: random.Random) -> str:
    words = text.split(" ")
    if len(words) > 2 and rng.random() < 0.3:
        i = rng.randrange(len(words) - 1)
        words[i], words[i + 1] = words[i + 1], words[i]
    joined = " ".join(words)
    if rng.random() < 0.3 and len(joined) > 4:
        i = rng.randrange(len(joined))
        return joined[:i] + joined[i + 1:]
    return joined


def build(seed: int, n_entities: int, variants_per_entity: int) -> Plan:
    rng = random.Random(seed)
    entities = []
    for i in range(n_entities):
        year = 2018 + rng.randrange(8)
        num = rng.randrange(90000) + 10000
        cve = f"CVE-{year}-{num:05d}"
        product = _PRODUCTS[rng.randrange(len(_PRODUCTS))]
        core = _CORE_TEMPLATES[rng.randrange(len(_CORE_TEMPLATES))] % product
        severity = _SEVERITIES[rng.randrange(len(_SEVERITIES))]
        entities.append(PlanEntity(i, cve, product, core, severity))

    rows = []
    for e in entities:
        for v in range(variants_per_entity):
            row_id = e.entity_id * 1000 + v
            s_prefix = _SUMMARY_PREFIXES[rng.randrange(len(_SUMMARY_PREFIXES))]
            d_prefix = _DESC_PREFIXES[rng.randrange(len(_DESC_PREFIXES))]
            cve_in_summary = rng.random() < 0.5
            if cve_in_summary:
                summary = f"{s_prefix}{e.cve_id} — {_perturb(e.core_text, rng)}"
            else:
                summary = f"{s_prefix}{_perturb(e.core_text, rng)}"
            description = (f"{d_prefix}{_perturb(e.core_text, rng)}. Affects {e.product}. "
                          f"Severity: {e.severity}. Reference: {e.cve_id}.")
            base_pri = _PRIORITIES[e.entity_id % len(_PRIORITIES)]
            priority = _PRIORITIES[rng.randrange(len(_PRIORITIES))] if rng.random() < 0.2 else base_pri
            rows.append(PlanRow(row_id, e.entity_id, summary, description, priority))

    return Plan(tuple(entities), tuple(rows))


def _pairs_frame(cfg: ZinggConf, pairs, labels) -> pd.DataFrame:
    recs = []
    for (l, r), lab in zip(pairs, labels):
        recs.append({
            f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}": l.row_id,
            f"{ZinggConf.LEFT_PREFIX}summary": l.summary,
            f"{ZinggConf.LEFT_PREFIX}description": l.description,
            f"{ZinggConf.LEFT_PREFIX}priority": l.priority,
            f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}": r.row_id,
            f"{ZinggConf.RIGHT_PREFIX}summary": r.summary,
            f"{ZinggConf.RIGHT_PREFIX}description": r.description,
            f"{ZinggConf.RIGHT_PREFIX}priority": r.priority,
            cfg.label_col: lab,
        })
    df = pd.DataFrame(recs)
    return features.add_features(df, cfg)


def labeled_training_set(cfg: ZinggConf, plan: Plan, n_negatives: int = 40) -> pd.DataFrame:
    """Positives: ALL same-entity pairs. Negatives: random cross-entity pairs."""
    by_entity: dict = {}
    for r in plan.rows:
        by_entity.setdefault(r.entity_id, []).append(r)

    positives = []
    for rs in by_entity.values():
        positives.extend(itertools.combinations(rs, 2))

    rng = random.Random(0xBEEF)
    negatives = []
    ents = list(plan.entities)
    if len(ents) >= 2:
        for _ in range(n_negatives):
            ea = ents[rng.randrange(len(ents))]
            eb = ents[rng.randrange(len(ents))]
            if ea.entity_id == eb.entity_id:
                continue
            a = by_entity[ea.entity_id][rng.randrange(len(by_entity[ea.entity_id]))]
            b = by_entity[eb.entity_id][rng.randrange(len(by_entity[eb.entity_id]))]
            negatives.append((a, b))

    pairs = list(positives) + negatives
    labels = [1.0] * len(positives) + [0.0] * len(negatives)
    return _pairs_frame(cfg, pairs, labels)


def labeled_training_set_with_noise(cfg: ZinggConf, plan: Plan, n_negatives: int,
                                    noise_fraction: float, noise_seed: int) -> pd.DataFrame:
    clean = labeled_training_set(cfg, plan, n_negatives)
    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"

    def flip(row):
        lid, rid, label = int(row[l_id]), int(row[r_id]), row[cfg.label_col]
        r = random.Random(noise_seed ^ (lid * 0x9E3779B97F4A7C15) ^ rid)
        return 1.0 - label if r.random() < noise_fraction else label

    out = clean.copy()
    out[cfg.label_col] = clean.apply(flip, axis=1)
    return out


def pairwise_f1(predicted: dict, truth: dict):
    """Pairwise (precision, recall, f1) between predicted clusters and truth."""
    ids = list(truth.keys())
    tp = fp = fn = 0
    for a, b in itertools.combinations(ids, 2):
        same_truth = truth[a] == truth[b]
        pa, pb = predicted.get(a), predicted.get(b)
        same_pred = pa is not None and pb is not None and pa == pb
        if same_truth and same_pred:
            tp += 1
        elif same_pred and not same_truth:
            fp += 1
        elif same_truth and not same_pred:
            fn += 1
    precision = 1.0 if tp + fp == 0 else tp / (tp + fp)
    recall = 1.0 if tp + fn == 0 else tp / (tp + fn)
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return precision, recall, f1
