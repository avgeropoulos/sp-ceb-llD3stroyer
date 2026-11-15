"""The taals (rhythm cycles) the notation can be laid out in.

A taal is a cycle of matras (beats) grouped into vibhags (sections). Each
vibhag is marked the way it is clapped: X for sam, the first beat of the
cycle; a number for a tali (clap); 0 for a khali (wave).
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Taal:
    key: str
    name: str
    vibhags: tuple[int, ...]
    marks: tuple[str, ...]

    @property
    def matras(self) -> int:
        return sum(self.vibhags)

    @property
    def label(self) -> str:
        return f"{self.name} ({self.matras} matras, {'+'.join(map(str, self.vibhags))})"


TAALS: dict[str, Taal] = {
    t.key: t
    for t in (
        Taal("teentaal", "Teentaal", (4, 4, 4, 4), ("X", "2", "0", "3")),
        Taal("keherwa", "Keherwa", (4, 4), ("X", "0")),
        Taal("dadra", "Dadra", (3, 3), ("X", "0")),
        # Rupak's sam falls on a khali; it is written X here like the others.
        Taal("rupak", "Rupak", (3, 2, 2), ("X", "2", "3")),
        Taal("jhaptaal", "Jhaptaal", (2, 3, 2, 3), ("X", "2", "0", "3")),
        Taal("ektaal", "Ektaal", (2, 2, 2, 2, 2, 2), ("X", "0", "2", "0", "3", "4")),
        Taal("deepchandi", "Deepchandi", (3, 4, 3, 4), ("X", "2", "0", "3")),
    )
}
DEFAULT = "teentaal"
