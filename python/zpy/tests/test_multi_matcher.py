"""Port of ``MultiMatcherTest.scala`` — several matchers on one column."""

from __future__ import annotations

import pandas as pd

from zingg_py import features, similarity
from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

cfg = ZinggConf(fields=[FieldDef("summary", [MatchType.Fuzzy, MatchType.cve])], block_size=50)


def _df(rows):
    return pd.DataFrame(rows, columns=[cfg.id_col, "summary"])


def test_feature_width_sums_all_matchers():
    width = sum(similarity.feature_width(mt) for f in cfg.fields for mt in f.match_types)
    assert width == 4


def _id_pairs(cands):
    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"
    return {tuple(sorted(p)) for p in zip(cands[l_id], cands[r_id])}


def test_each_matcher_contributes_its_own_canopy():
    data = _df([
        (1, "Alpha widget vulnerable to CVE-2021-12345"),
        (2, "Zeta gadget vulnerable to CVE-2021-12345"),   # shares CVE, differs on first chars
        (3, "Alpha thing vulnerable to CVE-2019-99999"),   # shares first chars, differs on CVE
    ])
    cands = Zingg(cfg).find_training_data(data, n=100)
    p = _id_pairs(cands)
    assert (1, 2) in p, f"regex (CVE) canopy must pair shared-CVE records; got {p}"
    assert (1, 3) in p, f"fuzzy (firstChars) canopy must pair shared-prefix records; got {p}"
    assert (2, 3) not in p, f"records sharing neither must not pair; got {p}"

    size = cands[features.FEATURE_COL].iloc[0].size
    assert size == 4, f"feature vector should have 4 components; got {size}"
