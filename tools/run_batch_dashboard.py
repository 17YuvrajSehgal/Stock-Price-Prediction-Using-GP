#!/usr/bin/env python3
"""Run multi-symbol GP experiments and build a professional HTML dashboard."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

from plotly.offline import get_plotlyjs

DEFAULT_SYMBOLS = ["META", "MSFT", "GOOG", "AAPL", "AMZN", "NVDA", "JPM"]
DEFAULT_START = "2020-01-01"

REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = REPO_ROOT / "src" / "main" / "data"
PROFILE_PATH = REPO_ROOT / "src" / "main" / "resources" / "stock-trading.params"
BUILD_DIR = REPO_ROOT / "build" / "manual-classes-dashboard"
REPORTS_ROOT = REPO_ROOT / "reports" / "trading-dashboard"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a batch of GP trading experiments and generate a dashboard.")
    parser.add_argument(
        "--symbols",
        nargs="+",
        default=DEFAULT_SYMBOLS,
        help="Ticker symbols to process. Default: %(default)s",
    )
    parser.add_argument(
        "--start",
        default=DEFAULT_START,
        help="Start date for Yahoo Finance pulls. Default: %(default)s",
    )
    parser.add_argument(
        "--profile",
        default=str(PROFILE_PATH),
        help="ECJ params file to use. Default: stock-trading.params",
    )
    parser.add_argument(
        "--run-id",
        help="Optional run identifier. Default: trading-batch-YYYYMMDD-HHMMSS",
    )
    parser.add_argument(
        "--skip-fetch",
        action="store_true",
        help="Skip data refresh and use existing CSV files.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    symbols = [symbol.upper() for symbol in args.symbols]
    run_id = args.run_id or datetime.now().strftime("trading-batch-%Y%m%d-%H%M%S")
    run_dir = REPORTS_ROOT / run_id
    symbols_dir = run_dir / "symbols"
    symbols_dir.mkdir(parents=True, exist_ok=True)

    if not args.skip_fetch:
        fetch_datasets(symbols, args.start)

    compile_java_sources()

    run_summaries = []
    for symbol in symbols:
        symbol_dir = symbols_dir / symbol
        symbol_dir.mkdir(parents=True, exist_ok=True)
        run_symbol(symbol, Path(args.profile), symbol_dir)
        run_summaries.append(load_symbol_result(symbol, symbol_dir))

    dashboard_data = build_dashboard_data(run_id, symbols, run_summaries)
    write_json(run_dir / "dashboard-data.json", dashboard_data)
    write_leaderboard_csv(run_dir / "leaderboard.csv", run_summaries)
    build_dashboard_html(run_dir / "dashboard.html", dashboard_data)
    write_manifest(run_dir, run_id, symbols, args)

    print(f"Dashboard ready: {run_dir / 'dashboard.html'}")
    return 0


def fetch_datasets(symbols: list[str], start: str) -> None:
    command = [
        "python",
        str(DATA_DIR / "fetchData.py"),
        *symbols,
        "--start",
        start,
    ]
    run_command(command)


def compile_java_sources() -> None:
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    source_files = sorted(str(path) for path in (REPO_ROOT / "src" / "main" / "java").rglob("*.java"))
    command = [
        "javac",
        "-cp",
        "ecj.23.jar",
        "-d",
        str(BUILD_DIR),
        *source_files,
    ]
    run_command(command)


def run_symbol(symbol: str, profile_path: Path, symbol_dir: Path) -> None:
    dataset_path = DATA_DIR / f"{symbol}.csv"
    stat_path = symbol_dir / f"{symbol}.stat"
    command = [
        "java",
        "-cp",
        f"{BUILD_DIR};ecj.23.jar",
        "ec.Evolve",
        "-file",
        str(profile_path),
        "-p",
        f"eval.problem.dataset={dataset_path}",
        "-p",
        f"eval.problem.export-dir={symbol_dir}",
        "-p",
        f"eval.problem.export-prefix={symbol}",
        "-p",
        f"stat.file={stat_path}",
    ]
    run_command(command)


def run_command(command: list[str]) -> None:
    completed = subprocess.run(
        command,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"Command failed: {' '.join(command)}\nSTDOUT:\n{completed.stdout}\nSTDERR:\n{completed.stderr}"
        )
    if completed.stdout:
        print(completed.stdout)


def load_symbol_result(symbol: str, symbol_dir: Path) -> dict:
    summary = read_json(symbol_dir / f"{symbol}-summary.json")
    latest_signal = read_json(symbol_dir / f"{symbol}-latest-signal.json")
    webhook = read_json(symbol_dir / f"{symbol}-tradingview-webhook.json")
    equity_curve = read_csv(symbol_dir / f"{symbol}-equity.csv")
    signals = read_csv(symbol_dir / f"{symbol}-signals.csv")

    return {
        "symbol": symbol,
        "summary": summary,
        "latest_signal": latest_signal,
        "tradingview_webhook": webhook,
        "equity_curve": equity_curve,
        "signals": signals,
    }


def build_dashboard_data(run_id: str, symbols: list[str], run_summaries: list[dict]) -> dict:
    sorted_runs = sorted(run_summaries, key=lambda item: item["summary"]["total_return_pct"], reverse=True)
    returns = [item["summary"]["total_return_pct"] for item in run_summaries]
    directional = [item["summary"]["directional_accuracy_pct"] for item in run_summaries]
    drawdowns = [item["summary"]["max_drawdown_pct"] for item in run_summaries]
    win_rates = [item["summary"]["win_rate_pct"] for item in run_summaries]

    best_run = max(run_summaries, key=lambda item: item["summary"]["total_return_pct"])
    worst_drawdown_run = max(run_summaries, key=lambda item: item["summary"]["max_drawdown_pct"])

    overview = {
        "run_id": run_id,
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "symbol_count": len(symbols),
        "average_total_return_pct": average(returns),
        "average_directional_accuracy_pct": average(directional),
        "average_win_rate_pct": average(win_rates),
        "average_max_drawdown_pct": average(drawdowns),
        "best_symbol": best_run["symbol"],
        "best_total_return_pct": best_run["summary"]["total_return_pct"],
        "worst_drawdown_symbol": worst_drawdown_run["symbol"],
        "worst_drawdown_pct": worst_drawdown_run["summary"]["max_drawdown_pct"],
        "positive_return_models": sum(1 for value in returns if value > 0.0),
    }

    return {
        "overview": overview,
        "runs": sorted_runs,
    }


def build_dashboard_html(output_path: Path, dashboard_data: dict) -> None:
    plotly_js = get_plotlyjs()
    data_json = json.dumps(dashboard_data)
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GP Trading Dashboard</title>
  <style>
    :root {{
      --bg: #f3efe7;
      --panel: #fbf8f2;
      --ink: #102129;
      --muted: #60727b;
      --line: #d8d0c2;
      --accent: #0d5c63;
      --accent-soft: #c8e1de;
      --gain: #1e7f5c;
      --loss: #b44335;
      --gold: #b08d57;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
      background:
        radial-gradient(circle at top right, rgba(176, 141, 87, 0.18), transparent 28%),
        linear-gradient(180deg, #f6f2ea 0%, var(--bg) 100%);
      color: var(--ink);
    }}
    .shell {{
      max-width: 1500px;
      margin: 0 auto;
      padding: 32px 28px 48px;
    }}
    .hero {{
      display: grid;
      grid-template-columns: 1.3fr 0.7fr;
      gap: 22px;
      margin-bottom: 22px;
    }}
    .hero-panel, .card, .chart-panel, .table-panel, .detail-panel {{
      background: rgba(251, 248, 242, 0.92);
      border: 1px solid var(--line);
      border-radius: 22px;
      box-shadow: 0 20px 45px rgba(16, 33, 41, 0.07);
      backdrop-filter: blur(10px);
    }}
    .hero-panel {{
      padding: 28px;
    }}
    .eyebrow {{
      font-size: 12px;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      color: var(--muted);
      margin-bottom: 10px;
    }}
    h1 {{
      margin: 0 0 10px;
      font-family: Georgia, "Times New Roman", serif;
      font-weight: 600;
      font-size: 40px;
      line-height: 1.05;
    }}
    .hero-copy {{
      margin: 0;
      color: var(--muted);
      line-height: 1.6;
      font-size: 15px;
      max-width: 70ch;
    }}
    .hero-meta {{
      display: grid;
      gap: 14px;
      padding: 24px;
    }}
    .hero-meta .meta-value {{
      font-family: Georgia, "Times New Roman", serif;
      font-size: 28px;
      margin-top: 4px;
    }}
    .cards {{
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr));
      gap: 16px;
      margin-bottom: 22px;
    }}
    .card {{
      padding: 18px;
      min-height: 130px;
    }}
    .card-label {{
      font-size: 11px;
      letter-spacing: 0.16em;
      text-transform: uppercase;
      color: var(--muted);
      margin-bottom: 14px;
    }}
    .card-value {{
      font-family: Georgia, "Times New Roman", serif;
      font-size: 30px;
      line-height: 1.1;
      margin-bottom: 8px;
    }}
    .card-sub {{
      color: var(--muted);
      font-size: 14px;
      line-height: 1.45;
    }}
    .grid {{
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 18px;
      margin-bottom: 22px;
    }}
    .chart-panel {{
      padding: 18px 18px 10px;
    }}
    .panel-title {{
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: 12px;
      margin-bottom: 12px;
    }}
    .panel-title h2 {{
      margin: 0;
      font-size: 18px;
      font-weight: 600;
    }}
    .panel-title span {{
      color: var(--muted);
      font-size: 13px;
    }}
    .chart {{
      height: 360px;
    }}
    .wide-chart {{
      height: 420px;
    }}
    .table-panel {{
      padding: 18px;
      margin-bottom: 22px;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
      font-size: 14px;
    }}
    th, td {{
      padding: 12px 10px;
      border-bottom: 1px solid var(--line);
      text-align: left;
    }}
    th {{
      color: var(--muted);
      font-size: 12px;
      letter-spacing: 0.1em;
      text-transform: uppercase;
      font-weight: 600;
    }}
    tr:last-child td {{
      border-bottom: none;
    }}
    .detail-panel {{
      padding: 22px;
    }}
    .detail-head {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 20px;
      margin-bottom: 16px;
      flex-wrap: wrap;
    }}
    select {{
      appearance: none;
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 12px 18px;
      background: white;
      color: var(--ink);
      font-size: 14px;
      min-width: 220px;
    }}
    .detail-grid {{
      display: grid;
      grid-template-columns: 1.35fr 0.65fr;
      gap: 18px;
      align-items: start;
    }}
    .detail-side {{
      display: grid;
      gap: 16px;
    }}
    .mini-card {{
      border: 1px solid var(--line);
      border-radius: 18px;
      padding: 16px;
      background: rgba(255, 255, 255, 0.7);
    }}
    .mini-card h3 {{
      margin: 0 0 10px;
      font-size: 13px;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      color: var(--muted);
    }}
    .metric-list {{
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px 14px;
      font-size: 14px;
    }}
    .metric-list div {{
      border-top: 1px solid var(--line);
      padding-top: 10px;
    }}
    pre {{
      margin: 0;
      padding: 14px;
      background: #f6f2ea;
      border: 1px solid var(--line);
      border-radius: 14px;
      overflow: auto;
      font-size: 12px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-word;
    }}
    .gain {{ color: var(--gain); }}
    .loss {{ color: var(--loss); }}
    .footer-note {{
      margin-top: 20px;
      color: var(--muted);
      font-size: 13px;
      line-height: 1.55;
    }}
    @media (max-width: 1100px) {{
      .hero, .grid, .detail-grid {{
        grid-template-columns: 1fr;
      }}
      .cards {{
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }}
    }}
    @media (max-width: 700px) {{
      .shell {{
        padding: 20px 16px 32px;
      }}
      .cards {{
        grid-template-columns: 1fr;
      }}
      h1 {{
        font-size: 32px;
      }}
      .chart, .wide-chart {{
        height: 300px;
      }}
    }}
  </style>
</head>
<body>
  <div class="shell">
    <section class="hero">
      <div class="hero-panel">
        <div class="eyebrow">Systematic Research Dashboard</div>
        <h1>Multi-Asset GP Trading Review</h1>
        <p class="hero-copy">
          Cross-symbol genetic programming results with walk-forward validation, one-bar trading simulation,
          transaction-cost-aware backtests, model complexity controls, and export payloads for TradingView
          webhook workflows and Alpaca paper-trading automation.
        </p>
      </div>
      <div class="hero-panel hero-meta">
        <div>
          <div class="eyebrow">Run ID</div>
          <div class="meta-value" id="run-id"></div>
        </div>
        <div>
          <div class="eyebrow">Generated</div>
          <div class="meta-value" id="generated-at" style="font-size:22px;"></div>
        </div>
      </div>
    </section>

    <section class="cards" id="summary-cards"></section>

    <section class="grid">
      <div class="chart-panel">
        <div class="panel-title">
          <h2>Return Leadership</h2>
          <span>Cost-adjusted backtest total return by symbol</span>
        </div>
        <div id="returns-chart" class="chart"></div>
      </div>
      <div class="chart-panel">
        <div class="panel-title">
          <h2>Model Quality</h2>
          <span>Directional accuracy, test accuracy, and drawdown</span>
        </div>
        <div id="accuracy-chart" class="chart"></div>
      </div>
    </section>

    <section class="grid">
      <div class="chart-panel">
        <div class="panel-title">
          <h2>Risk / Return Map</h2>
          <span>Total return vs. max drawdown, sized by trade count</span>
        </div>
        <div id="scatter-chart" class="chart"></div>
      </div>
      <div class="chart-panel">
        <div class="panel-title">
          <h2>Equity Curves</h2>
          <span>Cross-symbol equity progression during the test window</span>
        </div>
        <div id="equity-chart" class="chart"></div>
      </div>
    </section>

    <section class="table-panel">
      <div class="panel-title">
        <h2>Leaderboard</h2>
        <span>Symbols sorted by total return</span>
      </div>
      <div id="leaderboard-table"></div>
    </section>

    <section class="detail-panel">
      <div class="detail-head">
        <div>
          <div class="eyebrow">Deep Dive</div>
          <h2 style="margin:0;font-size:26px;font-family:Georgia, 'Times New Roman', serif;">Single-Symbol Detail</h2>
        </div>
        <select id="symbol-select"></select>
      </div>
      <div class="detail-grid">
        <div class="chart-panel" style="padding:16px;">
          <div class="panel-title">
            <h2 id="detail-chart-title">Symbol Equity Curve</h2>
            <span id="detail-chart-subtitle"></span>
          </div>
          <div id="detail-equity-chart" class="wide-chart"></div>
          <div style="height:18px;"></div>
          <div class="panel-title">
            <h2>Trade Return Distribution</h2>
            <span>Net per-trade returns after costs</span>
          </div>
          <div id="detail-histogram" class="chart"></div>
        </div>
        <div class="detail-side">
          <div class="mini-card">
            <h3>Snapshot</h3>
            <div id="detail-metrics" class="metric-list"></div>
          </div>
          <div class="mini-card">
            <h3>Latest Signal</h3>
            <pre id="detail-signal"></pre>
          </div>
          <div class="mini-card">
            <h3>TradingView Webhook</h3>
            <pre id="detail-webhook"></pre>
          </div>
          <div class="mini-card">
            <h3>Model Expression</h3>
            <pre id="detail-model"></pre>
          </div>
        </div>
      </div>
      <div class="footer-note">
        Backtest interpretation: this is still a research environment. The dashboard reports a one-bar strategy
        driven by model forecasts, with configurable round-trip commission and slippage assumptions. It does not
        include borrow costs, market impact, partial fills, or portfolio-level capital allocation constraints.
      </div>
    </section>
  </div>

  <script>{plotly_js}</script>
  <script id="dashboard-data" type="application/json">{data_json}</script>
  <script>
    const dashboardData = JSON.parse(document.getElementById("dashboard-data").textContent);
    const runs = dashboardData.runs;
    const overview = dashboardData.overview;
    const colors = {{
      ink: "#102129",
      accent: "#0d5c63",
      gain: "#1e7f5c",
      loss: "#b44335",
      gold: "#b08d57",
      soft: "#dfe9e6",
      paper: "#fbf8f2"
    }};

    document.getElementById("run-id").textContent = overview.run_id;
    document.getElementById("generated-at").textContent = overview.generated_at.replace("T", " ");

    function formatPct(value) {{
      return `${{value >= 0 ? "" : "-" }}${{Math.abs(value).toFixed(2)}}%`;
    }}

    function formatMoney(value) {{
      return new Intl.NumberFormat("en-US", {{ style: "currency", currency: "USD", maximumFractionDigits: 0 }}).format(value);
    }}

    function buildCards() {{
      const cards = [
        ["Coverage", String(overview.symbol_count), "Large-cap models in the batch"],
        ["Average Return", formatPct(overview.average_total_return_pct), "Backtest total return across symbols"],
        ["Average Direction", formatPct(overview.average_directional_accuracy_pct), "Mean directional accuracy"],
        ["Average Win Rate", formatPct(overview.average_win_rate_pct), "Per-trade win rate average"],
        ["Best Model", `${{overview.best_symbol}}`, `${{formatPct(overview.best_total_return_pct)}} total return`],
        ["Worst Drawdown", `${{overview.worst_drawdown_symbol}}`, `${{formatPct(overview.worst_drawdown_pct)}} drawdown`],
      ];
      const container = document.getElementById("summary-cards");
      container.innerHTML = cards.map(([label, value, sub]) => `
        <div class="card">
          <div class="card-label">${{label}}</div>
          <div class="card-value">${{value}}</div>
          <div class="card-sub">${{sub}}</div>
        </div>
      `).join("");
    }}

    function baseLayout(titleY = "") {{
      return {{
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        margin: {{ l: 52, r: 18, t: 20, b: 48 }},
        font: {{ family: "Segoe UI, Arial, sans-serif", color: colors.ink }},
        xaxis: {{
          gridcolor: "#e6dfd2",
          linecolor: "#cabfaa",
          zerolinecolor: "#cabfaa",
          title: titleY
        }},
        yaxis: {{
          gridcolor: "#e6dfd2",
          linecolor: "#cabfaa",
          zerolinecolor: "#cabfaa"
        }},
        legend: {{
          orientation: "h",
          y: -0.2,
          x: 0
        }}
      }};
    }}

    function renderOverviewCharts() {{
      const symbols = runs.map(run => run.symbol);
      const returns = runs.map(run => run.summary.total_return_pct);
      const directional = runs.map(run => run.summary.directional_accuracy_pct);
      const testing = runs.map(run => run.summary.testing_accuracy_pct);
      const drawdowns = runs.map(run => run.summary.max_drawdown_pct);
      const trades = runs.map(run => run.summary.trades);
      const winRates = runs.map(run => run.summary.win_rate_pct);

      Plotly.newPlot("returns-chart", [
        {{
          type: "bar",
          x: symbols,
          y: returns,
          marker: {{
            color: returns.map(value => value >= 0 ? colors.gain : colors.loss),
            line: {{ color: "#163039", width: 1 }}
          }},
          hovertemplate: "<b>%{{x}}</b><br>Total return: %{{y:.2f}}%<extra></extra>"
        }}
      ], {{
        ...baseLayout(),
        yaxis: {{ ...baseLayout().yaxis, title: "Total return (%)" }}
      }}, {{ displayModeBar: false, responsive: true }});

      Plotly.newPlot("accuracy-chart", [
        {{
          type: "bar",
          name: "Directional Accuracy",
          x: symbols,
          y: directional,
          marker: {{ color: colors.accent }}
        }},
        {{
          type: "bar",
          name: "Hit Accuracy",
          x: symbols,
          y: testing,
          marker: {{ color: colors.gold }}
        }},
        {{
          type: "scatter",
          mode: "lines+markers",
          name: "Max Drawdown",
          x: symbols,
          y: drawdowns,
          yaxis: "y2",
          line: {{ color: colors.loss, width: 2 }},
          marker: {{ size: 8, color: colors.loss }}
        }}
      ], {{
        ...baseLayout(),
        barmode: "group",
        yaxis: {{ ...baseLayout().yaxis, title: "Accuracy (%)" }},
        yaxis2: {{
          overlaying: "y",
          side: "right",
          title: "Drawdown (%)",
          gridcolor: "rgba(0,0,0,0)",
          color: colors.loss
        }}
      }}, {{ displayModeBar: false, responsive: true }});

      Plotly.newPlot("scatter-chart", [
        {{
          type: "scatter",
          mode: "markers+text",
          x: drawdowns,
          y: returns,
          text: symbols,
          textposition: "top center",
          marker: {{
            size: trades.map(value => Math.max(12, value * 0.9)),
            color: winRates,
            colorscale: [
              [0, "#b44335"],
              [0.5, "#d5b269"],
              [1, "#1e7f5c"]
            ],
            line: {{ color: "#163039", width: 1 }},
            colorbar: {{ title: "Win Rate %" }}
          }},
          hovertemplate: "<b>%{{text}}</b><br>Return: %{{y:.2f}}%<br>Drawdown: %{{x:.2f}}%<br>Trades: %{{marker.size:.0f}}<extra></extra>"
        }}
      ], {{
        ...baseLayout(),
        xaxis: {{ ...baseLayout().xaxis, title: "Max drawdown (%)" }},
        yaxis: {{ ...baseLayout().yaxis, title: "Total return (%)" }}
      }}, {{ displayModeBar: false, responsive: true }});

      Plotly.newPlot("equity-chart", runs.map(run => ({{
        type: "scatter",
        mode: "lines",
        name: run.symbol,
        x: run.equity_curve.map(point => point.execution_timestamp),
        y: run.equity_curve.map(point => Number(point.equity)),
        line: {{ width: 2 }}
      }})), {{
        ...baseLayout(),
        xaxis: {{ ...baseLayout().xaxis, title: "Execution date" }},
        yaxis: {{ ...baseLayout().yaxis, title: "Equity ($)" }}
      }}, {{ displayModeBar: false, responsive: true }});
    }}

    function renderLeaderboard() {{
      const rows = runs.map(run => {{
        const summary = run.summary;
        return `
          <tr>
            <td><strong>${{run.symbol}}</strong></td>
            <td class="${{summary.total_return_pct >= 0 ? "gain" : "loss"}}">${{formatPct(summary.total_return_pct)}}</td>
            <td>${{formatPct(summary.max_drawdown_pct)}}</td>
            <td>${{formatPct(summary.directional_accuracy_pct)}}</td>
            <td>${{formatPct(summary.testing_accuracy_pct)}}</td>
            <td>${{formatPct(summary.win_rate_pct)}}</td>
            <td>${{summary.trades}}</td>
            <td>${{summary.node_count}}</td>
            <td>${{run.latest_signal.signal}}</td>
          </tr>
        `;
      }}).join("");

      document.getElementById("leaderboard-table").innerHTML = `
        <table>
          <thead>
            <tr>
              <th>Symbol</th>
              <th>Total Return</th>
              <th>Max Drawdown</th>
              <th>Directional Accuracy</th>
              <th>Hit Accuracy</th>
              <th>Win Rate</th>
              <th>Trades</th>
              <th>Node Count</th>
              <th>Latest Signal</th>
            </tr>
          </thead>
          <tbody>${{rows}}</tbody>
        </table>
      `;
    }}

    function renderSymbolSelector() {{
      const select = document.getElementById("symbol-select");
      select.innerHTML = runs.map(run => `<option value="${{run.symbol}}">${{run.symbol}}</option>`).join("");
      select.value = runs[0].symbol;
      select.addEventListener("change", () => renderSymbolDetail(select.value));
      renderSymbolDetail(select.value);
    }}

    function renderSymbolDetail(symbol) {{
      const run = runs.find(item => item.symbol === symbol);
      if (!run) return;

      document.getElementById("detail-chart-title").textContent = `${{symbol}} Equity Curve`;
      document.getElementById("detail-chart-subtitle").textContent = `${{run.summary.trades}} trades | node count ${{run.summary.node_count}}`;

      Plotly.newPlot("detail-equity-chart", [
        {{
          type: "scatter",
          mode: "lines",
          x: run.equity_curve.map(point => point.execution_timestamp),
          y: run.equity_curve.map(point => Number(point.equity)),
          line: {{ width: 3, color: colors.accent }},
          fill: "tozeroy",
          fillcolor: "rgba(13, 92, 99, 0.10)"
        }}
      ], {{
        ...baseLayout(),
        xaxis: {{ ...baseLayout().xaxis, title: "Execution date" }},
        yaxis: {{ ...baseLayout().yaxis, title: "Equity ($)" }}
      }}, {{ displayModeBar: false, responsive: true }});

      const tradeReturns = run.signals
        .filter(point => point.signal !== "FLAT")
        .map(point => Number(point.net_strategy_return) * 100.0);

      Plotly.newPlot("detail-histogram", [
        {{
          type: "histogram",
          x: tradeReturns,
          marker: {{ color: colors.gold, line: {{ color: "#163039", width: 1 }} }},
          nbinsx: 18
        }}
      ], {{
        ...baseLayout(),
        xaxis: {{ ...baseLayout().xaxis, title: "Net trade return (%)" }},
        yaxis: {{ ...baseLayout().yaxis, title: "Frequency" }}
      }}, {{ displayModeBar: false, responsive: true }});

      document.getElementById("detail-metrics").innerHTML = `
        <div><strong>Total Return</strong><br>${{formatPct(run.summary.total_return_pct)}}</div>
        <div><strong>Final Equity</strong><br>${{formatMoney(run.summary.final_equity)}}</div>
        <div><strong>Max Drawdown</strong><br>${{formatPct(run.summary.max_drawdown_pct)}}</div>
        <div><strong>Directional Accuracy</strong><br>${{formatPct(run.summary.directional_accuracy_pct)}}</div>
        <div><strong>Hit Accuracy</strong><br>${{formatPct(run.summary.testing_accuracy_pct)}}</div>
        <div><strong>Win Rate</strong><br>${{formatPct(run.summary.win_rate_pct)}}</div>
        <div><strong>Trades</strong><br>${{run.summary.trades}}</div>
        <div><strong>Profit Factor</strong><br>${{Number(run.summary.profit_factor).toFixed(2)}}</div>
        <div><strong>Long / Short</strong><br>${{run.summary.long_trades}} / ${{run.summary.short_trades}}</div>
        <div><strong>Avg Net Trade</strong><br>${{formatPct(run.summary.average_net_trade_return * 100.0)}}</div>
      `;
      document.getElementById("detail-signal").textContent = JSON.stringify(run.latest_signal, null, 2);
      document.getElementById("detail-webhook").textContent = JSON.stringify(run.tradingview_webhook, null, 2);
      document.getElementById("detail-model").textContent = run.summary.model_expression;
    }}

    buildCards();
    renderOverviewCharts();
    renderLeaderboard();
    renderSymbolSelector();
  </script>
</body>
</html>
"""
    output_path.write_text(html, encoding="utf-8")


def write_manifest(run_dir: Path, run_id: str, symbols: list[str], args: argparse.Namespace) -> None:
    manifest = {
        "run_id": run_id,
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "symbols": symbols,
        "profile": args.profile,
        "start": args.start,
        "dashboard": str(run_dir / "dashboard.html"),
    }
    write_json(run_dir / "manifest.json", manifest)


def write_leaderboard_csv(path: Path, run_summaries: list[dict]) -> None:
    fieldnames = [
        "symbol",
        "total_return_pct",
        "max_drawdown_pct",
        "directional_accuracy_pct",
        "testing_accuracy_pct",
        "win_rate_pct",
        "trades",
        "node_count",
        "latest_signal",
    ]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for item in sorted(run_summaries, key=lambda entry: entry["summary"]["total_return_pct"], reverse=True):
            writer.writerow({
                "symbol": item["symbol"],
                "total_return_pct": item["summary"]["total_return_pct"],
                "max_drawdown_pct": item["summary"]["max_drawdown_pct"],
                "directional_accuracy_pct": item["summary"]["directional_accuracy_pct"],
                "testing_accuracy_pct": item["summary"]["testing_accuracy_pct"],
                "win_rate_pct": item["summary"]["win_rate_pct"],
                "trades": item["summary"]["trades"],
                "node_count": item["summary"]["node_count"],
                "latest_signal": item["latest_signal"]["signal"],
            })


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def read_csv(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def average(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


if __name__ == "__main__":
    raise SystemExit(main())
