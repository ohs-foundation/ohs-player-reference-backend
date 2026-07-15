"""Posts a CSV to POST /api/bulk-import/user-assignments and reports timing/throughput.

Same batching/continue-on-error/terminal-`done` semantics as organizations
and locations. Note (per README): this endpoint is create-only -- re-running
the same CSV creates duplicate PractitionerRole resources.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from perfharness.cli import build_runner_parser, require_token
from perfharness.runner import print_result, run_import

ENTITY = "user-assignments"
ENDPOINT_PATH = "/api/bulk-import/user-assignments"
EXPECTS_TERMINAL_DONE = True


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
