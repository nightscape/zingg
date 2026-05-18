# Full coverage pipeline — clustering + rerank/judge linkage

The `cve_coverage` package wires the two validated halves together, using each
where it's strong:

| Stage | Engine | Job |
|---|---|---|
| 1 — cluster | `--cluster deterministic` (CVE regex) or `--cluster zingg` (ported Zingg core) | dedup StatistiX rows into CVE/category clusters |
| 2 — link | retrieve → rerank → judge (`pipeline.run_linkage`) | link each cluster to its remediation ops ticket (bipartite) |

The `zingg` strategy trains zingg-py on weak labels from the CVE/category
heuristic (`OracleLabeller`), then clusters all 819 rows; `deterministic` groups
by the CVE/category regex directly. Stage 2 is identical for both.

The schema and the wiring are config-driven: `config.json` declares named `data`
sources (each with its `fieldMapping`) and a `pipeline` section that wires those
names through `cluster` / `link` steps. The CLI only rebinds a source name to a
fresh path (`coverage run statistix=Stat_2026-06-16.csv`). See README
"Config-driven pipeline" for the DAG grammar, accumulation, and `--only`.

## Code layout (`cve_coverage/`)

| Module | Role |
|---|---|
| `config.py` | `Source` / `Step` / `Pipeline` + `load_pipeline` (parse + validate + synthesise default DAG), `normalize_rows` (CSV columns → internal field names via `fieldMapping`), `ModelConfig` |
| `text.py` | cluster/ops text: `canonical`, product hints, CVE regex (internal field names) |
| `clustering.py` | `Clusterer` interface + `DeterministicClusterer` / `ZinggClusterer` (operate on internal field names) |
| `retrieval.py` | `Embedder` interface + `LocalEmbedder` (sentence-transformers), cosine retrieve |
| `rerank.py` | `Reranker` interface + `CrossEncoderReranker` (MPS/CUDA) |
| `judge.py` | `Judge` interface (optional `images` arg) + `OpenAIJudge` (prompt v3) |
| `pipeline.py` | `run_linkage` orchestration (one link step) + CSV writers |
| `runner.py` | DAG executor: runs cluster/link nodes, caches `cluster_<id>.json` intermediates (carry title/desc so `canonical` rebuilds), `--only` slicing |
| `scoring.py` | partition-independent metric |
| `cli.py` | `run` (loads pipeline, applies `name=path` bindings) / `score` subcommands |

The embedder, reranker and judge are pluggable behind their protocols — swap a
model with a flag (`--judge-model …`) or a larger backend by implementing the
interface. Embedder and reranker run locally (sentence-transformers); only the
judge calls an OpenAI-compatible chat API (`--base`, `--api-key`, `--header`). The
`Judge` interface already accepts an optional `images` argument so a vision judge
(VLM) can be added without touching callers; no VLM backend ships yet
(`OpenAIJudge` rejects images rather than ignoring them).

## Install & run

`examples/security/` is a `uv`/pip project (`pyproject.toml`, console script
`cve-coverage`). See the README for full install + Copilot/LocalAI invocations.

```bash
cd examples/security

# Stage 1 only — verify clustering, no models / API needed:
uv run --extra zingg cve-coverage run --cluster zingg --dry-run

# rebind a timestamped CSV without editing config.json (name from config's data[]):
uv run --extra zingg cve-coverage run --cluster zingg --dry-run statistix=StatAnon_2026-06-16.csv

# Full pipeline (downloads embed/rerank models; judge via a chat API):
OPENAI_API_KEY=$TOKEN uv run --extra zingg cve-coverage run \
  --cluster zingg --base https://api.githubcopilot.com --judge-model gpt-4o
```

Env vars are only flag *defaults*; the same config is available as
`--base/--api-key/--header/--embed-model/--judge-model/--rerank-model/--device/--topn/--topk/…`.
Inputs come from the config (`--conf`, default `config.json`); rebind a source's
path with a `name=path` positional, run a DAG slice with `--only id1,id2`, and
redirect outputs/intermediates with `--out-dir`. Other flags: `--cluster
{deterministic,zingg}`, `--block-strategy {tree,canopies}`, `--cap N` (zingg
training rows/cluster, 0 = all), `--judge-disable-thinking` (LocalAI/LM Studio
only). `topn`/`topk`/`cluster_limit` are per-link-step params in the config (CLI
`--topn/--topk` are the fallback defaults). Outputs `coverage_map.csv` (one
row/cluster, covered? + linked ops),
`coverage_candidates.csv` (every candidate with verdict), and
`coverage_members.csv` (cluster→ticket membership for the scorer).

## Verified — full run, scored

Full run (all 37 clusters, validated config: Ettin-400m, qwen3-embedding-0.6b,
qwen3.5-4b on LocalAI :8080, TOPN=50 TOPK=5), scored with
`python3 score.py coverage_candidates_zingg.csv ground_truth_zingg.csv`:

**Cluster-level gated coverage: P/R/F1 = 0.86 / 0.92 / 0.89** (14 covered) — vs
the deterministic-clustering baseline **0.92**.

The ~0.03 gap is a single Stage-1 artifact: zingg split `CVE-2017-68001`
(64 + 37), and the phantom `CVE-2017-68001#2` gets judged covered → 1 cluster FP.
The other two errors are identical to the deterministic baseline (the
user-confirmed judge errors): FP `snmp-cleartext-credential`→STXIO-4776379,
FN `ADMIN_SERVICE_ACCOUNT`→STXIO-554155. So Stage 2 behaves identically; the only
delta is the clustering split. Pair-level F1 is 0.62 (N:M Java hub matches many
clusters — expected, see README §4).

A quick 3-cluster smoke run (link `cluster_limit: 3`, Ettin-150m) was also 3/3 correct
(USER_MANAGED_SERVICE_ACCOUNT_KEY→STXIO-7407538, CVE-2009-70199→STXIO-674499 both
COVERED; CVE-2010-51108 needs-ticket).

## Note — zingg clustering ≠ exactly the regex (and the split is defensible)

zingg-py produces **37** clusters vs the deterministic 36: it split
`CVE-2017-68001` (101 rows) into 64 + 37.

**Investigated (the `--block-strategy canopies` experiment).** Switching the
final clustering from the single-key learned tree to the higher-recall multi-key
cold-start canopies produced the *identical* 37 clusters — so the split is **not**
a blocking-recall gap. The cause is in the data: the 101 rows are two distinct
products that merely share a summary CVE id —

| rows | Summary | Vulnerability Title |
|---|---|---|
| 64 | `jre-vuln-CVE-2017-68001` | Java CPU April 2024 **Oracle Java SE**, GraalVM |
| 37 | `azul-zulu-CVE-2017-68001` | **Azul Zulu**: CVE-2017-**92784**: … |

The Azul title even cites a *different* CVE. The deterministic regex lumps them by
the summary id; zingg's classifier separates them on lexical content — arguably
**more** correct. It only costs coverage *score* because the ground truth keys
clusters by the summary CVE, so the second component (`CVE-2017-68001#2`) has no
matching key and reads as a cluster false-positive.

**Takeaway:** #1 (canopies) is the wrong lever for this split. To recover the
0.92 *score*, either post-merge components that derive the same CVE/category key
(#2 — leans on the regex key), or accept the split as legitimate (both halves are
Java and link to the same "remove obsolete Java" tickets). `--block-strategy
canopies` remains available for the genuine case where the learned tree
under-blocks a cluster — it just isn't that case here.

## Partition-independent metric (`coverage score`)

A cluster-id join mis-keys when a clustering re-partitions the tickets (the
Oracle/Azul split) against the regex ground truth, so the extra component reads as
a phantom FP (→ 0.89). `coverage score` (`scoring.py`) fixes this by asking the
real question — *did each CVE ticket get linked to the correct ops ticket(s)?* —
re-aggregating the pipeline's clusters back to the TRUE CVE/category via the
`coverage_members.csv` membership file (written by `coverage run`). The clustering
partition no longer affects the score.

Run: `python3 examples/security/coverage_cli.py score
  [--candidates …] [--members …]`.

On the zingg run:

```
CLUSTER COVERAGE over 36 TRUE clusters (strict-covered=13):
  covered? vs strict-covered:  P/R/F1 = 0.92/0.92/0.92  covered=13
  cluster FPs: ['snmp-cleartext-credential']     # same judge errors as the
  cluster FNs: ['ADMIN_SERVICE_ACCOUNT']         # deterministic 0.92 baseline
```

The phantom `CVE-2017-68001#2` is gone → **0.92**, matching the deterministic
baseline: the split never cost coverage, only the mis-keyed score. The stricter
ops-exactness view also surfaces the genuine N:M tension (all-matches R=0.93 /
P=0.50; rank-1 gated picks one of several gold env-variant tickets).
