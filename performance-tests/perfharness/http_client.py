"""Multipart CSV upload + SSE progress stream consumption, with timing.

Wire format (SseResponseHelper.java): `data: {...}\\n\\n`, one JSON object per
line, no `event:`/`id:` fields -- a plain line-based parser is sufficient.

Two server protocols exist:
- Users (`expects_terminal_done=False`): no terminal `done` event; the stream
  closes on the first row error and remaining rows are never processed.
- Organizations/Locations/User-assignments (`expects_terminal_done=True`):
  processing continues past row errors and a final `done` event carries the
  true processed/failed/total tallies.
"""

import json
import time
from dataclasses import dataclass, field
from pathlib import Path

import requests

SSE_DATA_PREFIX = "data: "


@dataclass
class ImportOutcome:
    entity: str
    endpoint_path: str
    csv_path: str
    wall_seconds: float
    time_to_first_byte_seconds: float | None
    processed: int
    failed: int
    total: int | None
    event_count: int
    errors: list[dict] = field(default_factory=list)
    stopped_early: bool = False
    http_status: int = 0


class SseImportClient:
    def __init__(self, base_url: str, bearer_token: str | None, timeout: float = 600.0):
        self.base_url = base_url.rstrip("/")
        self.bearer_token = bearer_token
        self.timeout = timeout

    def post_csv_and_stream(
        self,
        entity: str,
        endpoint_path: str,
        csv_path: Path,
        expects_terminal_done: bool,
        max_errors_kept: int = 50,
    ) -> ImportOutcome:
        url = f"{self.base_url}{endpoint_path}"
        headers = {}
        if self.bearer_token:
            headers["Authorization"] = f"Bearer {self.bearer_token}"

        processed = 0
        failed = 0
        total: int | None = None
        event_count = 0
        errors: list[dict] = []
        stopped_early = False
        first_byte_at: float | None = None
        http_status = 0

        start = time.perf_counter()
        with open(csv_path, "rb") as fh:
            files = {"file": (csv_path.name, fh, "text/csv")}
            with requests.post(
                url, files=files, headers=headers, stream=True, timeout=self.timeout
            ) as response:
                response.raise_for_status()
                http_status = response.status_code
                for line in response.iter_lines(decode_unicode=True):
                    if first_byte_at is None:
                        first_byte_at = time.perf_counter()
                    if not line or not line.startswith(SSE_DATA_PREFIX):
                        continue
                    event_count += 1
                    payload = json.loads(line[len(SSE_DATA_PREFIX) :])
                    if "done" in payload:
                        processed = payload.get("processed", processed)
                        failed = payload.get("failed", failed)
                        total = payload.get("total", total)
                        break
                    if "error" in payload:
                        failed += 1
                        if len(errors) < max_errors_kept:
                            errors.append(
                                {"row": payload.get("row"), "error": payload.get("error")}
                            )
                        if not expects_terminal_done:
                            stopped_early = True
                            break
                        continue
                    processed = payload.get("processed", processed)
                    total = payload.get("total", total)
        end = time.perf_counter()

        return ImportOutcome(
            entity=entity,
            endpoint_path=endpoint_path,
            csv_path=str(csv_path),
            wall_seconds=end - start,
            time_to_first_byte_seconds=(first_byte_at - start) if first_byte_at else None,
            processed=processed,
            failed=failed,
            total=total,
            event_count=event_count,
            errors=errors,
            stopped_early=stopped_early,
            http_status=http_status,
        )
