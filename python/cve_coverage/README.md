# CVE-cluster ↔ operations-ticket coverage mapping

Match automated vulnerability tickets to the operations (remediation) ticket that
fixes them, so we know **which clusters of CVE tickets already have an ops ticket
and which still need one** (the missing ones will later be auto-created with
backlinks).

This directory is an investigation + working prototype, not yet a productized
Zingg feature. Read this whole file before continuing the work.

---

## 1. The task

- **StatistiX** (`StatAnon.csv`, 819 rows) — automated vulnerability findings, ~1
  ticket per VM. Massively duplicated: only ~14 distinct CVEs / ~38 category codes
  across the 819 rows (the same vuln repeated across hundreds of hosts).
- **Jira / STXIO** (`VmtAnon.csv`, 530 rows) — operations tickets. The real fix is
  applied once at the IaC level (Ansible, config, upgrade, key rotation, …) and
  covers many VMs at once.
- **Goal:** for each *cluster* of duplicate CVE tickets, decide whether a
  corresponding ops ticket already exists. Relationship is **one ops ticket ↔
  many CVE clusters** and possibly **N:M**.
- **Constraint:** we must infer the link from *content* — we cannot use existing
  backlinks, because creating those links is exactly the product. (Only ~3
  explicit backlinks exist in the data anyway.)

## 2. What the data investigation established

- **No shared structural key between the two systems.** CVEs appear only on the
  StatistiX side (496 rows) and in **zero** of the 378 Jira columns. Hostnames
  (`*.internal`) overlap only 8× and are coincidental co-location, not identity.
  Only 3 Jira tickets cite a StatistiX key.
- **The strong signal is *within* StatistiX** (dedup by CVE/category is near
  trivial), **not between** the systems. The cross-system link is **semantic**
  (e.g. GCPSCC `SERVICE_ACCOUNT_KEY_NOT_ROTATED` ↔ Jira "Rotate expiring service
  account keys"; Java CVEs ↔ "remove obsolete Java"), with almost no lexical
  overlap and no CVE in the ops text.
- **Zingg's clustering cannot do this safely.** It thresholds pairwise scores then
  takes connected components; an ops ticket is a deliberate high-degree hub, so
  one false edge transitively merges unrelated clusters, and a hard partition
  can't represent N:M (it flattens it). Hence the **retrieve → rerank → judge**
  design below, which emits **bipartite** links instead of a partition.

## 3. The pipeline (`cve_coverage` package)

```
Stage 1  cluster CVE tickets       deterministic CVE/category regex, or zingg-py
Stage 2a retrieve (embeddings)     Qwen3-Embedding-0.6B cosine -> top-N candidates
Stage 2b rerank (cross-encoder)    Ettin reranker re-scores -> top-K
Stage 2c judge (LLM)               YES / NO / UNSURE per (cluster, candidate)
```

The package (`cve_coverage/`) puts each stage in its own module behind a small
interface — `Embedder` (`retrieval.py`), `Reranker` (`rerank.py`), `Judge`
(`judge.py`) — so models swap with a flag or a new backend class. `coverage_cli.py`
is the runnable entry point; see `coverage_pipeline.md` for the module map.

Output (bipartite, N:M-friendly), written to `--out-dir`:
- `coverage_map.csv` — one row per cluster: `covered? | linked ops keys`.
- `coverage_candidates.csv` — every (cluster, candidate) with rerank score + verdict.
- `coverage_members.csv` — cluster→ticket membership (for partition-independent scoring).

The reranker replaces lexical blocking (there is no shared key to block on); the
LLM makes the decision the uncalibrated rerank score can't.

### Install (reproducible on any machine)

`examples/security/` is a `uv`/pip project (`pyproject.toml`). The embedder and
reranker run **locally** via `sentence-transformers` (the only heavy dep, pulls
torch); only the judge calls a chat API. So on a fresh machine:

```bash
cd examples/security
uv run cve-coverage --help                 # installs deps + the `cve-coverage` command
uv run --extra zingg cve-coverage run --cluster zingg   # adds zingg-py + pandas
```

(The first run downloads torch and the embedding/reranker models — a few GB.)

### Config-driven pipeline (`--conf`, default `config.json`)

The *schema* and the *DAG* live in `config.json`: named `data` sources each carry
their `fieldMapping` (CSV column → internal field name), and a `pipeline` section
wires those names through `cluster` / `link` steps. The CLI only rebinds a source
**name → path**, because that is the part that changes every run (the CSVs are
timestamped):

```bash
# run the whole DAG; rebind a timestamped file without editing config.json
uv run cve-coverage run statistix=StatAnon_2026-06-16.csv jira=VmtAnon_2026-06-16.csv

# run one step in isolation (upstream clusters load from the cached intermediate)
uv run cve-coverage run --only clusters statistix=StatAnon_2026-06-16.csv   # Stage 1
uv run cve-coverage run --only coverage  jira=VmtAnon_2026-06-16.csv        # Stage 2
```

A `cluster` node names one `input` source; a `link` node names its `clusters`
inputs and the source to match `against`. Multiple cluster nodes feeding one link
node **accumulate** — e.g. two vulnerability sources both linked against `jira`:

```jsonc
"pipeline": [
  { "id": "cs",  "step": "cluster", "input": "statistix", "strategy": "deterministic" },
  { "id": "c3",  "step": "cluster", "input": "source3",   "strategy": "zingg" },
  { "id": "cov", "step": "link", "clusters": ["cs", "c3"], "against": "jira", "topn": 50, "topk": 5 }
]
```

The cluster intermediate (`cluster_<id>.json`) carries the title/desc counters, so
an isolated `link` step rebuilds each cluster's reranker query (`canonical`) intact.
A config with no `pipeline` key synthesises the default two-node DAG from a
two-source `link` config (cluster `data[0]` → link against `data[1]`). Model flags
(`--base/--judge-model/--device/...`) stay CLI/env-driven.

### How to run — GitHub Copilot (or any OpenAI-compatible chat API)

Only the judge needs the API. Pass the bearer token via `--api-key`/`OPENAI_API_KEY`
and any gateway headers via repeatable `--header KEY=VALUE`. Do **not** set
`--judge-disable-thinking` against a strict gateway (it sends non-standard fields):

```bash
cd examples/security
OPENAI_API_KEY=$COPILOT_TOKEN uv run cve-coverage run \
  --base https://api.githubcopilot.com \
  --judge-model gpt-4o \
  --header "Copilot-Integration-Id=vscode-chat" \
  --header "Editor-Version=cve-coverage/0.1"
uv run cve-coverage score
```

### How to run — LocalAI / LM Studio (local judge)

LocalAI at `:8080` is the original judge backend. It needs no key, but does need
the thinking-off switches, so add `--judge-disable-thinking`:

```bash
cd examples/security
uv run cve-coverage run \
  --base http://127.0.0.1:8080/v1 --judge-model qwen3.5-4b \
  --rerank-model cross-encoder/ettin-reranker-400m-v1 --judge-disable-thinking
uv run cve-coverage score
```

The judge is deterministic by default (`temperature 0` + fixed `--judge-seed 42`).
Every env var below is just a flag *default* — the same config is settable as
`--base/--embed-model/--judge-model/--rerank-model/--topn/…`. Inputs come from
`--conf` (default `config.json`); rebind a source path with a `name=path`
positional and `--out-dir` redirects outputs. Add `--cluster zingg` to cluster
with zingg-py, `--dry-run` to stop after the cluster steps.

Env knobs (all optional, defaults in `config.py`):

| var | meaning | notes |
|-----|---------|-------|
| `OPENAI_BASE` | judge chat endpoint | Copilot/OpenAI URL, or LocalAI `:8080/v1` / LM Studio `:1234/v1` |
| `OPENAI_API_KEY` | judge bearer token | omit for LocalAI/LM Studio |
| `EMBED_MODEL` | **local** sentence-transformers id | default `Qwen/Qwen3-Embedding-0.6B` (runs on-device, no API) |
| `JUDGE_MODEL` | chat model id | e.g. `gpt-4o` (Copilot) / `qwen3.5-4b` (LocalAI) |
| `RERANK_MODEL` | HF cross-encoder | `cross-encoder/ettin-reranker-{17m,32m,68m,150m,400m,1b}-v1` |
| `TOPN` | candidates kept by embedding retrieval | default `50` pre-filter (rerank ~1800 pairs, fast); `530` = rerank ALL, no faster recall |
| `TOPK` | candidates judged per cluster | default `5` |
| `DEVICE` | embed/rerank torch device | auto-detects `mps`; **must not be CPU** (see gotchas) |
| `JUDGE_MAXTOK` | judge token budget | `2048` (covers a reasoning judge; non-thinking stops early) |
| `JUDGE_TEMP` / `JUDGE_SEED` | judge sampling | `0` / `42` — both needed for determinism (see gotcha §5.4) |

The cluster pool fed to a link step is capped by that step's `cluster_limit`
param in the config (`0`/absent = all); zingg training rows with `--cap N`.

### Other scripts here
- `make_labels.py` — builds the human labeling sheet (`ground_truth.md`, joins full
  ops descriptions) from the rerank candidates.
- `ground_truth_spec.py` — the hand labels (STRICT / RELATED / EXTRA); consumed by
  `coverage score` (§4).
- `rejudge.py` — fast judge-tuning loop: re-judges the cached candidates (no rerank)
  and scores vs ground truth. Holds the prompt variants (v1/v2/v3).
- `gate_analysis.py` — evaluates confidence-gate rules (rank / score / gap) and shows
  rank-1 wins (§5.3).
- `cluster_text_probe.py` — reranker-only probe for the cluster `canonical()` text;
  reports where the true tickets land under each text variant (drove the §4 Java fix).
- `coverage_map.py` — earlier prototype of the same idea using `fastembed`'s jina
  reranker + a generic `ZINGG_AI_*` OpenAI endpoint for the judge. Superseded by
  the `cve_coverage` package but useful as a fastembed reference.
- `rerank_probe.py` — feasibility probe: does a reranker separate the true ops
  ticket from the 530? (It does, for clear cases.)
- `config.json` — the Zingg-style config that now **drives this pipeline**: its
  named sources + `fieldMapping` supply the schema and its `pipeline` section the
  cluster→link DAG (see "Config-driven pipeline" above). Note its `"link": true`
  is *not* run as Zingg cross-source linkage (the wrong tool here, per §2); the
  `pipeline` section replaces that with the retrieve→rerank→judge stages.

## 4. Latest results (Ettin-400m + qwen3.5-4b on LocalAI), measured

There is now a **hand-labeled ground truth** and a scorer, so results are measured,
not eyeballed. Labels use two axes (see §7): `strict` (this existing ticket genuinely
remediates this vuln — the bar for "already covered") and `related` (close enough to
batch into a new ticket / suggest extending the existing one).

- `ground_truth_spec.py` — the hand labels (STRICT / RELATED / EXTRA).
- `coverage score` (`cve_coverage/scoring.py`) — re-aggregates the pipeline's
  clusters back to the true CVE/category via `coverage_members.csv` and reports the
  partition-independent precision/recall/F1 with the exact FPs/FNs.

**Cluster-level coverage (the product metric) = P/R/F1 0.92 / 0.92 / 0.92**
(13 / 36 covered; 1 FP `snmp-cleartext-credential`, 1 FN `ADMIN_SERVICE_ACCOUNT`).
That is the rank-1 gate (gotcha §5.3); raw "any judged match" is 0.71. The arc:
0.71 (baseline) → 0.82 (judge v3 + seed + gate) → **0.92** (+ TOPN=50 + product-hint
cluster text). All three known backlinks still recovered without using them.

What got fixed vs. the old write-up:
- **Java false negatives fixed.** `CVE-2017-68001` and `CVE-2022-59237` (true
  "remove obsolete Java" tickets below rerank top-5) are now COVERED — the fix was
  the cluster **canonical text**, not the model: a raw CVE title ("Azul Zulu: …:
  WebKit …") gives the cross-encoder no bridge to "remove obsolete Java", so
  `canonical()` now prepends a product→remediation hint (see `cluster_text_probe.py`:
  true ticket rank 10→1). The diagnosis split the bug in two — `CVE-2009-70199` was a
  *judge* miss (true ticket at rank 1, rejected) fixed by prompt v3; the others were
  *rerank* recall misses fixed by the cluster text.
- **Hubs handled by the rank-1 gate**, not a score threshold. `4.2.22 sshd` and the
  CIS-benchmark hubs now correctly read "needs ticket". `STXIO-554155` is a genuine
  match for exactly ONE cluster (`ADMIN_SERVICE_ACCOUNT`) and a magnet everywhere
  else.
- **Log4j stays "needs ticket"** even though the hint surfaces the Java tickets — the
  v3 judge knows Log4j-the-library ≠ the JDK.

Pair-level F1 is lower (~0.66) because N:M clusters have several real ops tickets
across ranks; the gate decides the cluster boolean, the candidates file keeps the
bipartite links (flagged `high` / `review`).

## 5. Gotchas discovered (don't relearn these)

1. **Disabling "thinking" on Qwen3.x reasoning models:**
   - **LocalAI** — reliable: top-level `"metadata": {"enable_thinking": "false"}`
     (the value is the **string** `"false"`). This is what the script sends.
   - **LM Studio (MLX build)** — *flaky*: `enable_thinking:false` as a top-level
     field is honored only sometimes; `/no_think`, `reasoning_effort`, and
     nesting under `chat_template_kwargs` are all ignored. Don't rely on it.
   - If thinking is on, a judgment burns ~1400+ tokens and the verdict lands in
     `content` only after the reasoning completes (or in `reasoning_content` if
     truncated). The judge parser handles both; `JUDGE_MAXTOK=2048` covers it.
2. **Reranker device:** `sentence-transformers` defaults to **CPU** on macOS.
   Reranking all 19k pairs on CPU looks hung ("no progress"). The script
   auto-selects **MPS** (Apple GPU). Speed is now a non-issue: `TOPN=50` reranks
   only ~1800 pairs (50 × 36) instead of all 19k — seconds, not 36 min. Verified
   the top-50 embedding pre-filter does not drop true tickets (cluster F1 unchanged
   at 0.92).
3. **Reranker scores are uncalibrated across clusters** — a positive logit ≈
   "leaning relevant" but the absolute value is not comparable cluster-to-cluster.
   So the confidence gate uses **within-cluster rank**, not an absolute threshold:
   `gate_analysis.py` shows rank-1 beats every score/gap threshold (cluster F1 0.82
   vs ≤0.67 for score≥6/7 or gap gates). The coverage script implements this: a
   cluster is "covered" only if its rank-1 reranked candidate is a judged match;
   lower-ranked matches are still emitted as bipartite links flagged `review`.
4. **The judge is nondeterministic even at `temperature=0`.** LocalAI defaults to a
   **random seed**, so verdicts flip run-to-run on borderline pairs (two identical
   runs differed by ~23/180 pairs; F1 swung 0.58–0.68). Fix: send a fixed `seed`
   (verified: `seed=42` → 0 flips). Both `temperature:0` **and** `seed` are needed —
   for a stable coverage report and for being able to measure prompt changes at all.
5. **Reranker device:** see #2. — Ettin rerankers are **English-only** cross-encoders
   (fine here); Qwen3-Embedding is multilingual (handles any German). The judge must
   be a capable instruct model — a 2.6B (`lfm2`) rubber-stamps hubs; `qwen3.5-4b`
   with prompt **v3** (identify-component-then-action + a hard NO-list for host
   copies / reboots / routine patching / vuln-restating tickets) is the working judge
   (F1 0.43 → ~0.6, recall 0.92). Tune it on cached candidates with `rejudge.py`
   (2–6 min, no rerank).

---

## 6. HANDOFF — how to continue

**Done** (handoff items 1–4 from the prior write-up): ground truth + scorer built;
judge tuned (prompt v3, deterministic via seed); Java false negatives fixed via the
cluster-text product hint; hubs handled by the rank-1 gate; reranking sped up with
`TOPN=50`. Cluster-level F1 is **0.92**. See §4/§5.

Remaining, highest-value first. All work is in the `cve_coverage` package unless noted.

1. **Expand the ground truth.** It is currently ~24 strict/related pairs over the
   reranked top-5, hand-labeled by the assistant under the §7 rubric — enough to
   measure cluster coverage but thin for the long tail. Add more clusters and re-tune
   against the larger set. The two remaining errors are user-confirmed **judge**
   errors (labels are right), fixable in the prompt but NOT worth chasing on a thin
   self-labeled set (overfitting):
   - `ADMIN_SERVICE_ACCOUNT`→`STXIO-554155` is a real match (judge FN). The v3
     "restating ticket → NO" rule misfires: it should only reject restating a
     *different* vuln, not the matching finding's own ops ticket.
   - `snmp-cleartext-credential`→`STXIO-4776379` is a real non-match (judge FP):
     same control class but a different system (Informatica truststore ≠ SNMP), and
     they can't be fixed in one swoop, so it is neither `strict` nor `related`.

2. **Productize the "extend an existing ticket" idea (the `related` axis).** The
   labels already separate strict-covered from `related` (batch / "you're touching
   this anyway, also handle this CVE"). Surface `related` matches as suggested
   ticket-extensions rather than coverage — this is where `snmp-cleartext` and the
   `/var` mount-hardening pairs belong.

3. **Stronger models if quality plateaus.** Reranker: `ettin-reranker-1b-v1` or
   `jinaai/jina-reranker-v2-base-multilingual`. Judge: a larger Qwen3 (27B / 35B-a3b
   are loaded in LocalAI) for the borderline CIS-benchmark calls. NB: the 35B-a3b has
   a cold-start that blows a 180 s request timeout — warm it first / raise the timeout.

4. **Decide Python vs Scala** (see top-level discussion). The algorithm is now
   validated, so the deferred language call can be made: if this productizes into
   Zingg, port to Scala/JVM and serve the reranker over HTTP (LocalAI/TEI) so the
   whole pipeline is HTTP + deterministic logic, then drop Python; if it stays an
   offline analysis tool, keep Python.

5. **(Optional) productionize into Zingg.** Reuse the existing `LlmLabeller`
   (`ZINGG_AI_*`) as the judge and add reranker-based candidate generation, but
   **keep the bipartite output** — do NOT route this through Zingg's
   connected-components clustering (§2).

### State of the world
- Working backend: **LocalAI** at `127.0.0.1:8080` (judge + embeddings). LM Studio
  at `:1234` also works but its thinking toggle is flaky.
- Run + score: see §3. Outputs are `coverage_map.csv` (one row/cluster, `covered` +
  linked ops), `coverage_candidates.csv` (every candidate with `verdict` +
  `confidence`), and `coverage_members.csv`. `coverage score` prints P/R.
- The pipeline is now deterministic (judge seed) and measured (cluster F1 0.92). The
  "what counts as a true match" rubric is settled and written down in §7.

---

## 7. Labeling rubric (what counts as a match)

Two axes, because there are two different products:

- **`strict`** — this *existing* ops ticket genuinely remediates *this specific*
  vulnerability (same component / software / misconfiguration). This is the bar for
  claiming a cluster is **already covered**. Same control *class* but a different
  asset/scope does **not** qualify — we don't pretend an almost-right ticket closes
  the CVE. (e.g. nodev/nosuid on `/var` ≠ the CIS rule for `/dev/shm`.)
- **`related`** — close enough to **batch into a newly-created ops ticket**, or to
  **suggest extending the existing ticket** ("you're touching this anyway — mind also
  handling this CVE?"). Looser; same-control-class counts. Borderline same-class pairs
  are `related`, not `strict`.

Edge rules:
- A ticket that only **restates** the vulnerability (no fix) is not a remediation.
- **Routine/scheduled OS patching & reboots** do **not** count as strict coverage for
  OS/kernel CVEs ("could be the fix sometimes, but don't rely on it"). They are tagged
  `note=routine-os` so the assumption can be revisited (sensitivity analysis).
- "Remove/upgrade obsolete Java" (any environment variant) remediates **any** Java-
  runtime CVE (Zulu / JRockit / Oracle Java SE / GraalVM / OpenJDK).

The labels live in `ground_truth_spec.py` (`STRICT`, `RELATED`, `EXTRA`, `NOTES`).
`EXTRA` = true links the *old* reranker failed to surface; kept so recall is measured
even when retrieval misses (the §4 Java fix moved them into the retrieved set).
