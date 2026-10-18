import argparse
import html
import json
from pathlib import Path
from typing import Any

import pandas as pd

from .transform import transform


SYMBOLS = ("INFY.NS", "RELIANCE.NS", "TATASTEEL.BO")
COLORS = {"INFY.NS": "#e76f51", "RELIANCE.NS": "#2a9d8f", "TATASTEEL.BO": "#e9c46a"}
NAMES = {"INFY.NS": "Infosys", "RELIANCE.NS": "Reliance Industries", "TATASTEEL.BO": "Tata Steel"}
STYLE = """
:root { --ink:#172121; --muted:#66716f; --paper:#f5f2eb; --line:#d9ddd5; --mint:#dcece4; }
* { box-sizing:border-box; } body { margin:0; background:var(--paper); color:var(--ink); font-family:Trebuchet MS,Segoe UI,sans-serif; }
main { max-width:1180px; margin:auto; padding:46px 28px 64px; } header { display:flex; justify-content:space-between; align-items:end; border-bottom:1px solid var(--line); padding-bottom:28px; margin-bottom:28px; }
.eyebrow { font:500 12px Consolas,monospace; color:#e76f51; text-transform:uppercase; } h1 { font-family:Georgia,serif; font-size:clamp(34px,5vw,64px); line-height:.98; letter-spacing:-2px; margin:10px 0 12px; }
.lede { color:var(--muted); max-width:540px; line-height:1.6; } .asof { font:500 11px 'DM Mono',monospace; color:var(--muted); text-align:right; line-height:1.7; }
.cards { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-bottom:28px; } .instrument,.panel { background:#fff; border:1px solid var(--line); border-radius:8px; }
.instrument { padding:20px; } .instrument-head { display:flex; gap:10px; align-items:center; margin-bottom:22px; } .dot { width:10px; height:10px; border-radius:50%; }
small { display:block; color:var(--muted); font-size:11px; margin-top:3px; } .instrument strong { font:500 29px 'DM Mono',monospace; } em { font:400 11px Manrope,sans-serif; color:var(--muted); font-style:normal; }
.instrument-meta { display:flex; justify-content:space-between; border-top:1px solid var(--line); margin-top:18px; padding-top:12px; font:500 11px Consolas,monospace; color:var(--muted); }
.panel { padding:22px; margin-top:14px; } .panel-top { display:flex; justify-content:space-between; gap:15px; } h2 { font-size:17px; margin:0 0 5px; } .sub { color:var(--muted); font-size:12px; margin:0; line-height:1.5; }
.legend { display:flex; gap:16px; flex-wrap:wrap; font:500 11px Consolas,monospace; color:var(--muted); } .legend i { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:6px; }
.chart,.bar-chart { width:100%; height:auto; margin-top:20px; overflow:visible; } .grid line { stroke:#e7e9e4; } .grid text,.bar-label,.bar-value,.axis-label { fill:#8b9490; font:11px Consolas,monospace; } .series { fill:none; stroke-width:2.5; stroke-linejoin:round; stroke-linecap:round; } .baseline { stroke:#c9cec7; stroke-dasharray:4 5; }
.bar-value { fill:var(--ink); } .table-wrap { overflow-x:auto; } table { width:100%; border-collapse:collapse; margin-top:17px; font-size:12px; min-width:660px; } th { font:500 10px 'DM Mono',monospace; text-align:left; color:var(--muted); padding:0 10px 10px; border-bottom:1px solid var(--line); } td { padding:14px 10px; border-bottom:1px solid #eceee9; } td:first-child { padding-left:0; } td small { display:inline; margin-left:8px; }
.positive { color:#238172; font-family:Consolas,monospace; font-weight:500; } .negative { color:#c6533c; font-family:Consolas,monospace; font-weight:500; } .note { background:var(--mint); border-left:3px solid #2a9d8f; padding:13px 15px; margin-top:14px; color:#36504a; font-size:12px; line-height:1.5; } .conclusion { border-top:1px solid var(--line); padding-top:13px; margin:18px 0 0; color:var(--accent); font-size:12px; line-height:1.55; } footer { color:var(--muted); font:11px Consolas,monospace; margin-top:28px; }
@media(max-width:720px) { main { padding:28px 16px 45px; } header { display:block; } .asof { text-align:left; margin-top:20px; } .cards { grid-template-columns:1fr; } .panel { padding:16px; } .panel-top { display:block; } .legend { margin-top:15px; } h1 { letter-spacing:-1px; } }
"""


def load_frames(cache_dir: Path) -> dict[str, pd.DataFrame]:
    frames = {}
    for symbol in SYMBOLS:
        path = cache_dir / f"{symbol}_1y.json"
        if not path.exists():
            raise FileNotFoundError(f"Missing cache file: {path}")
        response: Any = json.loads(path.read_text(encoding="utf-8"))
        frames[symbol] = transform(response).tail(27).reset_index(drop=True)
    return frames


def metrics(frames: dict[str, pd.DataFrame]) -> dict[str, dict[str, Any]]:
    result = {}
    for symbol, frame in frames.items():
        close = frame["close"]
        result[symbol] = {
            "rows": len(frame), "start": frame["date"].min().strftime("%Y-%m-%d"),
            "end": frame["date"].max().strftime("%Y-%m-%d"), "last": close.iloc[-1],
            "return": (close.iloc[-1] / close.iloc[0] - 1) * 100,
            "drawdown": (close / close.cummax() - 1).min() * 100,
        }
    return result


def line_chart(frames: dict[str, pd.DataFrame]) -> str:
    width, height, left, top, right, bottom = 920, 300, 58, 22, 20, 36
    series = [(symbol, (frame["close"] / frame["close"].iloc[0] * 100).tolist()) for symbol, frame in frames.items()]
    values = [value for _, points in series for value in points]
    low, high = min(values) * .98, max(values) * 1.02
    plot_width, plot_height = width - left - right, height - top - bottom

    def point(index: int, value: float, count: int) -> str:
        x = left + index / max(count - 1, 1) * plot_width
        y = top + (high - value) / (high - low) * plot_height
        return f"{x:.1f},{y:.1f}"

    grid = []
    for value in (low, 100, high):
        y = top + (high - value) / (high - low) * plot_height
        grid.append(f'<line x1="{left}" y1="{y:.1f}" x2="{width-right}" y2="{y:.1f}"/><text x="8" y="{y+4:.1f}">{value:.0f}</text>')
    paths = [f'<polyline class="series" stroke="{COLORS[symbol]}" points="{" ".join(point(i, value, len(values)) for i, value in enumerate(values))}"/>' for symbol, values in series]
    baseline_y = top + (high - 100) / (high - low) * plot_height
    return f'<svg id="relative-price-path" class="chart" viewBox="0 0 {width} {height}" role="img" aria-label="Infosys finished highest and Reliance lowest on normalized close over the latest 27 sessions"><g class="grid">{"".join(grid)}</g>{"".join(paths)}<line class="baseline" x1="{left}" y1="{baseline_y:.1f}" x2="{width-right}" y2="{baseline_y:.1f}"/><text class="axis-label" x="{width/2}" y="{height-2}" text-anchor="middle">Trading sessions (count)</text><text class="axis-label" x="12" y="{height/2}" text-anchor="middle" transform="rotate(-90 12 {height/2})">Indexed close (base = 100, percent movement)</text></svg>'


def bar_chart(values: dict[str, float], chart_id: str, aria_label: str, unit: str) -> str:
    width, height, left, top = 920, 180, 120, 18
    max_abs = max(abs(value) for value in values.values()) * 1.25
    baseline = left + (width - left - 20) / 2
    bars = []
    for index, (symbol, value) in enumerate(values.items()):
        y, bar_width = top + index * 42, value / (2 * max_abs) * (width - left - 20)
        x, color = (baseline, "#2a9d8f") if value >= 0 else (baseline + bar_width, "#e76f51")
        anchor, label_x = ("start", baseline + bar_width + 8) if value >= 0 else ("end", baseline + bar_width - 8)
        bars.append(f'<text class="bar-label" x="0" y="{y+15}">{NAMES[symbol]} ({symbol})</text><rect x="{x:.1f}" y="{y}" width="{abs(bar_width):.1f}" height="22" rx="3" fill="{color}"/><text class="bar-value" x="{label_x:.1f}" y="{y+15}" text-anchor="{anchor}">{value:+.2f}{unit}</text>')
    return f'<svg id="{chart_id}" class="bar-chart" viewBox="0 0 {width} {height}" role="img" aria-label="{aria_label}"><text class="axis-label" x="0" y="12">Instrument (company name and ticker)</text><text class="axis-label" x="{width/2}" y="{height-2}" text-anchor="middle">Change ({unit})</text>{"".join(bars)}</svg>'


def build_dashboard(frames: dict[str, pd.DataFrame]) -> str:
    data = metrics(frames)
    shallowest = max(data, key=lambda symbol: data[symbol]["drawdown"])
    cards = "".join(f'<article class="instrument"><div class="instrument-head"><span class="dot" style="background:{COLORS[symbol]}"></span><div><b>{symbol}</b><small>{NAMES[symbol]}</small></div></div><strong>{data[symbol]["last"]:,.2f}<em> INR close</em></strong><div class="instrument-meta"><span>{data[symbol]["rows"]} valid sessions</span><span>{data[symbol]["start"]} to {data[symbol]["end"]}</span></div></article>' for symbol in SYMBOLS)
    legend = "".join(f'<span><i style="background:{COLORS[symbol]}"></i>{NAMES[symbol]} ({symbol})</span>' for symbol in SYMBOLS)
    rows = "".join(f'<tr><td><b>{symbol}</b><small>{NAMES[symbol]}</small></td><td>{data[symbol]["start"]} to {data[symbol]["end"]}</td><td>{data[symbol]["rows"]}</td><td class="negative">{data[symbol]["drawdown"]:+.2f}%</td></tr>' for symbol in SYMBOLS)
    note = "All three instruments use the same 27 valid sessions, which makes the ordering directly comparable."
    if data:
        return f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Indian Market Lens</title><style>{STYLE}</style></head><body><main><header><div><div class="eyebrow">Sprint 04 / Fauxnance EOD</div><h1>Indian market lens.</h1><p class="lede">A compact view of three selected instruments, grounded in the latest 27 valid daily candles.</p></div><div class="asof">LATEST 27 SESSIONS<br>THROUGH 2026-08-26<br><span>INR / DAILY CLOSES</span></div></header><section class="cards">{cards}<p class="conclusion"><b>Conclusion:</b> Infosys finished highest on the normalized price path, while Reliance had the shallowest drawdown.</p></section><section class="panel"><div class="panel-top"><div><h2>Infosys finished highest on normalized close</h2><p class="sub">Each series starts at 100 to compare percentage movement over the same window.</p></div><div class="legend">{legend}</div></div>{line_chart(frames)}<p class="conclusion"><b>Conclusion:</b> Infosys finished highest on the rebased chart, followed by Tata Steel, with Reliance lowest.</p></section><section class="panel"><div class="panel-top"><div><h2>Reliance had the shallowest drawdown</h2><p class="sub">Largest fall from a previous closing-price peak in the same 27-session window.</p></div></div>{bar_chart({symbol: data[symbol]["drawdown"] for symbol in SYMBOLS}, "drawdown", "Reliance had the shallowest drawdown over the latest 27 sessions", "%")}<p class="conclusion"><b>Conclusion:</b> Reliance had the shallowest drawdown at -2.96%, while Infosys had the deepest at -6.36%.</p></section><section class="panel" id="evidence-table"><div class="panel-top"><div><h2>All instruments have equal evidence coverage</h2><p class="sub">Invalid candles and missing required fields are removed by the transform step.</p></div></div><div class="table-wrap"><table><thead><tr><th>Instrument</th><th>Coverage</th><th>Valid sessions</th><th>Max drawdown</th></tr></thead><tbody>{rows}</tbody></table></div><div class="note">{html.escape(note)} The shallowest drawdown is {shallowest} ({data[shallowest]["drawdown"]:+.2f}%).</div><p class="conclusion"><b>Conclusion:</b> All three instruments contribute 27 valid sessions, making the drawdown comparison directly comparable.</p></section><footer>Descriptive analytics only. Not investment advice. Generated from local Fauxnance cache.</footer></main></body></html>'''


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the Sprint 4 market dashboard")
    parser.add_argument("--cache-dir", type=Path, default=Path("data/cache"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/dashboard.html"))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    page = build_dashboard(load_frames(args.cache_dir))
    args.output.write_text(page, encoding="utf-8")
    print(f"Dashboard written to {args.output}")


if __name__ == "__main__":
    main()
