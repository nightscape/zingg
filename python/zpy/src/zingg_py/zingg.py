"""End-to-end orchestrator — port of ``Zingg.scala``.

    cfg = ZinggConf(fields=[
        FieldDef("summary", MatchType.Text),
        FieldDef("description", MatchType.cve),
        FieldDef("priority", MatchType.Exact),
    ])
    z = Zingg(cfg)
    candidates = z.find_training_data(df)
    # label `candidates` -> `labeled` with z_label in {0.0, 1.0}
    model, tree = z.train(labeled)
    clusters = z.cluster(df, model, tree)
"""

from __future__ import annotations

from typing import Optional

import pandas as pd

from . import active_learning, blocking, features, model as model_mod, pairs as pair_mod
from .blocking import BlockingTree, Leaf, Node
from .clustering import connected_components
from .hashing import IdentityString, cold_start_hashes
from .schema import ZinggConf


class Zingg:
    def __init__(self, cfg: ZinggConf, link: bool = False):
        self.cfg = cfg
        self.link = link

    def find_training_data(
        self,
        df: pd.DataFrame,
        prior_model: Optional[model_mod.TrainedModel] = None,
        prior_tree: Optional[BlockingTree] = None,
        n: int = 30,
    ) -> pd.DataFrame:
        cfg = self.cfg
        with_ids = self._ensure_id(df)
        if prior_tree is not None:
            pairs = pair_mod.self_pairs(
                blocking.assign_blocks(with_ids, prior_tree), cfg, cross_source_only=self.link)
        else:
            pairs = pair_mod.cold_start_pairs(with_ids, cfg, cross_source_only=self.link)
        featured = features.add_features(pairs, cfg)
        if prior_model is not None:
            scored = model_mod.score(prior_model, featured, cfg)
            sampled = active_learning.query_uncertain(scored, cfg, n)
        else:
            sampled = active_learning.cold_start_sample(featured, n, cfg.sample_seed)
        return self._strip_pipeline_columns(sampled)

    def _strip_pipeline_columns(self, df: pd.DataFrame) -> pd.DataFrame:
        to_drop = ["z_poly_features", "z_prob", "rawPrediction",
                   self.cfg.score_col, self.cfg.prediction_col, "z_uncertainty"]
        return df.drop(columns=[c for c in to_drop if c in df.columns]).reset_index(drop=True)

    def train(self, labeled: pd.DataFrame):
        cfg = self.cfg
        pos = labeled[labeled[cfg.label_col] == 1.0]
        tree = self._seed_tree() if len(pos) < 2 else blocking.learn(self._rows_to_pair_maps(pos), cfg)
        model = model_mod.train(labeled, cfg)
        return model, tree

    def cluster(self, df: pd.DataFrame, model: model_mod.TrainedModel, tree: BlockingTree,
                block_strategy: str = "tree") -> pd.DataFrame:
        """Cluster ``df`` into entities.

        ``block_strategy`` controls candidate generation for the final clustering:
          - ``"tree"`` (default): the learned single-key blocking tree — bounded
            block size, high precision, the scalable choice.
          - ``"canopies"``: the multi-key cold-start canopies (as used at
            ``find_training_data`` time) — higher recall, so a cluster whose
            members the single key fails to co-locate is not split, at the cost
            of more candidate pairs (slower) and more false-edge / hub-merge risk.
        """
        cfg = self.cfg
        with_ids = self._ensure_id(df)
        if block_strategy == "canopies":
            pairs = pair_mod.cold_start_pairs(with_ids, cfg, cross_source_only=self.link)
        elif block_strategy == "tree":
            blocked = blocking.assign_blocks(with_ids, tree)
            pairs = pair_mod.self_pairs(blocked, cfg, cross_source_only=self.link)
        else:
            raise ValueError(f"block_strategy must be 'tree' or 'canopies', got {block_strategy!r}")
        featured = features.add_features(pairs, cfg)
        scored = model_mod.score(model, featured, cfg)
        if scored.empty:
            scored[cfg.prediction_col] = pd.Series([], dtype=float)
        else:
            scored[cfg.prediction_col] = (scored[cfg.score_col] >= cfg.threshold).astype(float)
        clustered = connected_components(scored, with_ids[cfg.id_col].tolist(), cfg)
        return with_ids.merge(clustered, on=cfg.id_col, how="left")

    def interactive_session(self, df: pd.DataFrame, prior_slope: float = 6.0, prior_precision: float = 1.0,
                            balance_prior: float = 1.0, seed: Optional[int] = 0):
        from .interactive_session import InteractiveSession
        cfg = self.cfg
        with_ids = self._ensure_id(df)
        pairs = pair_mod.cold_start_pairs(with_ids, cfg, cross_source_only=self.link)
        featured = features.add_features(pairs, cfg)
        return InteractiveSession(cfg, featured, prior_slope, prior_precision, balance_prior, seed)

    # ── internals ────────────────────────────────────────────────────────────

    def _ensure_id(self, df: pd.DataFrame) -> pd.DataFrame:
        if self.cfg.id_col in df.columns:
            return df.reset_index(drop=True)
        out = df.reset_index(drop=True).copy()
        out[self.cfg.id_col] = range(len(out))
        return out

    def _seed_tree(self) -> BlockingTree:
        for f in self.cfg.fields:
            if f.blockable:
                hs = [h for mt in f.match_types for h in cold_start_hashes(mt)]
                h = hs[0] if hs else IdentityString()
                return Node(f.name, h, {}, Leaf("seed"))
        return Leaf("root")

    def _rows_to_pair_maps(self, rows: pd.DataFrame):
        out = []
        for _, r in rows.iterrows():
            out.append({
                f.name: (r.get(f"{ZinggConf.LEFT_PREFIX}{f.name}"),
                         r.get(f"{ZinggConf.RIGHT_PREFIX}{f.name}"))
                for f in self.cfg.fields
            })
        return out
