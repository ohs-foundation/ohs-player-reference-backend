"""Writes CSVs in the exact unquoted comma-joined shape the server's naive parser expects.

Deliberately does not use the stdlib `csv` module: `csv.writer` would quote
fields containing commas (e.g. `"foo,bar"`), which CsvProcessor.java has no
logic to unquote -- the literal quote characters would end up in the FHIR
resource. Every row is sanitized first so no field ever contains a comma.
"""

from collections.abc import Iterable
from pathlib import Path

from perfharness.csv_sanitizer import sanitize_row


def write_csv(path: Path, fieldnames: list[str], rows: Iterable[dict]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(",".join(fieldnames) + "\n")
        for row in rows:
            clean = sanitize_row(row)
            f.write(",".join(clean.get(name, "") for name in fieldnames) + "\n")
            count += 1
    return count
