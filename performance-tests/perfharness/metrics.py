"""Run-result reporting: a detailed per-run JSON plus a cumulative summary.csv
row, so 5,000 vs 10,000-row runs can be compared at a glance.
"""

import csv
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

from perfharness.http_client import ImportOutcome

SUMMARY_FIELDNAMES = [
    "timestamp",
    "entity",
    "csv_path",
    "row_count",
    "wall_seconds",
    "ttfb_seconds",
    "processed",
    "failed",
    "total",
    "rows_per_second",
    "stopped_early",
    "error_count",
]


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass
class ImportResult:
    entity: str
    csv_path: str
    row_count: int
    started_at: str
    outcome: ImportOutcome

    @property
    def rows_per_second(self) -> float:
        if self.outcome.wall_seconds <= 0:
            return 0.0
        return self.outcome.processed / self.outcome.wall_seconds

    def summary_row(self) -> dict:
        ttfb = self.outcome.time_to_first_byte_seconds
        return {
            "timestamp": self.started_at,
            "entity": self.entity,
            "csv_path": self.csv_path,
            "row_count": self.row_count,
            "wall_seconds": round(self.outcome.wall_seconds, 3),
            "ttfb_seconds": round(ttfb, 3) if ttfb is not None else "",
            "processed": self.outcome.processed,
            "failed": self.outcome.failed,
            "total": self.outcome.total if self.outcome.total is not None else "",
            "rows_per_second": round(self.rows_per_second, 2),
            "stopped_early": self.outcome.stopped_early,
            "error_count": len(self.outcome.errors),
        }

    def detail_dict(self) -> dict:
        return {
            "entity": self.entity,
            "csv_path": self.csv_path,
            "row_count": self.row_count,
            "started_at": self.started_at,
            "rows_per_second": round(self.rows_per_second, 2),
            "outcome": asdict(self.outcome),
        }


def write_report(result: ImportResult, report_dir: Path) -> tuple[Path, Path]:
    report_dir.mkdir(parents=True, exist_ok=True)
    ts_slug = result.started_at.replace(":", "").replace("-", "").replace("+", "_")
    detail_path = report_dir / f"{result.entity}_{result.row_count}_{ts_slug}.json"
    with open(detail_path, "w", encoding="utf-8") as f:
        json.dump(result.detail_dict(), f, indent=2)

    summary_path = report_dir / "summary.csv"
    file_exists = summary_path.exists()
    with open(summary_path, "a", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=SUMMARY_FIELDNAMES)
        if not file_exists:
            writer.writeheader()
        writer.writerow(result.summary_row())

    return detail_path, summary_path
