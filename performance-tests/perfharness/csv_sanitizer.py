"""Field sanitization for the server's naive `line.split(",", -1)` CSV parser.

CsvProcessor.buildHeaderIndex/getColumn (src/main/java/dev/ohs/player/bulk/CsvProcessor.java)
does not understand quoting or escaping. A literal comma or newline inside a
generated field silently shifts every column after it. Every value written to
a CSV by this harness must pass through sanitize_field/sanitize_row first.
"""

from typing import Any


def sanitize_field(value: Any) -> str:
    if value is None:
        return ""
    text = str(value)
    text = text.replace(",", "")
    text = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    return text.strip()


def sanitize_row(row: dict[str, Any]) -> dict[str, str]:
    return {key: sanitize_field(value) for key, value in row.items()}
