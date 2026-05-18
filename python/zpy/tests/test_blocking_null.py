"""Port of ``BlockingNullTest.scala`` — null/empty values must not pair."""

from __future__ import annotations

import pandas as pd

from zingg_py import blocking, pairs
from zingg_py.blocking import Leaf, Node
from zingg_py.hashing import FirstChars
from zingg_py.schema import FieldDef, MatchType, ZinggConf

cfg = ZinggConf(fields=[FieldDef("name", MatchType.Fuzzy)])
tree = Node("name", FirstChars(2), {}, Leaf("seed"))


def _df(rows):
    return pd.DataFrame(rows, columns=[cfg.id_col, "name"])


def test_null_valued_records_get_null_block_key():
    data = _df([(0, None), (1, "Alice Smith"), (2, "Alice Adams")])
    blocked = blocking.assign_blocks(data, tree)
    by_id = dict(zip(blocked[cfg.id_col], blocked["z_block"]))
    assert pd.isna(by_id[0]), f"null value must yield a null block key, got {by_id[0]}"
    assert not pd.isna(by_id[1]) and by_id[1] == by_id[2]


def test_null_valued_records_do_not_pair():
    data = _df([(0, None), (1, None), (2, "Alice Smith"), (3, "Alice Adams"), (4, "Bob Jones")])
    blocked = blocking.assign_blocks(data, tree)
    p = pairs.self_pairs(blocked, cfg)
    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"
    id_pairs = set(zip(p[l_id], p[r_id]))
    assert id_pairs == {(2, 3)}
