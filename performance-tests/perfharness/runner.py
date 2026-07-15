"""Shared body for the 4 import_*.py runner scripts: post a CSV, write the
report, print a summary. Factored out so run_all.py can call it directly and
collect ImportResults for a combined end-of-suite report, instead of only
seeing each script's process exit code.
"""

from pathlib import Path

from perfharness.http_client import SseImportClient
from perfharness.metrics import ImportResult, now_iso, write_report


def count_data_rows(csv_path: Path) -> int:
    with open(csv_path, encoding="utf-8") as f:
        return max(sum(1 for _ in f) - 1, 0)


def run_import(
    entity: str,
    endpoint_path: str,
    expects_terminal_done: bool,
    csv_path: Path,
    base_url: str,
    token: str,
    timeout: float,
    report_dir: Path,
) -> tuple[ImportResult, Path, Path]:
    started_at = now_iso()
    client = SseImportClient(base_url, token, timeout=timeout)
    outcome = client.post_csv_and_stream(
        entity, endpoint_path, csv_path, expects_terminal_done=expects_terminal_done
    )
    result = ImportResult(
        entity=entity,
        csv_path=str(csv_path),
        row_count=count_data_rows(csv_path),
        started_at=started_at,
        outcome=outcome,
    )
    detail_path, summary_path = write_report(result, report_dir)
    return result, detail_path, summary_path


def print_result(result: ImportResult, detail_path: Path, summary_path: Path) -> None:
    outcome = result.outcome
    print(
        f"{result.entity}: processed={outcome.processed} failed={outcome.failed} "
        f"total={outcome.total} wall={outcome.wall_seconds:.2f}s "
        f"rows/sec={result.rows_per_second:.1f} stopped_early={outcome.stopped_early}"
    )
    print(f"Report: {detail_path}")
    print(f"Summary appended to: {summary_path}")
