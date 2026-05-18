"""Port of ``LinkageTest.scala`` — N-way heterogeneous record linkage."""

from __future__ import annotations

import itertools

import pandas as pd
import pytest

from zingg_py import blocking, features, model as model_mod, pairs as pair_mod
from zingg_py.blocking import Leaf, Node
from zingg_py.canonicalizer import canonicalize
from zingg_py.config_loader import IO
from zingg_py.hashing import IdentityString, RegexExtract
from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

cfg = ZinggConf(
    fields=[FieldDef("name", MatchType.Fuzzy), FieldDef("email", MatchType.Email)],
    block_size=50,
)

crm_io = IO("csv", ".", True, name="crm",
            mapping={"name": "full_name", "email": "mail"}, normalizers={"email": "lower"})
billing_io = IO("csv", ".", True, name="billing", mapping={"email": "email_addr"})
support_io = IO("csv", ".", True, name="support",
                mapping={"name": "customer", "email": "contact_email"}, normalizers={"email": "lower"})


def crm():
    return pd.DataFrame([
        ("Alice Smith", "ALICE@EXAMPLE.COM"),
        ("Bob Jones", "BOB@EXAMPLE.COM"),
        ("Carol White", "CAROL@EXAMPLE.COM"),
    ], columns=["full_name", "mail"])


def billing():
    return pd.DataFrame([
        ("Alice Smith", "alice@example.com"),
        ("Bob Jones", "bob@example.com"),
        ("Carol White", "carol@example.com"),
    ], columns=["name", "email_addr"])


def support():
    return pd.DataFrame([
        ("Alice Smith", "Alice@Example.com"),
        ("Bob Jones", "Bob@Example.com"),
        ("Carol White", "Carol@Example.com"),
    ], columns=["customer", "contact_email"])


def canonical_union():
    return pd.concat([
        canonicalize(crm(), crm_io, cfg),
        canonicalize(billing(), billing_io, cfg),
        canonicalize(support(), support_io, cfg),
    ], ignore_index=True)


def test_canonicalize_maps_heterogeneous_columns_and_normalizes():
    c = canonicalize(crm(), crm_io, cfg)
    assert set(c.columns) == {"name", "email", ZinggConf.SOURCE_COL}
    assert set(c["email"]) == {"alice@example.com", "bob@example.com", "carol@example.com"}
    assert set(c[ZinggConf.SOURCE_COL]) == {"crm"}


def test_missing_mapped_column_fails_loudly():
    bad = IO("csv", ".", True, name="bad", mapping={"email": "nonexistent"})
    with pytest.raises(AssertionError, match="nonexistent"):
        canonicalize(billing(), bad, cfg)


def test_cross_source_pairing_excludes_same_source_pairs():
    with_id = canonical_union()
    with_id[cfg.id_col] = range(len(with_id))
    blocked = blocking.assign_blocks(with_id, Leaf("root"))
    pairs = pair_mod.self_pairs(blocked, cfg, cross_source_only=True)
    l_src = f"{ZinggConf.LEFT_PREFIX}{ZinggConf.SOURCE_COL}"
    r_src = f"{ZinggConf.RIGHT_PREFIX}{ZinggConf.SOURCE_COL}"
    same_source = (pairs[l_src] == pairs[r_src]).sum()
    assert same_source == 0
    assert len(pairs) > 0


def _labeled_from(union):
    rows = list(zip(union["name"], union["email"], union[ZinggConf.SOURCE_COL]))
    recs = []
    for (ln, le, _), (rn, re, _) in itertools.combinations(rows, 2):
        recs.append({
            f"{ZinggConf.LEFT_PREFIX}name": ln, f"{ZinggConf.LEFT_PREFIX}email": le,
            f"{ZinggConf.RIGHT_PREFIX}name": rn, f"{ZinggConf.RIGHT_PREFIX}email": re,
            cfg.label_col: 1.0 if le == re else 0.0,
        })
    return features.add_features(pd.DataFrame(recs), cfg)


def _assert_linked_correctly(clusters):
    by_email = {}
    for email, cl in zip(clusters["email"], clusters[cfg.cluster_col]):
        by_email.setdefault(email, set()).add(cl)
    for email, cs in by_email.items():
        assert len(cs) == 1, f"entity {email} split across clusters {cs}"
    all_clusters = set().union(*by_email.values())
    assert len(all_clusters) == 3, f"expected 3 clusters, got {len(all_clusters)}: {by_email}"


def test_links_same_entity_across_three_sources():
    union = canonical_union()
    model = model_mod.train(_labeled_from(union), cfg)
    z = Zingg(cfg, link=True)
    _assert_linked_correctly(z.cluster(union, model, Leaf("root")))


def test_blocking_tree_json_round_trips():
    tree = Node(
        "description", RegexExtract(r"(?i)CVE-\d{4}-\d{4,7}"),
        {
            "CVE-2021-0001": Leaf("a"),
            "CVE-2020-9999": Node("priority", IdentityString(), {}, Leaf("b")),
        },
        Leaf("fallback"),
    )
    restored = blocking.from_json(blocking.to_json(tree))
    assert restored == tree


def test_persisted_tree_drives_link_clustering():
    union = canonical_union()
    z = Zingg(cfg, link=True)
    model, tree = z.train(_labeled_from(union))
    restored = blocking.from_json(blocking.to_json(tree))
    _assert_linked_correctly(z.cluster(union, model, restored))
