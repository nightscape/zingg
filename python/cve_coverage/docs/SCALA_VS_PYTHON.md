# Python-only vs Scala-only for the coverage pipeline

Decision doc for consolidating the CVE-cluster ↔ ops-ticket coverage prototype onto
**one** language. The current state — a Python prototype (`coverage_lmstudio.py` et al.)
beside a Scala/Spark Zingg core — is the configuration we want to leave: two runtimes
is the "unnecessarily hard" deployment to avoid.

## The realization that shapes everything

Of the seven pipeline stages, **only one is language-sensitive**:

1. Load CSVs — pure logic
2. Cluster CVE tickets (regex / category) — pure logic
3. Cluster canonical text + product hints — pure logic
4. Embedding retrieval → cosine → top-N — **HTTP call** + vector math
5. Cross-encoder rerank — **the one in-process model**
6. LLM judge (YES/NO/UNSURE) — **HTTP call**
7. Rank-1 gate + output + scoring — pure logic

The **LLM judge and the embedder are always an external model server** (LocalAI/TEI)
reached over HTTP — nobody runs a 4B LLM in-process in either language. So stages 4 and
6 are identical in both languages. Stages 1–3 and 7 are pure logic that ports 1:1
(~300 lines of custom code total; libraries do embedding, rerank inference, LLM calls,
CSV/JSON). **The only stage that differs is the reranker** (stage 5) — small enough
(400M) to run in-process, which is where Python is native and the JVM needs a
workaround.

**Corollary:** if the reranker is also served over HTTP (TEI / Infinity / LocalAI rerank
endpoint), the two languages become *equivalent in capability* and the choice collapses
to ecosystem + integration.

---

## Python-only

**Shape:** what exists today — a few scripts, one process, reranker local via torch.

| Stage | Library | Custom code |
|---|---|---|
| CSV load | stdlib `csv` (current); `polars` if it grows | trivial |
| Cluster + canonical + product hints | stdlib `re`, `collections` | **yes** — `cluster_cve_tickets`, `canonical`, `PRODUCT_HINTS` (~35 lines, written) |
| Embeddings | `openai`/`httpx` to LocalAI, **or** local `sentence-transformers` (Qwen3-Embedding is on HF) | thin wrapper + cosine in `numpy` |
| Vector search | brute-force numpy (530 docs); `faiss`/`hnswlib` if it scales | trivial |
| **Reranker** | **`sentence-transformers` `CrossEncoder`** (current) / `FlagEmbedding` / `fastembed` | one line — the native advantage |
| Judge | `openai` SDK / `httpx` | the v3 prompt + YES/NO/UNSURE parse (written) |
| Gate + output + scoring | stdlib | **yes** — gate, `score.py`, tuning harnesses (written) |

**Custom code to write:** essentially none beyond what exists — it's already Python.
You'd only refactor the scripts into a package with a config object (`pydantic-settings`).

**Testing:** `pytest`. Pure functions (clustering, canonical, gate, scorer) unit-test
with tiny fixtures — fully deterministic, easy. Model-touching stages get
dependency-injected fakes (pass a stub `judge` fn) or HTTP mocks (`respx`). `score.py`
against `ground_truth_spec.py` *is* the eval/integration test already.

**Easy to add:** anything ML. Fine-tune the Ettin cross-encoder on the growing labels to
*replace* the prompt-judge with a trained classifier (sentence-transformers has the
training loop); swap to `jina-reranker-v2`; distill a fast bi-encoder; embedding cache;
ONNX-quantize for speed; an active-learning loop. The whole HF ecosystem is right there.

**Hard to add:** living inside Zingg. If productized, it's a *second runtime* next to the
Scala/Spark core — integration means a process boundary (subprocess / microservice /
py4j). No shared typed config with Zingg. Scaling clustering to millions of tickets means
PySpark + pandas UDFs (possible, different shape). Deploy artifact = Python env + torch
(~2 GB) + weights.

---

## Scala-only

**Shape:** a JVM app that fits the just-rewritten Scala core (Mill, `assemble/` for the
fat jar, zio-test, and `LlmLabeller` / `ZINGG_AI_*` already present).

| Stage | Library | Custom code |
|---|---|---|
| CSV load | `kantan.csv` / `scala-csv` (standalone); or Spark `read.csv` (Zingg already has Spark) | trivial |
| Cluster + canonical + hints | stdlib `scala.util.matching.Regex`, collections | **yes** — direct port of the Python (more verbose, type-safe) |
| Embeddings | HTTP via `sttp` + `circe`/`upickle`; **or** local via **DJL** (`ai.djl`) | wrapper + cosine |
| Vector search | hand-rolled cosine; Lucene HNSW / `hnswlib-java` if scaled | trivial |
| **Reranker** | **the crux** — three options below | varies a lot |
| Judge | `sttp`+`circe`, or **langchain4j** (OpenAI-compatible + structured output), or reuse `LlmLabeller` | prompt + parse |
| Gate + output + scoring | stdlib / zio-test spec | **yes** — port of gate + `score.py` |

**The reranker, three ways:**

1. **HTTP to a rerank server** (HuggingFace **TEI**, **Infinity**, or LocalAI's rerank
   backend). Scala just does `sttp` calls. *Cleanest code*, but adds a model-server
   sidecar — though LocalAI already runs for the judge, so this is near-free.
   **This is the option that makes Scala-only as capable as Python-only.**
2. **DJL in-process** (`ai.djl` + `ai.djl.huggingface.tokenizers` + ONNX-Runtime or
   PyTorch engine). Genuinely JVM-only at runtime — single process. Cost: export Ettin to
   ONNX once via Python `optimum` (Ettin is ModernBERT-based — verify op support on
   export), then ~100–200 lines of tokenize → `session.run` → extract-logit plumbing that
   `sentence-transformers` gives for free.
3. **ONNX Runtime Java directly** (`com.microsoft.onnxruntime`) — same as (2) with more
   manual wiring, fewer deps.

**Custom code to write:** the pure logic ports straightforwardly (more lines than Python,
but the types are a benefit here). The *extra* work over Python is entirely in the
reranker if you go in-process (options 2/3): tokenizer config, ONNX session management,
pooling/logit extraction. Option 1 (HTTP) avoids all of it.

**Testing:** **zio-test** (already the repo's choice — the `coverage/` lib uses Mill +
zio-test). Pure functions test cleanly, and property-based tests (zio-test gen /
scalacheck) are *nicer* here than in Python — you could even dog-food the coverage lib to
ensure every label-class is exercised. HTTP stages mock with `sttp`'s **stub backend**
(very clean). Judge/embedder become integration-tagged tests against live LocalAI. DJL
in-process inference is the hard-to-unit-test part — golden-file it.

**Easy to add:** everything that touches the core. Fold into the Zingg CLI; reuse
`LlmLabeller` as the judge for free; **single fat jar** via `assemble/`; scale the
clustering/blocking over **Spark** to large datasets; typed configs shared with Zingg;
deterministic concurrent judge fan-out via ZIO. This is the natural home if it becomes a
Zingg feature.

**Hard to add:** ML experimentation. New reranker = new ONNX export + op-support check
(or just repoint the HTTP server, if you took option 1). Fine-tuning the cross-encoder on
the labels is effectively *not* done on the JVM — train in Python and export anyway.
Keeping pace with HF model churn lags. Novel tokenizers/architectures may have no ONNX
path.

---

## Synthesis

| | Python-only | Scala-only |
|---|---|---|
| Reranker in-process | one line (native) | DJL/ONNX, ~150 lines + export step |
| Reranker over HTTP | trivial | trivial (and erases the gap) |
| Judge + embeddings | HTTP (same) | HTTP (same) |
| Fits Zingg core / Spark / one jar | process boundary | native |
| ML iteration (fine-tune, swap models) | native, fast | export-and-pray |
| Testing | pytest + mocks | zio-test + sttp stubs (repo already here) |
| Deploy artifact | Python + torch env | fat jar (+ model-server sidecar) |

The decision reduces to two clean configurations, depending on the (still-deferred)
end-state:

- **Offline / still-experimental tool → Python-only.** Still tuning models, the reranker
  is one import, no Zingg-integration tax. This is where it is now; no reason to move yet.
- **Productized Zingg feature → Scala-only with the reranker served over HTTP (option 1).**
  Then *every* model (judge, embedder, reranker) is HTTP, the Scala app is pure logic +
  typed config, it ships as one jar alongside the LocalAI/TEI sidecar already running,
  reuses `LlmLabeller`, and can scale clustering on Spark. The in-process DJL route
  (option 2) only earns its keep if a single self-contained process with no model sidecar
  is a hard requirement.

The HTTP-reranker insight is what lets Scala-only match Python-only without dragging torch
onto the JVM. Avoid the long-term both-languages state; either configuration above is
clean.
