# python src/main/data/fetchData.py MSFT
# python src/main/data/fetchData.py MSFT AAPL NVDA --start 2020-01-01
# python src/main/data/fetchData.py MSFT --interval 1h --period 60d
# python src/main/data/fetchData.py MSFT --replace --start 2015-01-01 --end 2026-04-13

#!/usr/bin/env python3
"""Download and refresh Yahoo Finance CSV files for the GP stock models."""

from __future__ import annotations

import argparse
from datetime import date, timedelta
from pathlib import Path
from typing import Iterable

import pandas as pd
import yfinance as yf

DATE_COLUMN = "Date"
PRICE_COLUMNS = ["Open", "High", "Low", "Close", "Adj Close", "Volume"]
OUTPUT_COLUMNS = [DATE_COLUMN, *PRICE_COLUMNS]
DEFAULT_INTERVAL = "1d"
DEFAULT_INITIAL_PERIOD = "max"
DEFAULT_OVERLAP_DAYS = 7


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch Yahoo Finance history and save it in the CSV format used by this project."
    )
    parser.add_argument(
        "tickers",
        nargs="+",
        help="Ticker symbols to download, for example MSFT AAPL NVDA.",
    )
    parser.add_argument(
        "--interval",
        default=DEFAULT_INTERVAL,
        help="Yahoo Finance interval such as 1d, 1h, 30m, or 5m. Default: 1d.",
    )
    parser.add_argument(
        "--start",
        help="Inclusive start date in YYYY-MM-DD format. Overrides incremental refresh mode.",
    )
    parser.add_argument(
        "--end",
        default=date.today().isoformat(),
        help="Exclusive end date in YYYY-MM-DD format. Default: today.",
    )
    parser.add_argument(
        "--period",
        help="Yahoo Finance period such as 1mo, 6mo, 1y, 5y, or max. Ignored when --start is provided.",
    )
    parser.add_argument(
        "--outdir",
        default=str(Path(__file__).resolve().parent),
        help="Directory where CSV files will be saved. Default: this script's directory.",
    )
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Replace the output file instead of merging with existing rows.",
    )
    parser.add_argument(
        "--overlap-days",
        type=int,
        default=DEFAULT_OVERLAP_DAYS,
        help="When refreshing an existing file, re-download this many days before the latest saved row. Default: 7.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    outdir = Path(args.outdir).resolve()
    outdir.mkdir(parents=True, exist_ok=True)

    failures = []
    for raw_ticker in args.tickers:
        ticker = raw_ticker.upper()
        output_path = build_output_path(outdir, ticker, args.interval)

        try:
            downloaded = download_history(
                ticker=ticker,
                interval=args.interval,
                start=args.start,
                end=args.end,
                period=args.period,
                output_path=output_path,
                replace=args.replace,
                overlap_days=args.overlap_days,
            )
            print(f"Saved {len(downloaded)} rows to {output_path}")
        except Exception as exc:  # pragma: no cover - CLI error path
            failures.append((ticker, str(exc)))

    if failures:
        for ticker, message in failures:
            print(f"Failed to update {ticker}: {message}")
        return 1
    return 0


def build_output_path(outdir: Path, ticker: str, interval: str) -> Path:
    if interval == DEFAULT_INTERVAL:
        filename = f"{ticker}.csv"
    else:
        normalized_interval = interval.replace("/", "_")
        filename = f"{ticker}_{normalized_interval}.csv"
    return outdir / filename


def download_history(
    *,
    ticker: str,
    interval: str,
    start: str | None,
    end: str,
    period: str | None,
    output_path: Path,
    replace: bool,
    overlap_days: int,
) -> pd.DataFrame:
    resolved_start = start
    resolved_period = period

    if resolved_start is None and resolved_period is None and output_path.exists() and not replace:
        resolved_start = infer_refresh_start(output_path, overlap_days).isoformat()
    elif resolved_start is None and resolved_period is None:
        resolved_period = DEFAULT_INITIAL_PERIOD

    history = yf.download(
        tickers=ticker,
        start=resolved_start,
        end=end,
        period=None if resolved_start is not None else resolved_period,
        interval=interval,
        auto_adjust=False,
        actions=False,
        progress=False,
        threads=False,
    )

    normalized = normalize_history(history)
    if normalized.empty:
        raise ValueError("Yahoo Finance returned no rows for the requested range.")

    if output_path.exists() and not replace:
        existing = pd.read_csv(output_path)
        normalized = merge_rows(existing, normalized)

    normalized.to_csv(output_path, index=False)
    return normalized


def infer_refresh_start(output_path: Path, overlap_days: int) -> date:
    existing = pd.read_csv(output_path)
    if existing.empty:
        return date.today() - timedelta(days=overlap_days)

    latest_timestamp = pd.to_datetime(existing.iloc[:, 0], errors="coerce").max()
    if pd.isna(latest_timestamp):
        raise ValueError(f"Could not parse the existing date column in {output_path}")

    latest_date = latest_timestamp.date()
    return latest_date - timedelta(days=max(overlap_days, 0))


def normalize_history(history: pd.DataFrame) -> pd.DataFrame:
    if history.empty:
        return pd.DataFrame(columns=OUTPUT_COLUMNS)

    if isinstance(history.columns, pd.MultiIndex):
        history.columns = flatten_columns(history.columns)

    normalized = history.reset_index()
    first_column = normalized.columns[0]
    normalized = normalized.rename(columns={first_column: DATE_COLUMN})

    missing_columns = [column for column in PRICE_COLUMNS if column not in normalized.columns]
    if missing_columns:
        raise ValueError(f"Missing expected columns from Yahoo Finance response: {missing_columns}")

    normalized = normalized[OUTPUT_COLUMNS].copy()
    normalized[DATE_COLUMN] = pd.to_datetime(normalized[DATE_COLUMN], errors="coerce")
    normalized = normalized.dropna(subset=[DATE_COLUMN])
    normalized = normalized.sort_values(DATE_COLUMN).drop_duplicates(subset=[DATE_COLUMN], keep="last")
    normalized[DATE_COLUMN] = normalized[DATE_COLUMN].map(format_timestamp)
    return normalized.reset_index(drop=True)


def flatten_columns(columns: Iterable[tuple[str, ...]]) -> list[str]:
    flattened = []
    for column in columns:
        non_empty_parts = [part for part in column if part]
        flattened.append(non_empty_parts[0] if non_empty_parts else "")
    return flattened


def merge_rows(existing: pd.DataFrame, downloaded: pd.DataFrame) -> pd.DataFrame:
    merged = pd.concat([existing, downloaded], ignore_index=True)
    merged[DATE_COLUMN] = pd.to_datetime(merged.iloc[:, 0], errors="coerce")
    merged = merged.dropna(subset=[DATE_COLUMN])
    merged = merged.sort_values(DATE_COLUMN).drop_duplicates(subset=[DATE_COLUMN], keep="last")
    merged[DATE_COLUMN] = merged[DATE_COLUMN].map(format_timestamp)
    return merged[OUTPUT_COLUMNS].reset_index(drop=True)


def format_timestamp(value: pd.Timestamp) -> str:
    if value.hour == 0 and value.minute == 0 and value.second == 0 and value.nanosecond == 0:
        return value.strftime("%Y-%m-%d")
    return value.strftime("%Y-%m-%d %H:%M:%S")


if __name__ == "__main__":
    raise SystemExit(main())
