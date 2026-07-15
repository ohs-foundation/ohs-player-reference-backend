"""Posts a CSV to POST /api/bulk-import/users and reports timing/throughput.

The Users endpoint has no terminal `done` event and stops the stream on the
first row error (README: "the stream closes -- subsequent rows are not
processed").
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from perfharness.cli import build_runner_parser, require_token
from perfharness.runner import print_result, run_import

ENTITY = "users"
ENDPOINT_PATH = "/api/bulk-import/users"
EXPECTS_TERMINAL_DONE = False


def main(argv: list[str] | None = None) -> int:
    parser = build_runner_parser(f"Import a CSV into POST {ENDPOINT_PATH} and report timing")
    args = parser.parse_args(argv)
    token = require_token(args.token)

    result, detail_path, summary_path = run_import(
        ENTITY,
        ENDPOINT_PATH,
        EXPECTS_TERMINAL_DONE,
        args.csv,
        args.base_url,
        token,
        args.timeout,
        args.report_dir,
    )
    print_result(result, detail_path, summary_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
