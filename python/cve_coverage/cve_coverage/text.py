"""Turning raw tickets into the short texts the embedder / reranker / judge see."""

from __future__ import annotations

import re

from .types import Cluster

CVE = re.compile(r"(?i)CVE-\d{4}-\d{3,7}")

# Product-family -> remediation phrasing that mirrors how ops tickets are written.
# A raw CVE title ("Azul Zulu: ...: WebKit Active Code Execution") gives the
# reranker no lexical/semantic bridge to "remove obsolete Java", so the true ops
# ticket lands at rank ~10 behind host-copy/reboot noise. Prepending the canonical
# remediation phrase lifts the true Java/RHEL tickets to ranks 1-4 (see the
# cluster_text_probe experiment: CVE-2017-68001 true ticket rank 10->1). The hint
# only fires on a keyword match, so clusters with no known product are unchanged.
# Log4j deliberately keeps a distinct phrase; the judge separates Log4j-the-library
# from the JDK.
PRODUCT_HINTS = [
    (re.compile(r"(?i)\b(zulu|jrockit|graalvm|openjdk|\bjre\b|\bjdk\b|javafx|java se|java cpu|\bjava\b)"),
     "Obsolete vulnerable Java runtime. Remediation: remove or upgrade obsolete Java (JDK/JRE)."),
    (re.compile(r"(?i)\b(red hat enterprise linux|rhel)\b"),
     "Obsolete Red Hat Enterprise Linux version. Remediation: upgrade RHEL."),
    (re.compile(r"(?i)\blog4j\b"),
     "Obsolete Apache Log4j library. Remediation: upgrade Log4j."),
]


def product_hint(text: str) -> str:
    for rx, phrase in PRODUCT_HINTS:
        if rx.search(text):
            return phrase
    return ""


def cluster_key(summary: str, vuln_title: str) -> str:
    """The deterministic cluster id for a row: its CVE id, else its category summary."""
    m = CVE.search(summary + " " + vuln_title)
    return m.group(0).upper() if m else summary.strip()


def canonical(cluster: Cluster) -> str:
    """The text representing a cluster to the embedder / reranker / judge."""
    key = cluster.key
    code = "" if CVE.fullmatch(key) else re.sub(r"[_\-]+", " ", key).strip().lower()
    title = cluster.title.most_common(1)[0][0] if cluster.title else ""
    desc = cluster.desc.most_common(1)[0][0] if cluster.desc else ""
    base = ". ".join(p for p in (code, title, desc) if p).strip()
    hint = product_hint(base)
    return ((hint + " " + base) if hint else base).strip()[:600]


def ops_doc(row: dict) -> str:
    """The text representing an operations ticket. Operates on internal field
    names (rows are normalised via the source's fieldMapping before they reach
    here), so no physical CSV column is hardcoded."""
    from .config import F_MISCONF, F_VULN_TITLE
    return ((row.get(F_VULN_TITLE) or "") + ". " + (row.get(F_MISCONF) or "")).strip()[:600]
