"""Port of ``ConfigLoaderTest.scala``."""

from __future__ import annotations

import tempfile
from pathlib import Path

import pytest

from zingg_py import config_loader
from zingg_py.schema import MatchType


def _load(json_str: str):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        fh.write(json_str)
        path = fh.name
    return config_loader.load(path)


def _fields_of(match_type_json: str):
    return _load(f"""{{
      "fieldDefinition": [{{ "fieldName": "f", "matchType": {match_type_json} }}],
      "data": [{{ "name": "s", "format": "csv",
                 "props": {{ "path": "x.csv", "header": true }} }}],
      "output": [{{ "name": "o", "format": "csv",
                  "props": {{ "path": "/tmp/out", "header": true }} }}]
    }}""").cfg.fields


def test_csv_props_become_reader_options():
    loaded = _load("""{
      "link": true,
      "fieldDefinition": [{ "fieldName": "assignee", "matchType": "fuzzy" }],
      "data": [{
        "name": "src", "format": "csv",
        "props": { "path": "examples/x.csv", "delimiter": ",", "header": true, "multiLine": "true" },
        "fieldMapping": { "assignee": "Assignee" }
      }],
      "output": [{ "name": "o", "format": "csv", "props": { "path": "/tmp/out", "header": true } }]
    }""")
    io = loaded.inputs[0]
    assert io.header
    assert io.options.get("multiLine") == "true"
    assert io.options.get("delimiter") == ","
    assert "path" not in io.options
    assert "header" not in io.options


def test_match_type_as_object_regex():
    f = _fields_of(r'{ "type": "regex", "pattern": "ISSUE-\\d+" }')[0]
    assert f.match_types == (MatchType.Regex("ISSUE-\\d+"),)


def test_match_type_as_array_of_string_and_object():
    f = _fields_of(r'[ "fuzzy", { "type": "regex", "pattern": "(?i)CVE-\\d{4}-\\d+" } ]')[0]
    assert f.match_types == (MatchType.Fuzzy, MatchType.Regex("(?i)CVE-\\d{4}-\\d+"))


def test_cve_string_is_regex_alias():
    f = _fields_of('"cve"')[0]
    assert f.match_types == (MatchType.cve,) and f.match_types[0].is_regex


def test_dont_use_in_array_is_dropped():
    f = _fields_of('[ "dont_use", "exact" ]')[0]
    assert f.match_types == (MatchType.Exact,)


def test_field_with_only_dont_use_is_excluded():
    fields = _load("""{
      "fieldDefinition": [
        { "fieldName": "ignored", "matchType": "dont_use" },
        { "fieldName": "kept",    "matchType": "exact" }
      ],
      "data": [{ "name": "s", "format": "csv", "props": { "path": "x.csv", "header": true } }],
      "output": [{ "name": "o", "format": "csv", "props": { "path": "/tmp/out", "header": true } }]
    }""").cfg.fields
    assert [f.name for f in fields] == ["kept"]
