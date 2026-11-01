from __future__ import annotations
import html
import json

def timeline(run):
    if not run.snapshots:
        return "<p>Timeline: N/A (missing state evidence)</p>"
    width, height, left, top = 960, 270, 55, 20
    end = max(run.end_ms, 1)
    totals = []
    for state in run.snapshots:
        values = [sum(u.get(key, 0) for u in state["units"] if u["id"] in ids and u.get("alive"))
                  for ids in (run.self_ids, run.enemy_ids) for key in ("hp", "shield")]
        totals.append((state["gameMs"], values))
    peak = max((max(v) for _, v in totals), default=1) or 1
    x = lambda ms: left + (width - left - 20) * ms / end
    y = lambda hp: height - 35 - hp / peak * (height - top - 55)
    parts = [f'<svg viewBox="0 0 {width} {height}" role="img" aria-label="HP, shield, commands and turns">']
    if run.engage_ms is not None:
        parts.append(f'<rect x="{x(run.engage_ms):.2f}" y="{top}" width="{x(end)-x(run.engage_ms):.2f}" height="{height-55}" fill="#fee9c8"><title>Engagement window</title></rect>')
    for i, color in enumerate(("#167d9a", "#6aaed6", "#c0392b", "#df9d95")):
        points = " ".join(f"{x(ms):.2f},{y(values[i]):.2f}" for ms, values in totals)
        label = ("Self HP", "Self shield", "Enemy HP", "Enemy shield")[i]
        parts.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="2"><title>{label}</title></polyline>')
        parts.append(f'<text x="{left+i*200}" y="{height-4}" fill="{color}" font-size="12">{label}</text>')
    for action in run.actions:
        parts.append(f'<path d="M{x(action["gameMs"]):.2f},{height-32}v-9" stroke="#222"><title>{html.escape(action["kind"])} tick={action["tick"]}</title></path>')
    if run.meta.get("track") == "pause":
        for event in run.gateway:
            if event["kind"] == "lifecycle" and event["event"] == "turn":
                parts.append(f'<path d="M{x(event["gameMs"]):.2f},{top}V{height-35}" stroke="#999" stroke-dasharray="2 4"><title>Turn {event["turn"]}</title></path>')
    parts += [f'<text x="5" y="25" font-size="12">{peak:.0f}</text>',
              f'<text x="{left}" y="{height-18}" font-size="12">0s</text>',
              f'<text x="{width-85}" y="{height-18}" font-size="12">{end/1000:.2f}s</text>', '</svg>']
    return "".join(parts)

def render(run, result):
    outcome = result["metrics"].get("outcome", {}).get("value", {})
    timing = result["metrics"].get("agent_timing", {}).get("value", {})
    evidence = result.get("evidence", {})
    first = f'Outcome: {outcome.get("outcome", "unknown")} | alive={outcome.get("alive", "N/A")} | delta={outcome.get("delta", "N/A")}'
    second = (f'Realtime: track={run.meta.get("track")} | wallMs={timing.get("wallUsedMs", "N/A")} '
              f'| thinkingWallMs={timing.get("thinkingWallMs", "N/A")} '
              f'| evidence={evidence.get("source", "unknown")} verified={evidence.get("verified", False)}')
    reference = result.get("reference")
    text = [first, second, "", "Metric | Tier | Agent/run | Human (T1 only)"]
    rows = []
    for key, metric in result["metrics"].items():
        value = json.dumps(metric["value"], ensure_ascii=False, sort_keys=True)
        human = reference.get("metrics", {}).get(key, {}).get("value") if reference and metric["tier"] == "T1" else None
        human_text = json.dumps(human, ensure_ascii=False, sort_keys=True) if human is not None else "N/A"
        text.append(f'{key} | {metric["tier"]} | {value} | {human_text}')
        rows.append(f'<tr><th>{html.escape(key)}</th><td>{metric["tier"]}</td><td><pre>{html.escape(value)}</pre></td><td><pre>{html.escape(human_text)}</pre></td></tr>')
    diagnostics = json.dumps(result.get("diagnostics", []), ensure_ascii=False, indent=2)
    text += ["", "Diagnostics: " + diagnostics]
    provenance = "" if not reference else f'<p>Reference: {html.escape(reference.get("kind", "human"))}. Only T1 is compared.</p>'
    document = '<!doctype html><meta charset="utf-8"><title>CASE1-duel evaluation</title><style>body{font:15px system-ui;max-width:1280px;margin:30px auto;padding:0 20px;color:#1a2530}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccd;padding:8px;text-align:left;vertical-align:top}pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:0}svg{width:100%;background:#f7f9fb}</style>'
    document += f'<h1>{html.escape(first)}</h1><p>{html.escape(second)}</p>' + timeline(run) + provenance
    document += '<table><thead><tr><th>Metric</th><th>Tier</th><th>Agent/run</th><th>Human (T1)</th></tr></thead><tbody>' + "".join(rows) + '</tbody></table>'
    document += '<h2>Diagnostics</h2><pre>' + html.escape(diagnostics) + '</pre>'
    return "\n".join(text) + "\n", document
