"""Config-driven input/output — port of the ``read`` / ``readOne`` /
``writeOutputs`` helpers in ``Phases.scala``.

Spark's reader/writer becomes pandas. In link mode each source is canonicalised
onto the logical schema (and tagged with its origin) before union; in dedup mode
the raw rows are unioned directly.
"""

from __future__ import annotations

import pandas as pd

from .canonicalizer import canonicalize
from .config_loader import IO, Loaded

# Map Zingg/Spark reader option names to pandas read_csv kwargs.
_CSV_OPT = {"delimiter": "sep", "sep": "sep", "quote": "quotechar",
            "escape": "escapechar", "encoding": "encoding"}


def read_one(io: IO) -> pd.DataFrame:
    if io.format == "parquet":
        return pd.read_parquet(io.path)
    kwargs = {"header": 0 if io.header else None}
    for k, v in io.options.items():
        if k in _CSV_OPT:
            kwargs[_CSV_OPT[k]] = v
    return pd.read_csv(io.path, dtype=str, keep_default_na=True, **kwargs)


def read_inputs(loaded: Loaded) -> pd.DataFrame:
    inputs = loaded.inputs
    assert inputs, "config has no `data` entries to read"
    if loaded.dedup_source is not None:
        io = next((i for i in inputs if i.name == loaded.dedup_source), None)
        assert io is not None, (
            f"dedup_source '{loaded.dedup_source}' not among sources: "
            f"{', '.join(i.name for i in inputs)}"
        )
        # Canonicalise the one source so its fieldMapping is applied; pairing
        # then happens within it (the id is assigned downstream).
        return canonicalize(read_one(io), io, loaded.cfg)
    if loaded.link:
        dfs = [canonicalize(read_one(io), io, loaded.cfg) for io in inputs]
    else:
        dfs = [read_one(io) for io in inputs]
    return pd.concat(dfs, ignore_index=True)


def write_outputs(df: pd.DataFrame, outputs) -> None:
    for io in outputs:
        if io.format == "parquet":
            df.to_parquet(io.path, index=False)
        else:
            sep = io.options.get("delimiter") or io.options.get("sep") or ","
            df.to_csv(io.path, sep=sep, index=False, header=io.header)
