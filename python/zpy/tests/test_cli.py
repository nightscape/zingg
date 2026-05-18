"""Tests for the runnable layer: LLM verdict parsing, pair rendering, and an
end-to-end config → learn → cluster round-trip through the phase dispatcher
(driven by the oracle labeller so it needs no stdin / network)."""

from __future__ import annotations

import json
from dataclasses import replace

import pandas as pd

from zingg_py import config_loader, io, persistence, phases
from zingg_py.labeller import Decision, OracleLabeller, OraclePairLabeller
from zingg_py.llm_labeller import first_signed_digit, parse_label
from zingg_py.pair_table import render
from zingg_py.schema import FieldDef, MatchType, ZinggConf


def test_cluster_block_strategy_canopies_and_validation():
    import pytest
    from zingg_py.zingg import Zingg
    cfg = ZinggConf(fields=[
        FieldDef("summary", MatchType.Fuzzy),
        FieldDef("description", MatchType.cve),
    ], block_size=50)
    df = pd.DataFrame([
        {"z_id": "0", "summary": "rce nginx", "description": "ref CVE-2021-00001"},
        {"z_id": "1", "summary": "remote code exec nginx", "description": "nginx ref CVE-2021-00001"},
        {"z_id": "2", "summary": "sqli redis", "description": "ref CVE-2020-22222"},
        {"z_id": "3", "summary": "sql injection redis", "description": "redis ref CVE-2020-22222"},
    ])
    truth = {"0": 0, "1": 0, "2": 1, "3": 1}
    z = Zingg(cfg)
    cands = z.find_training_data(df, n=10_000)
    labelled = OraclePairLabeller({("0", "1"), ("2", "3")}).label(cands, cfg)
    model, tree = z.train(labelled)

    clusters = dict(zip(*[z.cluster(df, model, tree, block_strategy="canopies")[c]
                          for c in (cfg.id_col, cfg.cluster_col)]))
    assert clusters["0"] == clusters["1"] and clusters["2"] == clusters["3"]
    assert clusters["0"] != clusters["2"]
    with pytest.raises(ValueError):
        z.cluster(df, model, tree, block_strategy="bogus")


def test_oracle_pair_labeller_is_bipartite():
    cfg = ZinggConf(fields=[FieldDef("t", MatchType.Fuzzy)])
    # b matches both a and c (N:M) — a partition oracle could not express this.
    oracle = OraclePairLabeller({("a", "b"), ("c", "b")})
    pairs = pd.DataFrame([
        {"l_z_id": "a", "r_z_id": "b"},
        {"l_z_id": "b", "r_z_id": "c"},  # order-independent
        {"l_z_id": "a", "r_z_id": "c"},
    ])
    decisions = [oracle.decide(r, cfg) for _, r in pairs.iterrows()]
    assert decisions == [Decision.Match, Decision.Match, Decision.NonMatch]
    labelled = oracle.label(pairs, cfg)
    assert list(labelled[cfg.label_col]) == [1.0, 1.0, 0.0]


def test_first_signed_digit_picks_last_standalone():
    assert first_signed_digit("thinking... CVE-2021-1234 ... Answer:\n1") == 1
    assert first_signed_digit("0") == 0
    assert first_signed_digit("the answer is -1") == -1
    assert first_signed_digit("year 2024, id 1234") is None  # digit-runs skipped


def test_parse_label_reads_content_and_reasoning():
    assert parse_label(json.dumps({"choices": [{"message": {"content": "1"}}]})) == 1
    assert parse_label(json.dumps({"choices": [{"message": {"reasoning_content": "...\n0"}}]})) == 0
    assert parse_label(json.dumps({"choices": []})) is None


def test_pair_table_renders_both_sides():
    cfg = ZinggConf(fields=[FieldDef("name", MatchType.Fuzzy)])
    row = pd.Series({"l_name": "Alice", "r_name": "Alicia"})
    lines = render(cfg, row, width=60)
    assert any("Alice" in ln and "Alicia" in ln for ln in lines)


def _write(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text)
    return p


def test_end_to_end_learn_then_cluster(tmp_path):
    # Same priority for all rows so the priority canopy also pairs the two CVE
    # groups against each other — those cross pairs become the negative labels
    # the classifier needs to learn the CVE signal (without them it sees one
    # class and merges everything).
    data = (
        "z_id,summary,description,priority\n"
        "0,rce nginx,ref CVE-2021-00001,P1\n"
        "1,remote code exec nginx,affects nginx ref CVE-2021-00001,P1\n"
        "2,sqli redis,ref CVE-2020-22222,P1\n"
        "3,sql injection redis,redis ref CVE-2020-22222,P1\n"
    )
    data_csv = _write(tmp_path, "data.csv", data)
    out_csv = tmp_path / "out.csv"
    conf = _write(tmp_path, "config.json", json.dumps({
        "link": False,
        "fieldDefinition": [
            {"fieldName": "summary", "matchType": "fuzzy"},
            {"fieldName": "description", "matchType": "cve"},
            {"fieldName": "priority", "matchType": "exact"},
        ],
        "data": [{"name": "t", "format": "csv",
                  "props": {"path": str(data_csv), "header": True}}],
        "output": [{"name": "o", "format": "csv",
                    "props": {"path": str(out_csv), "header": True}}],
    }))
    loaded = config_loader.load(conf)
    model_dir = tmp_path / "model"

    # Oracle: ids share an entity iff same CVE (0,1) and (2,3).
    truth = {"0": 0, "1": 0, "2": 1, "3": 1}
    phases.find_training_data(loaded, model_dir)
    phases.label(loaded, model_dir, OracleLabeller(truth))
    phases.train(loaded, model_dir)
    phases.match_phase(loaded, model_dir)

    out = pd.read_csv(out_csv, dtype=str)
    clusters = dict(zip(out["z_id"], out["z_cluster"]))
    assert clusters["0"] == clusters["1"]
    assert clusters["2"] == clusters["3"]
    assert clusters["0"] != clusters["2"]
    assert (model_dir / persistence.MODEL_FILE).exists()
    assert (model_dir / persistence.TREE_FILE).exists()


def test_oracle_labellers_match_int_ids_against_str_truth():
    """A truth file is read as strings; runtime z_ids are ints. Both must align."""
    cfg = ZinggConf(fields=[FieldDef("x", MatchType.Exact)])
    li, ri = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}", f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"
    same = pd.Series({li: 0, ri: 1})
    diff = pd.Series({li: 0, ri: 2})
    partition = OracleLabeller({"0": "c", "1": "c", "2": "d"})  # str keys
    assert partition.decide(same, cfg) == Decision.Match
    assert partition.decide(diff, cfg) == Decision.NonMatch
    pairs = OraclePairLabeller({("0", "1")})  # str positives
    assert pairs.decide(same, cfg) == Decision.Match
    assert pairs.decide(diff, cfg) == Decision.NonMatch


def test_dedup_source_canonicalises_one_mapped_source(tmp_path):
    """`dedup_source` projects a single mapping-dependent source onto the logical
    schema (the raw-union dedup path can't), so its fields exist downstream."""
    src = _write(tmp_path, "stat.csv", "Issue,Vuln Title\nA-1,log4j rce\nA-2,log4j rce\n")
    conf = _write(tmp_path, "config.json", json.dumps({
        "link": True,
        "fieldDefinition": [{"fieldName": "title", "matchType": "fuzzy"}],
        "data": [
            {"name": "stat", "format": "csv", "props": {"path": str(src), "header": True},
             "fieldMapping": {"title": "Vuln Title"}},
            {"name": "other", "format": "csv", "props": {"path": str(src), "header": True},
             "fieldMapping": {"title": "Vuln Title"}},
        ],
        "output": [{"name": "o", "format": "csv", "props": {"path": str(tmp_path / "o.csv"), "header": True}}],
    }))
    loaded = replace(config_loader.load(conf), link=False, dedup_source="stat")
    df = io.read_inputs(loaded)
    # canonicalised: logical field present, only the one source, tagged z_source.
    assert "title" in df.columns
    assert list(df["title"]) == ["log4j rce", "log4j rce"]
    assert set(df[ZinggConf.SOURCE_COL]) == {"stat"}
