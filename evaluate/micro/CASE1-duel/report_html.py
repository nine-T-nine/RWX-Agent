"""Render per-run and overall HTML summaries for the existing CASE1-duel results.

Reads only what the run already wrote (meta/result/gateway/match.replay) and emits dependency-free
HTML with inline SVG charts (hover titles show the exact values). No external assets, so the files
open offline.
"""
from __future__ import annotations
import argparse
import html
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
import sys

CASE = Path(__file__).resolve().parent
ROOT = CASE.parents[2]
sys.path.insert(0, str(CASE.parent))
from common.run import Replay, read_json, read_lines

PALETTE = ["#2563eb", "#dc2626", "#16a34a", "#d97706", "#7c3aed", "#0891b2", "#be123c", "#4d7c0f"]
CSS = """
body{font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;margin:0;background:#f8fafc;color:#0f172a}
main{max-width:1180px;margin:0 auto;padding:28px 20px 60px}
h1{font-size:24px;margin:0 0 4px}h2{font-size:18px;margin:34px 0 10px;border-bottom:1px solid #e2e8f0;padding-bottom:6px}
h3{font-size:15px;margin:20px 0 8px}
.sub{color:#64748b;margin:0 0 18px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:14px 0}
.card{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:10px 12px}
.card b{display:block;font-size:20px;line-height:1.25}.card span{color:#64748b;font-size:12px}
table{border-collapse:collapse;width:100%;background:#fff;font-size:13px;margin:8px 0 4px}
th,td{border:1px solid #e2e8f0;padding:6px 8px;text-align:left;vertical-align:top}
th{background:#f1f5f9;font-weight:600}
tr:nth-child(even) td{background:#fbfdff}
.badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:600;color:#fff}
.win{background:#16a34a}.loss{background:#dc2626}.draw{background:#64748b}.timeout{background:#d97706}.error{background:#7c3aed}
.chart{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:10px 12px;margin:10px 0;overflow:auto}
.chart svg{display:block;max-width:100%;height:auto}
.legend{display:flex;gap:14px;flex-wrap:wrap;font-size:12px;color:#475569;margin:4px 0 0}
.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px;vertical-align:middle}
.note{color:#64748b;font-size:12px;margin:6px 0 0}
a{color:#2563eb;text-decoration:none}a:hover{text-decoration:underline}
code{background:#f1f5f9;padding:1px 5px;border-radius:4px;font-size:12px}
"""


def esc(value) -> str:
    return html.escape("" if value is None else str(value))


def fmt(value, digits=1, suffix=""):
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:,.{digits}f}{suffix}"
    return f"{value:,}{suffix}"


def badge(outcome) -> str:
    kind = esc(outcome or "unknown")
    return f'<span class="badge {kind}">{kind}</span>'


def page(title, body, subtitle=""):
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{esc(title)}</title><style>{CSS}</style></head><body><main>'
            f'<h1>{esc(title)}</h1>' + (f'<p class="sub">{subtitle}</p>' if subtitle else "")
            + body + "</main></body></html>")


# --------------------------------------------------------------------------- SVG helpers
def _svg(width, height, body, title=None):
    t = f"<title>{esc(title)}</title>" if title else ""
    return (f'<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" '
            f'xmlns="http://www.w3.org/2000/svg" role="img">{t}{body}</svg>')


def _axes(width, height, left, top, right, bottom, x_max, y_max, x_label, y_label, x_ticks=6, y_ticks=4, categorical=False, y_labels=True):
    plot_w = width - left - right
    plot_h = height - top - bottom
    parts = [f'<rect x="{left}" y="{top}" width="{plot_w}" height="{plot_h}" fill="#fff" stroke="#cbd5e1"/>']
    for i in range(y_ticks + 1):
        value = y_max * i / y_ticks
        y = top + plot_h - plot_h * i / y_ticks
        parts.append(f'<line x1="{left}" y1="{y:.1f}" x2="{left + plot_w}" y2="{y:.1f}" stroke="#eef2f7"/>')
        if y_labels:
            parts.append(f'<text x="{left - 8}" y="{y + 4:.1f}" text-anchor="end" font-size="11" fill="#64748b">{fmt(value, 1)}</text>')
    if not categorical:
        for i in range(x_ticks + 1):
            value = x_max * i / x_ticks
            x = left + plot_w * i / x_ticks
            parts.append(f'<line x1="{x:.1f}" y1="{top}" x2="{x:.1f}" y2="{top + plot_h}" stroke="#eef2f7"/>')
            parts.append(f'<text x="{x:.1f}" y="{top + plot_h + 16}" text-anchor="middle" font-size="11" fill="#64748b">{fmt(value, 1)}</text>')
    parts.append(f'<text x="{left + plot_w / 2:.1f}" y="{height - 6}" text-anchor="middle" font-size="12" fill="#475569">{esc(x_label)}</text>')
    parts.append(f'<text transform="translate(14,{top + plot_h / 2:.1f}) rotate(-90)" text-anchor="middle" font-size="12" fill="#475569">{esc(y_label)}</text>')
    return "".join(parts), plot_w, plot_h


def bar_chart(items, *, width=880, height=260, x_label="", y_label="", color="#2563eb", digits=1):
    """items: [(label, value, tooltip)]"""
    if not items:
        return '<p class="note">no data</p>'
    left, top, right, bottom = 56, 14, 14, 40
    y_max = max(max(v for _, v, _ in items), 1e-9)
    body, plot_w, plot_h = _axes(width, height, left, top, right, bottom, max(len(items), 1), y_max,
                                 x_label, y_label, x_ticks=min(len(items), 8), y_ticks=4, categorical=True)
    slot = plot_w / len(items)
    for i, (label, value, tip) in enumerate(items):
        bar_w = slot * .62
        bar_h = plot_h * (value / y_max) if y_max else 0
        x = left + slot * i + (slot - bar_w) / 2
        y = top + plot_h - bar_h
        body += (f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_w:.1f}" height="{bar_h:.1f}" fill="{color}" opacity=".85">'
                 f'<title>{esc(tip or label)}: {fmt(value, digits)}</title></rect>')
        body += f'<text x="{x + bar_w / 2:.1f}" y="{top + plot_h + 30}" text-anchor="middle" font-size="10" fill="#475569">{esc(label)}</text>'
    return _svg(width, height, body)


def stacked_bars(categories, series, *, width=880, height=260, x_label="", y_label=""):
    """categories: [label]; series: [(name, [values], color)]"""
    if not categories:
        return '<p class="note">no data</p>'
    left, top, right, bottom = 56, 14, 14, 40
    totals = [sum(s[1][i] for s in series) for i in range(len(categories))]
    y_max = max(max(totals), 1)
    body, plot_w, plot_h = _axes(width, height, left, top, right, bottom, len(categories), y_max,
                                 x_label, y_label, x_ticks=min(len(categories), 8), y_ticks=4, categorical=True)
    slot = plot_w / len(categories)
    for i, label in enumerate(categories):
        bar_w = slot * .62
        x = left + slot * i + (slot - bar_w) / 2
        base = top + plot_h
        for name, values, color in series:
            value = values[i]
            if not value:
                continue
            h = plot_h * value / y_max
            base -= h
            body += (f'<rect x="{x:.1f}" y="{base:.1f}" width="{bar_w:.1f}" height="{h:.1f}" fill="{color}">'
                     f'<title>{esc(label)} {esc(name)}: {fmt(value)}</title></rect>')
        body += f'<text x="{x + bar_w / 2:.1f}" y="{top + plot_h + 30}" text-anchor="middle" font-size="10" fill="#475569">{esc(label)}</text>'
    legend = "".join(f'<span><i style="background:{c}"></i>{esc(n)}</span>' for n, _, c in series)
    return _svg(width, height, body) + f'<div class="legend">{legend}</div>'


def scatter_timeline(points, lanes, x_max, *, width=880, height=280, x_label="game time (s)", lane_colors=None):
    """points: [(x, lane, color, tooltip)]; lanes: [label]"""
    left, top, right, bottom = 96, 16, 14, 42
    body, plot_w, plot_h = _axes(width, height, left, top, right, bottom, max(x_max, 1e-9), max(len(lanes), 1),
                                 x_label, "", x_ticks=8, y_ticks=max(len(lanes) - 1, 1), y_labels=False)
    lane_h = plot_h / max(len(lanes), 1)
    for i, name in enumerate(lanes):
        y = top + plot_h - lane_h * (i + .5)
        body += f'<text x="{left - 10}" y="{y + 4:.1f}" text-anchor="end" font-size="11" fill="#475569">{esc(name)}</text>'
        body += f'<line x1="{left}" y1="{top + plot_h - lane_h * i:.1f}" x2="{left + plot_w}" y2="{top + plot_h - lane_h * i:.1f}" stroke="#eef2f7"/>'
    for x, lane, color, tip in points:
        px = left + plot_w * min(max(x, 0), x_max) / max(x_max, 1e-9)
        py = top + plot_h - lane_h * (lane + .5)
        body += (f'<circle cx="{px:.1f}" cy="{py:.1f}" r="3.2" fill="{color}" opacity=".85">'
                 f'<title>{esc(tip)}</title></circle>')
    return _svg(width, height, body)


def horizontal_bars(items, *, width=880, height=None, label_width=220, color="#2563eb"):
    """items: [(label, value, tooltip, color?)]"""
    if not items:
        return '<p class="note">no data</p>'
    height = height or (len(items) * 26 + 24)
    left, right, top = label_width, 90, 12
    plot_w = width - left - right
    y_max = max(max(v for _, v, _, _ in items), 1e-9)
    body = []
    for i, (label, value, tip, col) in enumerate(items):
        y = top + i * 26
        w = plot_w * value / y_max
        body.append(f'<text x="{left - 10}" y="{y + 15}" text-anchor="end" font-size="12" fill="#334155">{esc(label)}</text>')
        body.append(f'<rect x="{left}" y="{y + 4}" width="{max(w, 0.5):.1f}" height="16" rx="3" fill="{col or color}" opacity=".85">'
                    f'<title>{esc(tip or label)}: {fmt(value, 1)}</title></rect>')
        body.append(f'<text x="{left + w + 6:.1f}" y="{y + 16}" font-size="11" fill="#475569">{fmt(value, 1)}</text>')
    return _svg(width, height, "".join(body))


def map_svg(meta, result, actions, *, size=640):
    width = int(meta.get("map", {}).get("width") or 0) or 1
    height = int(meta.get("map", {}).get("height") or 0) or 1
    tile = meta.get("map", {}).get("tileSize") or 20
    scale = size / max(width, height)
    w, h = width * scale, height * scale
    body = [f'<rect x="0" y="0" width="{w:.1f}" height="{h:.1f}" fill="#e8f3df" stroke="#94a3b8"/>']
    colors = {"water": "#7dd3fc", "cliff": "#b08968"}
    for region in meta.get("regions") or []:
        col = colors.get(region.get("kind"), "#cbd5e1")
        for k in region.get("cells") or []:
            x, y = k % width, k // width
            body.append(f'<rect x="{x * scale:.2f}" y="{y * scale:.2f}" width="{max(scale, .7):.2f}" height="{max(scale, .7):.2f}" fill="{col}"/>')
    # order path for our side
    path = [(a["x"], a["y"]) for a in actions if a.get("team") == 0 and a.get("x") and a.get("y")
            and a.get("type") in {"move", "attackMove", "patrol"} and 0 < a["x"] < width * tile and 0 < a["y"] < height * tile]
    if len(path) > 1:
        pts = " ".join(f"{p[0] / tile * scale:.1f},{p[1] / tile * scale:.1f}" for p in path)
        body.append(f'<polyline points="{pts}" fill="none" stroke="#2563eb" stroke-width="1.6" opacity=".8"/>')
        for x, y in path:
            body.append(f'<circle cx="{x / tile * scale:.1f}" cy="{y / tile * scale:.1f}" r="2.2" fill="#2563eb"><title>order at {x:.0f},{y:.0f}</title></circle>')
    # units: initial positions from meta, final hp from result
    final = {u["id"]: u for u in (result.get("units") or [])}
    for unit in meta.get("units") or []:
        x = unit["x"] / tile * scale
        y = unit["y"] / tile * scale
        team = unit.get("team", 0)
        col = "#2563eb" if team == 0 else "#dc2626"
        state = final.get(unit["id"], {})
        tip = f'{unit.get("alias") or unit["id"]} {unit.get("type")} hp={state.get("hp", "?")}/{unit.get("maxHp")}'
        body.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="6" fill="{col}" stroke="#fff" stroke-width="1.5"><title>{esc(tip)}</title></circle>')
        body.append(f'<text x="{x + 9:.1f}" y="{y + 4:.1f}" font-size="11" fill="#0f172a">{esc(unit.get("alias") or unit["id"])}</text>')
    legend = '<div class="legend"><span><i style="background:#7dd3fc"></i>water</span><span><i style="background:#b08968"></i>cliff</span><span><i style="background:#2563eb"></i>our unit / orders</span><span><i style="background:#dc2626"></i>enemy</span></div>'
    return _svg(w + 2, h + 2, "".join(body)) + legend


def unit_bars(meta, result):
    final = {u["id"]: u for u in (result.get("units") or [])}
    items = []
    for unit in meta.get("units") or []:
        state = final.get(unit["id"], {})
        alias = unit.get("alias") or unit["id"]
        items.append((f'{alias} HP', state.get("hp", 0) or 0, f'{alias} final hp / {unit.get("maxHp")}', "#16a34a" if unit.get("team") == 0 else "#dc2626"))
        if (unit.get("maxShield") or 0) > 0:
            items.append((f'{alias} shield', state.get("shield", 0) or 0, f'{alias} final shield / {unit.get("maxShield")}', "#0891b2"))
    return horizontal_bars(items)


# --------------------------------------------------------------------------- run loading
class RunData:
    def __init__(self, directory: Path):
        self.directory = directory
        self.meta = read_json(directory / "meta.json", {}) or {}
        self.result = read_json(directory / "result.json", {}) or {}
        self.requests = [r for r in read_lines(directory / "gateway.jsonl") if r.get("kind") == "request"]
        self.lifecycle = [r for r in read_lines(directory / "gateway.jsonl") if r.get("kind") == "lifecycle"]
        self.actions = []
        self.replay_ok = False
        replay_file = directory / self.meta.get("replay", "match.replay")
        if replay_file.is_file():
            try:
                replay = Replay.load(str(replay_file), strict=True)
                self.replay_ok = replay.ok
                self.actions = replay.order_rows()
            except Exception:
                self.actions = []
        self.classes = Counter(r.get("class") for r in self.requests)
        self.statuses = Counter(r.get("status") for r in self.requests)
        self.native = Counter(a.get("type") or ("action:" + str(a.get("action"))) for a in self.actions)
        self.ab_latency = []
        observations = [r for r in self.requests if r.get("class") == "A"]
        for b in (r for r in self.requests if r.get("class") == "B"):
            before = [a for a in observations if a.get("wallReplyMs", 0) <= b.get("wallRecvMs", 0)]
            if before:
                a = before[-1]
                self.ab_latency.append({"from": a.get("seq"), "to": b.get("seq"),
                                        "ms": b.get("wallRecvMs", 0) - a.get("wallReplyMs", 0)})
        self.aa_intervals = [b.get("wallRecvMs", 0) - a.get("wallRecvMs", 0) for a, b in zip(observations, observations[1:])]

    def duration(self):
        return (self.result.get("gameMs") or 0) / 1000.0

    def summary(self):
        return {
            "name": self.directory.name, "task": self.meta.get("task"), "seed": self.meta.get("seed"),
            "track": self.meta.get("track"), "mode": self.meta.get("mode"),
            "outcome": self.result.get("outcome"), "endReason": self.result.get("endReason"),
            "gameMs": self.result.get("gameMs"), "tick": self.result.get("tick"), "wallMs": self.result.get("wallMs"),
            "requests": len(self.requests), "classes": dict(self.classes), "statuses": dict(self.statuses),
            "native": len(self.actions), "kills": len(self.result.get("kills") or []),
            "abLatencyMedian": _median([x["ms"] for x in self.ab_latency]),
        }


def _median(values):
    if not values:
        return None
    values = sorted(values)
    n = len(values)
    return values[n // 2] if n % 2 else (values[n // 2 - 1] + values[n // 2]) / 2


# --------------------------------------------------------------------------- per-run page
def render_run(run: RunData, siblings=None):
    meta, result = run.meta, run.result
    s = run.summary()
    body = [f'<p class="sub">{esc(s["name"])}</p>',
            f'<p>outcome {badge(s["outcome"])} <code>{esc(s["endReason"])}</code> · task <code>{esc(s["task"])}</code>'
            f' · seed <code>{esc(s["seed"])}</code> · track <code>{esc(s["track"])}</code> · mode <code>{esc(s["mode"])}</code></p>']
    body.append('<div class="cards">'
                f'<div class="card"><b>{fmt(s["gameMs"] / 1000 if s["gameMs"] else None, 1, " s")}</b><span>game time</span></div>'
                f'<div class="card"><b>{fmt(s["tick"])}</b><span>ticks</span></div>'
                f'<div class="card"><b>{fmt((s["wallMs"] or 0) / 1000, 1, " s")}</b><span>wall time (from first request)</span></div>'
                f'<div class="card"><b>{s["requests"]}</b><span>CLI requests</span></div>'
                f'<div class="card"><b>{s["native"]}</b><span>native replay commands</span></div>'
                f'<div class="card"><b>{s["kills"]}</b><span>kills</span></div>'
                '</div>')
    # map
    body.append("<h2>Map, units and order path</h2>")
    body.append(f'<div class="chart">{map_svg(meta, result, run.actions)}<p class="note">'
                f'map {meta.get("map", {}).get("width")}×{meta.get("map", {}).get("height")} tiles · '
                f'sha256 {esc((meta.get("map", {}).get("sha256") or "")[:16])}… · blue polyline = our move/attackMove orders</p></div>')
    # request timeline
    body.append("<h2>CLI request timeline</h2>")
    lanes = ["A observe", "B order", "C lifecycle"]
    lane_index = {"A": 0, "B": 1, "C": 2}
    x_max = max([(r.get("gameMs") or 0) / 1000 for r in run.requests] + [run.duration(), 1])
    points = []
    for r in run.requests:
        cls = r.get("class") or "C"
        ok = (r.get("status") or "") == "submitted"
        color = "#16a34a" if ok else "#dc2626" if r.get("status") in {"rejected", "invalid_request", "error"} else "#64748b"
        tip = f'#{r.get("seq")} {r.get("name")} · {r.get("status")} · t={(r.get("gameMs") or 0) / 1000:.2f}s'
        points.append(((r.get("gameMs") or 0) / 1000, lane_index.get(cls, 2), color, tip))
    body.append(f'<div class="chart">{scatter_timeline(points, lanes, x_max)}'
                '<div class="legend"><span><i style="background:#16a34a"></i>submitted</span>'
                '<span><i style="background:#dc2626"></i>rejected / invalid</span></div>'
                '<p class="note">Each dot is one request; hover for seq/name/status. Rejected requests never reach the engine.</p></div>')
    # native timeline
    body.append("<h2>Native replay commands</h2>")
    if run.actions:
        teams = sorted({a.get("team") for a in run.actions if a.get("team") is not None})
        lanes = [f"team {t}" for t in teams] or ["commands"]
        lane_of = {t: i for i, t in enumerate(teams)}
        types = sorted({(a.get("type") or a.get("action") or "?") for a in run.actions})
        color_of = {t: PALETTE[i % len(PALETTE)] for i, t in enumerate(types)}
        pts = []
        for a in run.actions:
            kind = a.get("type") or a.get("action") or "?"
            tip = f'{kind} · team {a.get("team")} · tick {a.get("tick")} · {a.get("game_time_ms", 0) / 1000:.2f}s'
            pts.append(((a.get("game_time_ms") or 0) / 1000, lane_of.get(a.get("team"), 0), color_of[kind], tip))
        body.append(f'<div class="chart">{scatter_timeline(pts, lanes, x_max, x_label="game time (s)")}'
                    + '<div class="legend">' + "".join(f'<span><i style="background:{color_of[t]}"></i>{esc(t)}</span>' for t in types) + '</div>'
                    + f'<p class="note">{len(run.actions)} commands parsed from match.replay'
                      + ('' if run.replay_ok else ' (replay incomplete)') + '.</p></div>')
    else:
        body.append('<p class="note">no native commands parsed from match.replay</p>')
    # hp bars
    body.append("<h2>Final HP and shield</h2>")
    body.append(f'<div class="chart">{unit_bars(meta, result)}<p class="note">Bars show the final values from result.json; max values come from meta.json.</p></div>')
    # latency
    body.append("<h2>A→B decision latency</h2>")
    if run.ab_latency:
        body.append(f'<div class="chart">{horizontal_bars([(f"#{x["from"]}→#{x["to"]}", x["ms"], f"{x["ms"]} ms", "#7c3aed") for x in run.ab_latency])}'
                    f'<p class="note">Last observation reply → order request. Includes model, runtime and tool scheduling.</p></div>')
    else:
        body.append('<p class="note">no A→B pair in this run</p>')
    # request table
    body.append("<h2>Requests</h2><table><tr><th>seq</th><th>class</th><th>command</th><th>status</th>"
                "<th>gameMs</th><th>wall recv→reply</th></tr>")
    for r in run.requests:
        body.append(f'<tr><td>{esc(r.get("seq"))}</td><td>{esc(r.get("class"))}</td><td><code>{esc(r.get("name"))}</code></td>'
                    f'<td>{esc(r.get("status"))}</td><td>{fmt(r.get("gameMs"))}</td>'
                    f'<td>{fmt(r.get("wallRecvMs"))}→{fmt(r.get("wallReplyMs"))}</td></tr>')
    body.append('</table>')
    # native table
    if run.actions:
        body.append("<h2>Native commands</h2><table><tr><th>tick</th><th>time</th><th>team</th><th>type</th><th>target</th><th>units</th></tr>")
        for a in run.actions:
            target = a.get("target_unit") if a.get("target_unit") not in (None, -1) else (f'{a.get("x")},{a.get("y")}' if a.get("x") else "")
            body.append(f'<tr><td>{esc(a.get("tick"))}</td><td>{esc(a.get("clock"))}</td><td>{esc(a.get("team"))}</td>'
                        f'<td>{esc(a.get("type") or a.get("action"))}</td><td>{esc(target)}</td><td>{esc(a.get("units"))}</td></tr>')
        body.append('</table>')
    if siblings:
        body.append('<p class="note"><a href="index.html">← all CASE1-duel runs</a></p>')
    return page(f'CASE1-duel · {s["name"]}', "".join(body), f'Run summary generated from meta/result/gateway/match.replay · {esc(s["name"])}')


# --------------------------------------------------------------------------- overall page
def render_index(runs):
    summaries = [r.summary() for r in runs]
    summaries.sort(key=lambda s: (str(s["task"]), s["seed"] if s["seed"] is not None else 0, str(s["name"])))
    body = ['<p class="sub">Summary of every run already present under <code>evaluate/output/micro/CASE1-duel</code>, plus the evaluation dimensions CASE1 defines. Generated from the existing evidence only.</p>']
    # what CASE1 evaluates
    body.append("<h2>What CASE1-duel evaluates</h2>")
    body.append("""<table>
<tr><th>tier</th><th>metric</th><th>what it measures</th><th>sources</th></tr>
<tr><td>T1</td><td><code>outcome</code></td><td>winner, alive counts, HP/shield net-loss fractions and delta</td><td>replay + re-sim snapshots</td></tr>
<tr><td>T1</td><td><code>cadence</code></td><td>interval between our native commands, command density while engaged</td><td>replay</td></tr>
<tr><td>T1</td><td><code>ENGAGE_LAG</code></td><td>first contact → first responsive command</td><td>replay + snapshots</td></tr>
<tr><td>T1</td><td><code>LOWHP_LAG</code></td><td>HP crossing 50%/25% → first command</td><td>replay + snapshots</td></tr>
<tr><td>T1</td><td><code>IDLE_IN_COMBAT</code></td><td>idle time while engaged</td><td>replay + snapshots</td></tr>
<tr><td>T1</td><td><code>STALE_COMMAND</code></td><td>commands that reference dead or missing units</td><td>replay</td></tr>
<tr><td>T1</td><td><code>FIRE_UPTIME</code></td><td>share of time firing with an enemy in range</td><td>snapshots</td></tr>
<tr><td>T1</td><td><code>FREE_FIRE</code></td><td>damage dealt outside the enemy's range</td><td>snapshots</td></tr>
<tr><td>T2</td><td><code>agent_timing</code></td><td>A/B/C counts, wall-clock decision cadence, rejects/skips</td><td>gateway + meta</td></tr>
<tr><td>T3</td><td><code>turns</code></td><td>pause turns, per-turn wall time and command count</td><td>gateway lifecycle</td></tr>
</table>
<p class="note">No weighted total score: T1 is comparable with human replays, T2 is agent-only, T3 only applies to the pause track. Metrics marked N/A keep that meaning.</p>""")
    # run matrix
    body.append("<h2>Runs</h2>")
    body.append('<table><tr><th>run</th><th>task</th><th>seed</th><th>track</th><th>mode</th><th>outcome</th>'
                '<th>game time</th><th>wall time</th><th>requests A/B/C</th><th>native</th><th>kills</th><th>report</th></tr>')
    for s in summaries:
        cls = s["classes"]
        body.append(f'<tr><td><code>{esc(s["name"])}</code></td><td>{esc(s["task"])}</td><td>{esc(s["seed"])}</td>'
                    f'<td>{esc(s["track"])}</td><td>{esc(s["mode"])}</td><td>{badge(s["outcome"])}</td>'
                    f'<td>{fmt((s["gameMs"] or 0) / 1000, 1, " s")}</td><td>{fmt((s["wallMs"] or 0) / 1000, 1, " s")}</td>'
                    f'<td>{cls.get("A", 0)}/{cls.get("B", 0)}/{cls.get("C", 0)}</td><td>{s["native"]}</td><td>{s["kills"]}</td>'
                    f'<td><a href="{esc(s["name"])}.html">open</a></td></tr>')
    body.append('</table>')
    # charts
    labels = [f'{s["task"]}/{s["seed"]}/{s["track"]}' for s in summaries]
    body.append("<h2>Game time per run</h2>")
    body.append(f'<div class="chart">{bar_chart([(labels[i], (s["gameMs"] or 0) / 1000, s["name"]) for i, s in enumerate(summaries)], y_label="game seconds")}</div>')
    body.append("<h2>Requests by class</h2>")
    body.append(f'<div class="chart">{stacked_bars(labels, [("A observe", [s["classes"].get("A", 0) for s in summaries], "#2563eb"), ("B order", [s["classes"].get("B", 0) for s in summaries], "#dc2626"), ("C lifecycle", [s["classes"].get("C", 0) for s in summaries], "#94a3b8")], y_label="requests")}</div>')
    body.append("<h2>Native commands per run</h2>")
    body.append(f'<div class="chart">{bar_chart([(labels[i], s["native"], s["name"]) for i, s in enumerate(summaries)], y_label="commands", color="#16a34a")}</div>')
    body.append("<h2>A→B decision latency (median)</h2>")
    body.append(f'<div class="chart">{bar_chart([(labels[i], s["abLatencyMedian"] or 0, s["name"]) for i, s in enumerate(summaries)], y_label="ms", color="#7c3aed")}</div>')
    outcomes = Counter(s["outcome"] for s in summaries)
    body.append("<h2>Outcomes</h2>")
    body.append(f'<div class="chart">{bar_chart([(k, v, f"{k}: {v}") for k, v in outcomes.items()], y_label="runs", color="#0f172a")}</div>')
    body.append('<p class="note">Charts use the same data as the tables; hover a bar or dot for the exact value. '
                'Per-run pages add the map, request timeline, native command timeline, HP/shield and latency details.</p>')
    return page("CASE1-duel · evaluation summary", "".join(body),
                "CASE1-duel evaluation summary (T1/T2/T3) and the runs currently on disk")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=ROOT / "evaluate/output/micro/CASE1-duel")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    out = (args.out or root / "reports").resolve()
    out.mkdir(parents=True, exist_ok=True)
    runs = [RunData(d) for d in sorted(root.iterdir()) if d.is_dir() and (d / "meta.json").is_file()]
    for run in runs:
        (out / f"{run.directory.name}.html").write_text(render_run(run, siblings=True), encoding="utf-8")
    (out / "index.html").write_text(render_index(runs), encoding="utf-8")
    print(json.dumps({"runs": len(runs), "output": str(out)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
