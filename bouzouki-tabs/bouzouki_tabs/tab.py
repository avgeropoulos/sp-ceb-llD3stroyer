"""ASCII tablature rendering."""

from __future__ import annotations

from .audio import format_time
from .fretboard import Position
from .instrument import Tuning
from .notes import Note
from .rhythm import Grid


def course_labels(tuning: Tuning) -> list[str]:
    """Labels for each course, lowest first. Repeated names get lower case for the higher course."""
    labels, seen = [], set()
    for name in tuning.labels:
        labels.append(name.lower() if name in seen else name)
        seen.add(name)
    return labels


def place_on_grid(notes: list[Note], positions: list[Position], grid: Grid) -> dict[int, Position]:
    """Map grid slots to positions. Notes faster than the grid nudge one slot later or are dropped."""
    slots: dict[int, Position] = {}
    last = -1
    for note, pos in zip(notes, positions):
        natural = max(grid.slot(note.start), 0)
        slot = max(natural, last + 1)
        if slot - natural > 1:
            continue
        slots[slot] = pos
        last = slot
    return slots


def render_tab(
    notes: list[Note],
    positions: list[Position],
    tuning: Tuning,
    grid: Grid,
    width: int = 100,
    time_offset: float = 0.0,
) -> str:
    if not notes:
        return "(no bouzouki notes detected)\n"
    slots = place_on_grid(notes, positions, grid)
    per_bar = grid.slots_per_bar
    n_bars = max(slots) // per_bar + 1
    cell = 3 if any(p.fret >= 10 for p in slots.values()) else 2
    labels = course_labels(tuning)
    label_w = max(len(l) for l in labels)
    bar_w = per_bar * cell + 1
    bars_per_line = max(1, (width - label_w - 1) // bar_w)

    def bar_rows(bar: int) -> list[str]:
        rows = []
        for string in range(len(tuning.strings) - 1, -1, -1):
            cells = []
            for slot in range(bar * per_bar, (bar + 1) * per_bar):
                pos = slots.get(slot)
                text = str(pos.fret) if pos is not None and pos.string == string else ""
                cells.append(text.ljust(cell, "-"))
            rows.append("".join(cells))
        return rows

    def is_empty(bar: int) -> bool:
        return not any(bar * per_bar <= s < (bar + 1) * per_bar for s in slots)

    out: list[str] = []
    bar = 0
    while bar < n_bars:
        if is_empty(bar):
            run = bar
            while run < n_bars and is_empty(run):
                run += 1
            if run - bar >= 2:
                stamp = format_time(grid.time(bar * per_bar) + time_offset)
                out.append(f"[{stamp}]  ... {run - bar} bars without bouzouki ...\n")
                bar = run
                continue
        line_bars = []
        while bar < n_bars and len(line_bars) < bars_per_line:
            if line_bars and is_empty(bar) and bar + 1 < n_bars and is_empty(bar + 1):
                break
            line_bars.append(bar)
            bar += 1
        stamp = format_time(grid.time(line_bars[0] * per_bar) + time_offset)
        out.append(f"[{stamp}]  bar {line_bars[0] + 1}")
        rendered = [bar_rows(b) for b in line_bars]
        for row, label in enumerate(reversed(labels)):
            out.append(label.rjust(label_w) + "|" + "|".join(r[row] for r in rendered) + "|")
        out.append("")
    return "\n".join(out)
