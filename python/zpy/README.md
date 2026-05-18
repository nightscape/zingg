# zingg-py

A Python port of the Zingg record-linkage **core** from `zscala/` (the Scala/Spark
rewrite). It keeps the algorithms identical and swaps the runtime:

| Scala / Spark                          | Python                                           |
|----------------------------------------|--------------------------------------------------|
| Spark `DataFrame` (joins, group-by)    | `pandas`                                         |
| commons-text Jaro-Winkler/Levenshtein  | `rapidfuzz`                                       |
| Breeze linear algebra (`BayesianLogReg`)| `numpy` / `scipy.linalg`                          |
| Spark MLlib `PolynomialExpansion`+`LogisticRegression` | `scikit-learn` `PolynomialFeatures`+`LogisticRegression` |
| GraphX connected components            | `scipy.sparse.csgraph.connected_components`      |
| Jackson JSON config                    | stdlib `json`                                    |
| ScalaCheck / JUnit                     | `hypothesis` / `pytest`                          |

Single-node only — Zingg's defaults (`blockSize=500`) keep candidate sets small,
so an in-process dataframe is the natural fit. The distributed pieces (self-join,
featurize UDF, connected components) port to ordinary pandas/scipy because none of
them are inherently distributed.

## Run the tests

```bash
cd zpy
uv sync
uv run pytest
```

## Command line

Two commands cover the usual flow; both are driven by a JSON config (same shape
as the Scala side: `fieldDefinition`, `data`, `output`, `link`).

```bash
# 1) label candidate pairs + train a model (adaptive, one pair at a time)
uv run zingg-py learn   --conf examples/dedup/config.json --model-dir models/1

#    LLM judge instead of a human at the keyboard (OpenAI-compatible endpoint):
ZINGG_AI_ENDPOINT=http://localhost:11434/v1/chat/completions \
  uv run zingg-py learn --conf examples/dedup/config.json --labeller llm

#    fixed-batch labelling instead of adaptive online:
uv run zingg-py learn   --conf examples/dedup/config.json --batch

# 2) cluster / link the data with the trained model, writing the config's outputs
uv run zingg-py cluster --conf examples/dedup/config.json --model-dir models/1

# one-shot: finish if the data is unambiguous, else fall back to labelling
uv run zingg-py auto    --conf examples/auto/config.json
```

### `auto` — finish if unambiguous, else label

`auto` scores candidate pairs by the unsupervised similarity proxy (mean feature
value) and checks whether that distribution splits cleanly into a low
(non-match) and high (match) mode with a wide valley between them:

- **clean valley** → threshold at the valley, cluster, write outputs, done — no
  labels or training needed.
- **no clean valley** → fall back to labelling (`--labeller auto` picks the LLM
  if its endpoint is reachable, else the human CLI), then train + cluster.

```bash
uv run zingg-py auto --conf examples/auto/config.json \
  --labeller auto      # auto (default) | human | llm
  --min-gap 0.15       # min valley width to call it unambiguous
  --max-labels 60      # cap on labels in the ambiguous fallback
```

`auto` is for dedup / linkage — Zingg's strength. It deliberately does **not**
attempt the CVE-cluster↔ops-ticket coverage mapping: that needs a bipartite
retrieve→rerank→judge pipeline, not a connected-components partition (see
`examples/security/README.md` §2). That pipeline stays in the Python prototype
under `examples/security/`.

`learn` = (online `findAndLabel`, or `findTrainingData`+`label` with `--batch`) +
`train`. `cluster` = `match` (or `link` when the config sets `"link": true`). For
parity with the Scala CLI, individual phases are also exposed:

```bash
uv run zingg-py phase --phase findTrainingData|label|findAndLabel|train|match|link|trainMatch --conf config.json
```

The model dir holds `model.pkl`, `blockingTree.json`, and the `unmarked.pkl` /
`marked.pkl` candidate frames. A worked dedup example is in `examples/dedup/`.

### Labelling backends
- `--labeller human` (default): renders each pair as a side-by-side table and
  reads `y`/`n`/`s`/`q` from stdin.
- `--labeller llm`: an OpenAI-compatible chat endpoint answers `1`/`0`/`-1` per
  pair. Configure via `ZINGG_AI_ENDPOINT` (or `OPENAI_BASE`), `ZINGG_AI_MODEL`,
  `ZINGG_AI_KEY`.
- `--labeller oracle --truth ground_truth.csv`: labels from a ground-truth file
  for held-out evaluation — `id,cluster` (partition) or two columns of matching
  id pairs (`OraclePairLabeller`, bipartite / N:M).

## Experiments

`../examples/security/zingg_coverage_eval.py` is a two-stage, two-oracle held-out
evaluation of the pipeline on the security CSVs (run it from this env:
`uv run python ../examples/security/zingg_coverage_eval.py`):

- **Stage 1 — intra-CVE clustering** (partition oracle): held-out **F1 1.00** —
  dedup is Zingg's strength.
- **Stage 2 — CVE↔ops linkage** (bipartite oracle): blocking surfaces **0/24**
  true links, held-out **F1 ≈ 0.47** — that task needs the rerank→judge pipeline
  (cluster-F1 0.92), not Zingg clustering.

Results note: `../examples/security/zingg_coverage_eval.md`.
