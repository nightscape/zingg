"""Tests for the `auto` workflow: the separability gate and the unambiguous
end-to-end path (which must finish without ever invoking a labeller)."""

from __future__ import annotations

import json

import numpy as np
import pandas as pd

from zingg_py import auto, config_loader


def test_separability_clean_bimodal():
    sep = auto.assess_separability(np.array([0.10, 0.12, 0.15, 0.70, 0.72, 0.75]))
    assert sep.separable and 0.15 < sep.threshold < 0.70
    assert sep.n_low == 3 and sep.n_high == 3


def test_separability_all_low_means_no_matches():
    sep = auto.assess_separability(np.array([0.02, 0.08, 0.11]))
    assert sep.separable and "no matches" in sep.reason


def test_separability_all_high_means_all_match():
    sep = auto.assess_separability(np.array([0.88, 0.92, 0.95]))
    assert sep.separable and "all match" in sep.reason


def test_separability_flat_is_ambiguous():
    sep = auto.assess_separability(np.array([0.30, 0.35, 0.40, 0.45, 0.50, 0.55]))
    assert not sep.separable


def test_auto_unambiguous_finishes_without_labelling(tmp_path):
    # All same priority → the priority canopy blocks every row together, so the
    # candidate set has clear matches (shared CVE) and clear non-matches.
    data = (
        "z_id,summary,description,priority\n"
        "0,rce in nginx,scanner ref CVE-2021-00001,P1\n"
        "1,remote code execution nginx,nginx ref CVE-2021-00001,P1\n"
        "2,sql injection redis,audit ref CVE-2020-22222,P1\n"
        "3,sqli in redis db,redis ref CVE-2020-22222,P1\n"
        "4,path traversal kafka,scan ref CVE-2019-33333,P1\n"
        "5,directory traversal kafka,kafka ref CVE-2019-33333,P1\n"
    )
    data_csv = tmp_path / "data.csv"
    data_csv.write_text(data)
    out_csv = tmp_path / "out.csv"
    conf = tmp_path / "config.json"
    conf.write_text(json.dumps({
        "link": False,
        "fieldDefinition": [
            {"fieldName": "summary", "matchType": "fuzzy"},
            {"fieldName": "description", "matchType": "cve"},
            {"fieldName": "priority", "matchType": "exact"},
        ],
        "data": [{"name": "t", "format": "csv", "props": {"path": str(data_csv), "header": True}}],
        "output": [{"name": "o", "format": "csv", "props": {"path": str(out_csv), "header": True}}],
    }))
    loaded = config_loader.load(conf)

    def _no_labeller():
        raise AssertionError("labeller must not be invoked on the unambiguous path")

    sep = auto.run(loaded, tmp_path / "model", _no_labeller, log=lambda *_: None)
    assert sep.separable

    out = pd.read_csv(out_csv, dtype=str)
    clusters = dict(zip(out["z_id"], out["z_cluster"]))
    assert clusters["0"] == clusters["1"]
    assert clusters["2"] == clusters["3"]
    assert clusters["4"] == clusters["5"]
    assert len({clusters[i] for i in ("0", "2", "4")}) == 3  # three distinct clusters
