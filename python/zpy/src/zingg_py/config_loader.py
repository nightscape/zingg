"""Config reader + IO descriptor — port of ``ConfigLoader.scala``.

``IO`` is also constructed directly by the linkage tests, so it lives here as a
public dataclass (the Scala ``ConfigLoader.IO`` case class).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .schema import FieldDef, MatchType, ZinggConf


@dataclass(frozen=True)
class IO:
    format: str
    path: str
    header: bool
    name: str = ""
    mapping: dict = field(default_factory=dict)
    normalizers: dict = field(default_factory=dict)
    options: dict = field(default_factory=dict)


@dataclass(frozen=True)
class Loaded:
    cfg: ZinggConf
    inputs: list
    outputs: list
    link: bool
    # When set, dedup a single named source: canonicalise just that input onto
    # the logical schema (so fieldMapping applies) and pair within it.
    dedup_source: Optional[str] = None


def load(path) -> Loaded:
    root = json.loads(Path(path).read_text())
    fields = [fd for n in _nodes(root, "fieldDefinition") if (fd := _parse_field(n))]
    inputs = [_parse_input(n, i) for i, n in enumerate(_nodes(root, "data"))]
    outputs = [_parse_output(n) for n in _nodes(root, "output")]
    link = bool(root.get("link", False))
    assert fields, f"config {path} has no usable fieldDefinition entries"
    return Loaded(ZinggConf(fields=fields), inputs, outputs, link)


def _nodes(root: dict, name: str) -> list:
    n = root.get(name)
    return list(n) if isinstance(n, list) else []


def _parse_field(n: dict) -> Optional[FieldDef]:
    name = n.get("fieldName", "")
    blockable = bool(n.get("blocking", True))
    match_types = _parse_match_types(n.get("matchType"))
    if not name or not match_types:
        return None
    return FieldDef(name, match_types, blockable)


def _parse_match_types(n) -> list[MatchType]:
    elems = n if isinstance(n, list) else [n]
    out = []
    for e in elems:
        mt = _parse_matcher(e)
        if mt is not None:
            out.append(mt)
    return out


def _parse_matcher(n) -> Optional[MatchType]:
    if isinstance(n, dict):
        t = str(n.get("type", "")).lower()
        if t == "regex":
            p = n.get("pattern", "")
            assert p, 'a "regex" matchType requires a non-empty "pattern"'
            return MatchType.Regex(p)
        return _match_type_of(t)
    if isinstance(n, str):
        return _match_type_of(n.lower())
    return None


def _match_type_of(s: str) -> Optional[MatchType]:
    return {
        "": None, "dont_use": None, "do_not_use": None,
        "exact": MatchType.Exact,
        "fuzzy": MatchType.Fuzzy, "text": MatchType.Fuzzy,
        "numeric": MatchType.Numeric, "number": MatchType.Numeric, "int": MatchType.Numeric,
        "email": MatchType.Email,
        "text_long": MatchType.Text, "long_text": MatchType.Text,
        "cve": MatchType.cve, "cve_id": MatchType.cve, "cveid": MatchType.cve,
    }.get(s, MatchType.Custom)


def _parse_input(n: dict, idx: int) -> IO:
    base = _parse_output(n)
    explicit = n.get("name", "")
    name = explicit if explicit else f"source_{idx}"
    return IO(
        format=base.format, path=base.path, header=base.header, name=name,
        mapping=_str_map(n.get("fieldMapping")),
        normalizers=_str_map(n.get("fieldNormalizers")),
        options=base.options,
    )


def _parse_output(n: dict) -> IO:
    fmt = n.get("format", "csv")
    props = n.get("props", {})
    path = props.get("path", "")
    header = bool(props.get("header", False))
    assert path, "data/output entry missing props.path"
    return IO(fmt, path, header, options=_reader_options(props))


def _reader_options(props) -> dict:
    if not isinstance(props, dict):
        return {}
    return {k: _as_text(v) for k, v in props.items() if k not in ("path", "header")}


def _str_map(n) -> dict:
    if not isinstance(n, dict):
        return {}
    return {k: _as_text(v) for k, v in n.items()}


def _as_text(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)
