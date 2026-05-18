"""Port of ``CrossAssigneeBlockingTest.scala`` — multi-key canopy blocking."""

from __future__ import annotations

import pandas as pd

from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

# `assignee` is listed FIRST on purpose and marked non-blockable (scoring only).
cfg = ZinggConf(
    fields=[
        FieldDef("assignee", MatchType.Exact, blockable=False),
        FieldDef("summary", MatchType.Text),
        FieldDef("priority", MatchType.Exact, blockable=False),
    ],
    block_size=50,
)


def _df(rows):
    return pd.DataFrame(rows, columns=[cfg.id_col, "assignee", "summary", "priority"])


def _candidate_pairs(data):
    cands = Zingg(cfg).find_training_data(data, n=200)
    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"
    return {tuple(sorted(p)) for p in zip(cands[l_id], cands[r_id])}


def test_same_cve_different_assignee_is_proposed():
    data = _df([
        (1, "joe123", "Java 11.0.51 vulnerable to CVE-2021-12345", "P1"),
        (2, "ben456", "Java 11.0.50 vulnerable to CVE-2021-12345", "P2"),
        (3, "joe123", "Tomcat 4.3.2 vulnerable to CVE-2019-98765", "P1"),
    ])
    pairs = _candidate_pairs(data)
    assert (1, 2) in pairs, f"same-CVE / different-assignee match (1,2) must be a candidate; got {pairs}"


def test_candidates_are_not_dominated_by_shared_assignee():
    data = _df([
        (1, "joe123", "Java 11.0.51 vulnerable to CVE-2021-12345", "P1"),
        (2, "ben456", "Java 11.0.50 vulnerable to CVE-2021-12345", "P2"),
        (3, "joe123", "Tomcat 4.3.2 vulnerable to CVE-2019-98765", "P1"),
        (4, "joe123", "nginx 1.2.3 vulnerable to CVE-2020-55555", "P3"),
        (5, "joe123", "redis 6.0 vulnerable to CVE-2018-11111", "P1"),
    ])
    pairs = _candidate_pairs(data)
    assert (1, 2) in pairs, f"the only true match (1,2) must be proposed; got {pairs}"
    spurious = {(1, 3), (1, 4), (1, 5), (3, 4), (3, 5), (4, 5)}
    assert not (pairs & spurious), f"pairs sharing only the non-blockable assignee must not be proposed; offending={pairs & spurious}"
