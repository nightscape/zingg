"""Fast judge-tuning loop: re-judge the cached rerank candidates and score against
the STRICT ground truth -- WITHOUT re-running the ~36-min embed+rerank.

Reads the (cluster, ops_key) pairs from coverage_candidates_lmstudio.csv, rebuilds
the cluster canonical text (StatAnon) and full ops text (VmtAnon), judges each pair
with the configured prompt/model, then prints precision/recall/F1 vs strict labels
and the exact FPs/FNs. Use it to iterate the judge prompt cheaply.

Run (from python/):  JUDGE_MODEL=qwen3.5-4b PROMPT=v2 uv run python cve_coverage/tools/rejudge.py
Env:  OPENAI_BASE (default LocalAI :8080), JUDGE_MODEL, JUDGE_MAXTOK, JUDGE_TEMP,
      PROMPT (which prompt variant: v1=current baseline, v2=...), WORKERS (parallel calls),
      CANDIDATES (cached rerank candidates CSV; default coverage_candidates.csv in the package root)
"""
import csv, re, os, json, urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from cve_coverage.ground_truth_spec import STRICT

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.dirname(HERE)            # package root: example CSVs + pipeline outputs
CANDIDATES = os.environ.get("CANDIDATES", os.path.join(DATA, "coverage_candidates.csv"))
BASE = os.environ.get("OPENAI_BASE", "http://127.0.0.1:8080/v1")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "qwen3.5-4b")
JUDGE_MAXTOK = int(os.environ.get("JUDGE_MAXTOK", "2048"))
JUDGE_TEMP = float(os.environ.get("JUDGE_TEMP", "0.7"))
PROMPT = os.environ.get("PROMPT", "v1")
WORKERS = int(os.environ.get("WORKERS", "8"))
# LocalAI defaults to a RANDOM seed even at temp=0, so verdicts vary run-to-run on
# borderline pairs. A fixed seed restores determinism (verified via seed_probe).
SEED = int(os.environ.get("SEED", "42"))

CVE = re.compile(r"(?i)CVE-\d{4}-\d{3,7}")
VERDICT = re.compile(r"(?i)\b(yes|no|unsure)\b")


def load(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def post(path, payload, timeout=180):
    req = urllib.request.Request(BASE + path, method="POST",
            data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


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


def canonical(key, c):
    code  = "" if CVE.fullmatch(key) else re.sub(r"[_\-]+", " ", key).strip().lower()
    title = c["title"].most_common(1)[0][0] if c["title"] else ""
    desc  = c["desc"].most_common(1)[0][0] if c["desc"] else ""
    return ". ".join(p for p in (code, title, desc) if p).strip()[:600]


def ops_doc(r):
    return ((r.get("Summary") or "") + ". " + (r.get("Description") or "")).strip()[:600]


# ---- prompt variants -------------------------------------------------------
def prompt_v1(cluster_text, ops_text):
    return (
        "You decide whether an OPERATIONS ticket is the remediation for a "
        "VULNERABILITY cluster. A vulnerability cluster is the same finding repeated "
        "across many machines; an operations ticket describes an infrastructure/IaC "
        "change (Ansible, config, package upgrade, key rotation, certificate, ...) that "
        "fixes a class of vulnerabilities across machines.\n\n"
        f"VULNERABILITY CLUSTER:\n{cluster_text}\n\nOPERATIONS TICKET:\n{ops_text}\n\n"
        "Answer YES only if the ticket specifically addresses THIS vulnerability "
        "(same component, software, or misconfiguration). Generic maintenance, "
        "unrelated upgrades, testing, or routine infra work that merely shares a "
        "word is NO.\n"
        "End with exactly one word on its own line: YES, NO, or UNSURE."
    )


def prompt_v2(cluster_text, ops_text):
    return (
        "You are matching a security VULNERABILITY (found repeatedly across many "
        "machines) to the OPERATIONS ticket that actually REMEDIATES it. The real fix "
        "is usually applied once at the infrastructure level (package upgrade, config "
        "change, key rotation, certificate fix, OS/runtime upgrade, mount-option "
        "hardening, ...) and covers many machines.\n\n"
        f"VULNERABILITY:\n{cluster_text}\n\nOPERATIONS TICKET:\n{ops_text}\n\n"
        "Reason in 1-2 short sentences, then answer. Steps:\n"
        "1. Name the specific software / component / misconfiguration the vulnerability "
        "is about. Use product knowledge: e.g. Azul Zulu, JRockit, Oracle Java SE, "
        "GraalVM, OpenJDK are all the JAVA runtime; RHEL is the Linux OS; Log4j is a "
        "Java logging library (NOT the JDK).\n"
        "2. Decide if the ops ticket UPGRADES, REPLACES, REMOVES, RECONFIGURES, or "
        "ROTATES that same software/component, or implements that exact control. "
        "'Remove/upgrade obsolete Java' fixes a Java CVE; 'Rotate ... keys' fixes a "
        "key-rotation finding; fixing a certificate's names fixes a CN-mismatch.\n"
        "3. These are NOT remediations -> answer NO: copying/migrating a host, a "
        "generic reboot or routine quarterly patch, risk-tracking / verification / "
        "crosscheck tickets, or a ticket that merely RESTATES the vulnerability without "
        "fixing it. A ticket that only shares a topic word is NO.\n"
        "End with exactly one word on its own line: YES, NO, or UNSURE."
    )


def prompt_v3(cluster_text, ops_text):
    return (
        "You are matching a security VULNERABILITY (found repeatedly across many "
        "machines) to the OPERATIONS ticket that actually REMEDIATES it. The real fix "
        "is applied once at the infrastructure level and covers many machines.\n\n"
        f"VULNERABILITY:\n{cluster_text}\n\nOPERATIONS TICKET:\n{ops_text}\n\n"
        "Answer YES only if the ticket performs a concrete fixing ACTION ON THE EXACT "
        "software/component/misconfiguration the vulnerability is about.\n"
        "Identify the component first (use product knowledge: Azul Zulu, JRockit, "
        "Oracle Java SE, GraalVM, OpenJDK = the JAVA runtime; RHEL = the Linux OS; "
        "Log4j = a Java logging library, NOT the JDK). Then check the ticket acts on "
        "THAT component: upgrade/remove/replace it, rotate THOSE keys, fix THAT "
        "certificate, harden THAT setting.\n\n"
        "Answer NO if the ticket is any of these, even when it mentions the same topic:\n"
        "- copying, cloning, or migrating a host/server (e.g. 'Copy <host>')\n"
        "- a reboot, or routine/scheduled/quarterly OS patching not aimed at this CVE\n"
        "- risk tracking, verification, crosscheck, recertification, or status tickets\n"
        "- a ticket that only RESTATES the vulnerability without fixing it\n"
        "- work on a DIFFERENT component/asset that merely shares a word or theme\n\n"
        "Reason in ONE short sentence naming the component and the action, then end "
        "with exactly one word on its own line: YES, NO, or UNSURE."
    )


PROMPTS = {"v1": prompt_v1, "v2": prompt_v2, "v3": prompt_v3}
PRESENCE = float(os.environ.get("PRESENCE", "0"))


def judge(cluster_text, ops_text):
    prompt = PROMPTS[PROMPT](cluster_text, ops_text)
    payload = {"model": JUDGE_MODEL, "messages": [{"role": "user", "content": prompt}],
               "max_tokens": JUDGE_MAXTOK, "temperature": JUDGE_TEMP, "seed": SEED,
               "presence_penalty": PRESENCE,
               "enable_thinking": False, "metadata": {"enable_thinking": "false"}}
    msg = post("/chat/completions", payload)["choices"][0]["message"]
    for text in (msg.get("content") or "", msg.get("reasoning_content") or ""):
        found = VERDICT.findall(text)
        if found:
            return {"yes": 1, "no": 0, "unsure": -1}[found[-1].lower()]
    return -1


def main():
    stat, vmt = load(os.path.join(DATA, "StatAnon.csv")), load(os.path.join(DATA, "VmtAnon.csv"))
    clusters = cluster_cve_tickets(stat)
    canon = {k: canonical(k, c) for k, c in clusters.items()}
    ops_text = {r.get("Issue key"): ops_doc(r) for r in vmt}
    cands = load(CANDIDATES)

    def work(r):
        return judge(canon[r["cluster"]], ops_text[r["ops_key"]])

    print(f"judging {len(cands)} cached pairs with {JUDGE_MODEL} prompt={PROMPT} ...")
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        verdicts = list(ex.map(work, cands))

    # dump per-pair verdicts so the confidence gate (gate_analysis.py) can be designed
    # against real judge output joined with rerank score + strict truth.
    vpath = os.path.join(HERE, "rejudge_verdicts.csv")
    with open(vpath, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["cluster", "n_tickets", "rank", "ops_key", "rerank_score",
                    "verdict", "truth_strict"])
        for r, v in zip(cands, verdicts):
            w.writerow([r["cluster"], r["n_tickets"], r["rank"], r["ops_key"],
                        r["rerank_score"], {1: "match", 0: "non-match", -1: "unsure"}[v],
                        1 if (r["cluster"], r["ops_key"]) in STRICT else 0])

    tp = fp = fn = tn = 0
    fps, fns = [], []
    for r, v in zip(cands, verdicts):
        pred = v == 1
        truth = (r["cluster"], r["ops_key"]) in STRICT
        if pred and truth: tp += 1
        elif pred and not truth: fp += 1; fps.append((r["cluster"], r["ops_key"]))
        elif not pred and truth: fn += 1; fns.append((r["cluster"], r["ops_key"], v))
        else: tn += 1
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    # recall vs ALL strict labels among the retrieved (4 not-retrieved are out of reach here)
    print(f"\n{JUDGE_MODEL} prompt={PROMPT}: TP={tp} FP={fp} FN={fn} TN={tn}")
    print(f"precision={prec:.2f} recall={rec:.2f} F1={f1:.2f}  (retrieved pairs vs strict)\n")
    print(f"false positives ({len(fps)}):")
    for c, k in fps: print(f"  {c[:42]:42} {k}")
    print(f"false negatives ({len(fns)}):")
    for c, k, v in fns:
        print(f"  {c[:42]:42} {k}  (verdict={'unsure' if v==-1 else 'no'})")


if __name__ == "__main__":
    main()
