"""Pipeline phases — port of ``Phase.scala``."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Phase:
    name: str


Phase.Train = Phase("train")
Phase.Match = Phase("match")
Phase.TrainMatch = Phase("trainMatch")
Phase.FindTrainingData = Phase("findTrainingData")
Phase.Label = Phase("label")
Phase.Link = Phase("link")
Phase.GenerateDocs = Phase("generateDocs")
Phase.Recommend = Phase("recommend")
Phase.UpdateLabel = Phase("updateLabel")
Phase.FindAndLabel = Phase("findAndLabel")

ALL = [
    Phase.Train, Phase.Match, Phase.TrainMatch, Phase.FindTrainingData, Phase.Label,
    Phase.Link, Phase.GenerateDocs, Phase.Recommend, Phase.UpdateLabel, Phase.FindAndLabel,
]


def parse(s: str) -> Phase:
    for p in ALL:
        if p.name == s:
            return p
    valid = "|".join(p.name for p in ALL)
    raise ValueError(f"unknown phase '{s}'. Valid: {valid}")
