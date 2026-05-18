"""Python port of the Zingg record-linkage core (see ``zscala/``)."""

from .schema import FieldDef, MatchType, ZinggConf
from .zingg import Zingg

__all__ = ["FieldDef", "MatchType", "ZinggConf", "Zingg"]
