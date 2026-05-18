"""Phase dispatcher — port of ``Phases.scala``.

Maps a parsed :class:`~zingg_py.phase.Phase` to the pipeline, reading/writing the
model dir laid out by :mod:`zingg_py.persistence`. ``labeller`` is the batch /
online labeller to use (CLI or LLM); it is only consulted by the labelling
phases.
"""

from __future__ import annotations

from . import io, persistence
from .config_loader import Loaded
from .labeller import Labeller
from .phase import Phase
from .zingg import Zingg


def run(phase: Phase, loaded: Loaded, model_dir, labeller: Labeller) -> None:
    if phase == Phase.FindTrainingData:
        find_training_data(loaded, model_dir)
    elif phase == Phase.Label:
        label(loaded, model_dir, labeller)
    elif phase == Phase.FindAndLabel:
        find_and_label(loaded, model_dir, labeller)
    elif phase == Phase.Train:
        train(loaded, model_dir)
    elif phase in (Phase.Match, Phase.Link):
        match_phase(loaded, model_dir)
    elif phase == Phase.TrainMatch:
        train(loaded, model_dir)
        match_phase(loaded, model_dir)
    else:
        raise NotImplementedError(f"phase '{phase.name}' is not wired through the zpy pipeline")


def find_training_data(loaded: Loaded, model_dir) -> None:
    df = io.read_inputs(loaded)
    candidates = Zingg(loaded.cfg, link=loaded.link).find_training_data(df)
    persistence.write_frame(candidates, model_dir, persistence.UNMARKED)


def label(loaded: Loaded, model_dir, labeller: Labeller) -> None:
    unmarked = persistence.read_frame(model_dir, persistence.UNMARKED)
    labelled = labeller.label(unmarked, loaded.cfg)
    persistence.write_frame(labelled, model_dir, persistence.MARKED, append=True)


def find_and_label(loaded: Loaded, model_dir, labeller: Labeller, max_labels=None) -> int:
    """Adaptive one-pair-at-a-time labelling driven by the online session.

    Re-fits the Bayesian posterior after every answer (BALD selection), so the
    human stops being shown long runs of obvious non-matches. Returns the number
    of definite labels collected. ``max_labels`` caps the loop (used by the
    unattended LLM path so it doesn't label the whole pool).
    """
    df = io.read_inputs(loaded)
    cfg = loaded.cfg
    session = Zingg(cfg, link=loaded.link).interactive_session(df)
    labelled = 0
    try:
        while max_labels is None or labelled < max_labels:
            pair = session.next_pair()
            if pair is None:
                break
            ans = labeller.ask(pair, cfg, f"━━━ Pair {labelled + 1} ━━━")
            if ans.is_quit:
                break
            if ans.is_skip:
                session.skip(pair)
            else:
                session.submit_label(pair, ans.value)
                labelled += 1
        persistence.write_frame(session.labeled, model_dir, persistence.MARKED, append=True)
    finally:
        session.close()
    return labelled


def train(loaded: Loaded, model_dir) -> None:
    marked = persistence.read_frame(model_dir, persistence.MARKED)
    model, tree = Zingg(loaded.cfg, link=loaded.link).train(marked)
    persistence.save_model(model, model_dir)
    persistence.save_tree(tree, model_dir)


def match_phase(loaded: Loaded, model_dir) -> None:
    df = io.read_inputs(loaded)
    model = persistence.load_model(model_dir)
    tree = persistence.load_tree(model_dir)
    clusters = Zingg(loaded.cfg, link=loaded.link).cluster(df, model, tree)
    io.write_outputs(clusters, loaded.outputs)
