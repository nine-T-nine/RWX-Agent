"""Shared curve/deduction helpers for the micro-case score documents.

The curves mirror `evaluate/scoring-curves.js` exactly (same endpoint interpolation and the
same output clamp), so a micro score reads the same way as the full-match Stage1/Stage2 scores:

* `exp`/`log` are solved in the translated forms `y=exp(x-a)+b` and `y=log(x-a)+b` through both endpoints;
* `quadratic` takes the given `a` and solves `b`/`c` from the two endpoints, then clamps the
  result into `[min(y0,y1), max(y0,y1)]`;
* x outside the segment is clamped to the endpoint value (so "below lo" means y0, "above hi"
  means y1).

Nothing here reads a run directory: callers pass plain numbers.
"""
from __future__ import annotations
import math

SCHEMA = "rwx-micro-score/1"


def clamp(value, low=0.0, high=1.0):
    return min(high, max(low, value))


def curve(x, lo, hi, y0, y1, kind="linear", a=0.0):
    """One segment of a piecewise curve in the original x coordinates."""
    if hi == lo:
        return y0
    u = clamp((x - lo) / (hi - lo))
    if kind == "quadratic":
        b = (y1 - y0 - a * (hi * hi - lo * lo)) / (hi - lo)
        c = y0 - a * lo * lo - b * lo
        value = lo + (hi - lo) * u
        raw = a * value * value + b * value + c
        return min(max(y0, y1), max(min(y0, y1), raw))
    value = lo + (hi - lo) * u
    if kind == "exp":
        # Solve both endpoints for y = exp(x-a) + b.
        delta = y1 - y0
        q = math.exp(hi - lo)
        exp_lo = delta / (q - 1.0)
        shift = lo - math.log(exp_lo)
        offset = y0 - exp_lo
        return math.exp(value - shift) + offset
    if kind == "log":
        # Solve both endpoints for y = log(x-a) + b; the segment must stay in x > a.
        delta = y1 - y0
        if not (delta > 0.0 and lo > 0.0 and hi > lo):
            return y0 + (y1 - y0) * u
        ratio = math.exp(delta)
        shift = (ratio * lo - hi) / (ratio - 1.0)
        offset = y0 - math.log(lo - shift)
        return math.log(value - shift) + offset
    return y0 + (y1 - y0) * u


def piecewise(x, segments, missing=0.0):
    """`segments` is a list of `(lo, hi, y0, y1, kind, a)`; the last y1 is the tail value."""
    if x is None or not math.isfinite(x):
        return missing
    for segment in segments:
        lo, hi, y0, y1 = segment[:4]
        kind = segment[4] if len(segment) > 4 else "linear"
        a = segment[5] if len(segment) > 5 else 0.0
        if x <= hi:
            return curve(x, lo, hi, y0, y1, kind, a)
    return segments[-1][3]


def quantile(values, p):
    ordered = sorted(values)
    if not ordered:
        return None
    index = (len(ordered) - 1) * p
    low = int(index)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (index - low)


def split_outliers(values):
    """Q3 + 1.5*IQR, the same rule the metric plugins use. Returns (kept, outliers, cutoff)."""
    values = list(values)
    if len(values) < 4:
        return values, [], None
    q1, q3 = quantile(values, .25), quantile(values, .75)
    cutoff = q3 + 1.5 * (q3 - q1)
    return [v for v in values if v <= cutoff], [v for v in values if v > cutoff], cutoff


# Delay windows: [0,3] (3,10] (10,25] (25,45] (45,?) -> weights. `K` is the weighted share of
# windows, exactly as the draft says ("按权重求和"), deliberately NOT normalised by the largest
# weight: the cap is what bounds a run that parks every instruction in a high bucket.
#
# The drafts describe `K` as a plain weighted sum, so this stays False. Setting it to True divides
# by the largest weight (18), making K a [0,1] coefficient; that keeps the item graded instead of
# saturating on the first instruction in the top bucket. Both cases share the same switch.
NORMALIZE_DELAY_COEFFICIENT = False
DELAY_BUCKETS = ((0.0, 3.0, 0.0), (3.0, 10.0, 3.0), (10.0, 25.0, 6.0), (25.0, 45.0, 10.0), (45.0, None, 18.0))


def delay_buckets(seconds):
    counts = [0] * len(DELAY_BUCKETS)
    for value in seconds:
        for index, (_, high, _) in enumerate(DELAY_BUCKETS):
            if high is None or value <= high:
                counts[index] += 1
                break
    total = len(seconds)
    rows = [{"low": low, "high": high, "weight": weight, "count": count,
             "share": count / total if total else 0.0}
            for (low, high, weight), count in zip(DELAY_BUCKETS, counts)]
    coefficient = sum(row["share"] * row["weight"] for row in rows)
    if NORMALIZE_DELAY_COEFFICIENT:
        coefficient /= max(weight for _, _, weight in DELAY_BUCKETS)
    return coefficient, rows


def delay_score(seconds, scale):
    """`S = K * scale` (never negative); the item cap is applied once when the document is built."""
    coefficient, rows = delay_buckets(seconds)
    raw = max(0.0, coefficient * scale)
    return raw, {"coefficient": round(coefficient, 3), "coefficientNormalized": NORMALIZE_DELAY_COEFFICIENT,
                 "scale": scale, "raw": round(raw, 3), "buckets": rows,
                 "windows": [round(value, 3) for value in seconds]}


def round1(value):
    return round(value + 0.0, 1)


def cap(value, limit):
    return min(limit, max(0.0, value))


def document(version, baseline, entries, notes=None, diagnostics=None):
    """Assemble the score document: per-item caps first, then the sum, then `baseline - sum`."""
    items, total = [], 0.0
    for entry in entries:
        points = cap(entry.get("raw", 0.0), entry["cap"]) if entry.get("status") == "OK" else 0.0
        entry = dict(entry, points=round1(points))
        total += points
        items.append(entry)
    deduction = round1(total)
    return {"schema": SCHEMA, "version": version, "baseline": baseline, "deduction": deduction,
            "total": round1(max(0.0, baseline - deduction)), "items": items,
            "notes": notes or [], "diagnostics": diagnostics or []}
