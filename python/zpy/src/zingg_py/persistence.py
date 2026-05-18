"""Model / artifact persistence — the on-disk layout of ``Phases.scala``.

A model dir holds:
  model.pkl          – the trained classifier (pickled ``TrainedModel``)
  blockingTree.json  – the learned blocking tree (inspectable JSON)
  unmarked.pkl       – candidate pairs awaiting labels (findTrainingData output)
  marked.pkl         – labelled pairs (label phase output, appended across runs)

Candidate/marked frames are pickled rather than parquet because the
``z_features`` column holds per-row numpy vectors (no clean columnar form).
"""

from __future__ import annotations

import pickle
from pathlib import Path

import pandas as pd

from . import blocking
from .blocking import BlockingTree
from .model import TrainedModel

MODEL_FILE = "model.pkl"
TREE_FILE = "blockingTree.json"
MARKED = "marked.pkl"
UNMARKED = "unmarked.pkl"


def save_model(model: TrainedModel, model_dir) -> None:
    d = Path(model_dir)
    d.mkdir(parents=True, exist_ok=True)
    with open(d / MODEL_FILE, "wb") as fh:
        pickle.dump(model, fh)


def load_model(model_dir) -> TrainedModel:
    with open(Path(model_dir) / MODEL_FILE, "rb") as fh:
        return pickle.load(fh)


def save_tree(tree: BlockingTree, model_dir) -> None:
    d = Path(model_dir)
    d.mkdir(parents=True, exist_ok=True)
    (d / TREE_FILE).write_text(blocking.to_json(tree))


def load_tree(model_dir) -> BlockingTree:
    return blocking.from_json((Path(model_dir) / TREE_FILE).read_text())


def write_frame(df: pd.DataFrame, model_dir, name: str, append: bool = False) -> None:
    d = Path(model_dir)
    d.mkdir(parents=True, exist_ok=True)
    path = d / name
    if append and path.exists():
        df = pd.concat([pd.read_pickle(path), df], ignore_index=True)
    df.to_pickle(path)


def read_frame(model_dir, name: str) -> pd.DataFrame:
    return pd.read_pickle(Path(model_dir) / name)
