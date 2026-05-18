# Python workspace

Self-contained `uv` workspace with two packages. Copy this whole directory into
another repo and it works as-is.

```
python/
├── pyproject.toml        # uv workspace root (members, torch CPU index, sources)
├── uv.lock               # single lockfile for the whole workspace
├── cve_coverage/         # CVE-cluster -> ops-ticket coverage pipeline (CLI)
│   ├── pyproject.toml     # project "cve-coverage"  (console script: cve-coverage)
│   ├── cve_coverage/      # the package
│   ├── config.json        # default pipeline config (schema + DAG)
│   ├── StatAnon.csv        # example data: vulnerability tickets
│   └── VmtAnon.csv         # example data: ops tickets
└── zpy/                  # zingg-py: Python port of the Zingg record-linkage core
    ├── pyproject.toml     # project "zingg-py"  (console script: zingg-py)
    ├── src/zingg_py/
    └── tests/
```

`cve_coverage` depends on `zingg-py` only for the `--cluster zingg` strategy; the
workspace root resolves it to the local `zpy/` member (no PyPI).

## Run

```bash
cd python

# install both members into one environment
uv sync                      # add --extra zingg for the zingg-py clustering strategy

# coverage pipeline (config-driven; rebind input files with name=path)
uv run cve-coverage run --dry-run
uv run cve-coverage run statistix=cve_coverage/StatAnon.csv jira=cve_coverage/VmtAnon.csv

# zingg-py core + its tests
uv run --package zingg-py pytest zpy/tests
```

See `cve_coverage/cve_coverage/cli.py` and the coverage docs for the full
config/DAG/CLI reference; `zpy/README.md` for the linkage core.
