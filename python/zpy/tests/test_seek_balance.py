"""Property-based tests for the Beta-Bernoulli balance controller.

`next_pair` mixes BALD exploration with seeking a probable match; the seek
probability is the Beta posterior-mean non-match rate
``p_seek = (a0 + u) / (2 a0 + m + u)``. The invariants below pin down the
behaviour the user asked for: every non-match raises it, every match lowers it,
the pull is stronger the fewer/more-imbalanced the labels, and it stays a
self-correcting probability in ``(0, 1)``.
"""

from __future__ import annotations

import math

import pandas as pd
from hypothesis import given, settings
from hypothesis import strategies as st

from zingg_py.interactive_session import InteractiveSession
from zingg_py.schema import FieldDef, MatchType, ZinggConf

cfg = ZinggConf(
    fields=[FieldDef("name", MatchType.Fuzzy), FieldDef("city", MatchType.Exact)],
    block_size=50,
)

_counts = st.integers(min_value=0, max_value=200)
_priors = st.floats(min_value=1e-3, max_value=100.0, allow_nan=False, allow_infinity=False)


def _seek(m: int, u: int, balance_prior: float = 1.0) -> float:
    """p_seek for a session carrying m match and u non-match labels."""
    s = InteractiveSession.__new__(InteractiveSession)
    s._balance_prior = balance_prior
    s._labels = [(0, 1.0)] * m + [(0, 0.0)] * u
    return s._seek_probability()


@settings(max_examples=200, deadline=None)
@given(_counts, _counts, _priors)
def test_seek_probability_is_a_probability(m, u, a0):
    p = _seek(m, u, a0)
    assert 0.0 < p < 1.0, (m, u, a0, p)


@settings(max_examples=50, deadline=None)
@given(_priors)
def test_cold_start_is_half(a0):
    assert _seek(0, 0, a0) == 0.5


@settings(max_examples=200, deadline=None)
@given(_counts, _counts, _priors)
def test_each_nonmatch_raises_seek_probability(m, u, a0):
    assert _seek(m, u + 1, a0) > _seek(m, u, a0)


@settings(max_examples=200, deadline=None)
@given(_counts, _counts, _priors)
def test_each_match_lowers_seek_probability(m, u, a0):
    assert _seek(m + 1, u, a0) < _seek(m, u, a0)


@settings(max_examples=200, deadline=None)
@given(_counts, _priors)
def test_pull_diminishes_as_sample_grows(u, a0):
    """The jump from one more non-match shrinks as the stream lengthens."""
    early = _seek(0, u + 1, a0) - _seek(0, u, a0)
    late = _seek(0, u + 2, a0) - _seek(0, u + 1, a0)
    assert early > late, (u, a0, early, late)


@settings(max_examples=200, deadline=None)
@given(_counts, _counts, _priors)
def test_symmetry_match_nonmatch(m, u, a0):
    """Swapping the roles of matches and non-matches reflects p_seek about 0.5."""
    assert math.isclose(_seek(m, u, a0) + _seek(u, m, a0), 1.0, rel_tol=1e-12)


@settings(max_examples=200, deadline=None)
@given(st.integers(1, 200), _priors)
def test_more_imbalanced_means_higher_seek(n, a0):
    """At equal N, a non-match-heavier stream sits at a higher seek probability."""
    # all non-matches vs a balanced split of the same size
    assert _seek(0, n, a0) > _seek(n // 2, n - n // 2, a0) or n == 1


@settings(max_examples=200, deadline=None)
@given(st.integers(1, 200), st.floats(1e-3, 0.5), st.floats(2.0, 100.0))
def test_smaller_prior_reacts_faster(u, sharp, smooth):
    """A weaker balance prior moves p_seek further from 0.5 for the same labels."""
    assert _seek(0, u, sharp) > _seek(0, u, smooth)


def test_empty_pool_yields_no_pair():
    """A session over an empty candidate pool ends labelling, not crashes."""
    empty = pd.DataFrame({"z_id": [], "name": [], "city": []})
    from zingg_py.zingg import Zingg

    sess = Zingg(cfg).interactive_session(empty)
    assert sess.next_pair() is None


@settings(max_examples=25, deadline=None)
@given(st.integers(1, 2 ** 31 - 1))
def test_seeded_session_is_deterministic(seed):
    from zingg_py.zingg import Zingg

    df = pd.DataFrame(
        {"z_id": range(8),
         "name": [f"bob{i % 3}" for i in range(8)],
         "city": ["LA"] * 8}
    )
    la, ra = f"{ZinggConf.LEFT_PREFIX}z_id", f"{ZinggConf.RIGHT_PREFIX}z_id"
    a = Zingg(cfg).interactive_session(df, seed=seed)
    b = Zingg(cfg).interactive_session(df, seed=seed)
    seq_a = [(p[la], p[ra]) for p in (a.next_pair() for _ in range(5)) if p is not None]
    seq_b = [(p[la], p[ra]) for p in (b.next_pair() for _ in range(5)) if p is not None]
    assert seq_a == seq_b
