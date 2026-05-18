"""Isolate the Java rerank-recall problem (#3) WITHOUT the full pipeline.

For the problem Java CVE clusters, rerank ALL ops tickets with the same Ettin
cross-encoder under different cluster-query-text variants, and report where the
KNOWN-true 'remove obsolete Java' tickets land (their rank + score vs the noise
that currently beats them). Lets us iterate canonical() text in ~minutes instead
of a 36-min full rerun.

Run (from python/): DEVICE=mps uv run python cve_coverage/tools/cluster_text_probe.py
Env: RERANK_MODEL (default ettin-400m), CLUSTERS (comma list), VARIANTS (comma list)
"""
import csv, re, os
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.dirname(HERE)            # package root: where the example CSVs live
RERANK_MODEL = os.environ.get("RERANK_MODEL", "cross-encoder/ettin-reranker-400m-v1")
CVE = re.compile(r"(?i)CVE-\d{4}-\d{3,7}")

# clusters to probe (default: the two with true Java tickets below rerank top-5)
CLUSTERS = os.environ.get("CLUSTERS", "CVE-2017-68001,CVE-2022-59237").split(",")
VARIANTS = os.environ.get("VARIANTS", "current,normalized").split(",")

# known true remediation tickets for Java-runtime CVEs ("remove/upgrade obsolete Java")
JAVA_TRUE = {"STXIO-5333140", "STXIO-8320100", "STXIO-674499", "STXIO-7926534", "STXIO-8393962"}


def load(p):
    with open(os.path.join(DATA, p), newline="") as f:
        return list(csv.DictReader(f))


def cluster_cve_tickets(stat):
    clusters = defaultdict(lambda: {"keys": [], "title": Counter(), "desc": Counter()})
    for r in stat:
        s = r.get("Summary") or ""
        t = r.get("Custom field (Vulnerability Title)") or ""
        d = r.get("Custom field (Misconfiguration Description)") or ""
        m = CVE.search(s + " " + t)
        key = m.group(0).upper() if m else s.strip()
        if not key:
            continue
        c = clusters[key]; c["keys"].append(r.get("Issue key"))
        if t.strip(): c["title"][t.strip()] += 1
        if d.strip(): c["desc"][d.strip()] += 1
    return clusters


# product-family -> remediation phrasing that mirrors how ops tickets are written.
# Encodes the domain fact "a vulnerable <X> is fixed by upgrading/removing <X>", which
# the cross-encoder can't infer from a raw CVE title like "Azul Zulu: ...: WebKit ...".
PRODUCT_HINTS = [
    (re.compile(r"(?i)\b(zulu|jrockit|graalvm|openjdk|\bjre\b|\bjdk\b|javafx|java se|java cpu|\bjava\b)"),
     "Obsolete vulnerable Java runtime. Remediation: remove or upgrade obsolete Java (JDK/JRE)."),
    (re.compile(r"(?i)\b(red hat enterprise linux|rhel)\b"),
     "Obsolete Red Hat Enterprise Linux version. Remediation: upgrade RHEL."),
    (re.compile(r"(?i)\blog4j\b"),
     "Obsolete Apache Log4j library. Remediation: upgrade Log4j."),
]


def product_hint(text):
    for rx, phrase in PRODUCT_HINTS:
        if rx.search(text):
            return phrase
    return ""


def canon_current(key, c):
    code  = "" if CVE.fullmatch(key) else re.sub(r"[_\-]+", " ", key).strip().lower()
    title = c["title"].most_common(1)[0][0] if c["title"] else ""
    desc  = c["desc"].most_common(1)[0][0] if c["desc"] else ""
    return ". ".join(p for p in (code, title, desc) if p).strip()[:600]


def canon_normalized(key, c):
    base = canon_current(key, c)
    hint = product_hint(base)
    return (hint + " " + base).strip()[:600] if hint else base


CANON = {"current": canon_current, "normalized": canon_normalized}


def main():
    stat, vmt = load("StatAnon.csv"), load("VmtAnon.csv")
    clusters = cluster_cve_tickets(stat)
    ops_text = [((r.get("Summary") or "") + ". " + (r.get("Description") or "")).strip()[:600] for r in vmt]
    ops_key  = [r.get("Issue key") for r in vmt]

    from sentence_transformers import CrossEncoder
    import torch
    device = os.environ.get("DEVICE") or ("mps" if torch.backends.mps.is_available()
             else "cuda" if torch.cuda.is_available() else "cpu")
    print(f"reranking {len(vmt)} ops with {RERANK_MODEL} on {device}\n")
    ce = CrossEncoder(RERANK_MODEL, device=device)

    for key in CLUSTERS:
        key = key.strip()
        c = clusters.get(key)
        if not c:
            print(f"{key}: not found"); continue
        print(f"=== {key}  ({len(c['keys'])} tickets) ===")
        for var in VARIANTS:
            q = CANON[var.strip()](key, c)
            scores = ce.predict([(q, t) for t in ops_text], batch_size=64)
            order = sorted(range(len(scores)), key=lambda i: -scores[i])
            rankpos = {ops_key[i]: r for r, i in enumerate(order, 1)}
            true_ranks = sorted((rankpos[k], k, float(scores[ops_key.index(k)]))
                                for k in JAVA_TRUE if k in rankpos)
            best = true_ranks[0] if true_ranks else None
            print(f"  [{var:10}] q={q[:70]!r}")
            print(f"    top5: " + ", ".join(f"{ops_key[i]}({scores[i]:.1f})" for i in order[:5]))
            if best:
                print(f"    best true Java ticket: {best[1]} rank={best[0]} score={best[2]:.1f}"
                      f"  (in top5: {best[0] <= 5})")
            print()


if __name__ == "__main__":
    main()


