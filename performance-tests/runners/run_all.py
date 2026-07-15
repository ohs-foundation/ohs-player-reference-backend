"""Generates and imports all 4 bulk-import entities in README order (Users ->
Organizations -> Locations -> User Assignments), in-process, sharing one
manifest so relationships are consistent end to end. Writes a combined
end-of-suite report on top of the per-step reports each import produces.
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generators import (
    generate_locations,
    generate_organizations,
    generate_user_assignments,
    generate_users,
)
from perfharness.cli import DEFAULT_MANIFEST, DEFAULT_REPORT_DIR, apply_seed, require_token
from perfharness.config import get_base_url, get_bearer_token
from perfharness.metrics import now_iso
from perfharness.runner import print_result, run_import

STEPS = [
    ("users", "/api/bulk-import/users", False, generate_users),
    ("organizations", "/api/bulk-import/organizations", True, generate_organizations),
    ("locations", "/api/bulk-import/locations", True, generate_locations),
    ("user-assignments", "/api/bulk-import/user-assignments", True, generate_user_assignments),
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate and import all 4 bulk-import entities in README order"
    )
    parser.add_argument(
        "--count", type=int, required=True, help="Row count applied to all 4 entities"
    )
    parser.add_argument("--users-count", type=int, default=None)
    parser.add_argument("--organizations-count", type=int, default=None)
    parser.add_argument("--locations-count", type=int, default=None)
    parser.add_argument("--user-assignments-count", type=int, default=None)
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=None,
        help="Directory for generated CSVs (default: data/run_<timestamp>, so CSVs from "
        "different invocations never overwrite each other)",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help=f"Shared source_id manifest path (default: {DEFAULT_MANIFEST}, persisted across "
        "invocations -- NOT inside --data-dir -- so source_ids keep incrementing and never "
        "collide with data already created on the target server by a previous run)",
    )
    parser.add_argument("--base-url", default=get_base_url())
    parser.add_argument("--token", default=get_bearer_token())
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--timeout", type=float, default=600.0)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument(
        "--fresh", action="store_true", help="Reset the manifest before generating"
    )
    parser.add_argument(
        "--fail-fast",
        action="store_true",
        help="Abort the suite on the first step with any failed rows",
    )
    return parser


def counts_by_entity(args: argparse.Namespace) -> dict[str, int]:
    return {
        "users": args.users_count or args.count,
        "organizations": args.organizations_count or args.count,
        "locations": args.locations_count or args.count,
        "user-assignments": args.user_assignments_count or args.count,
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    token = require_token(args.token)
    apply_seed(args.seed)

    run_started_at = now_iso()
    data_dir = args.data_dir or Path("data") / f"run_{run_started_at.replace(':', '').replace('+', '_')}"
    manifest_path = args.manifest
    counts = counts_by_entity(args)

    summaries = []
    for entity, endpoint_path, expects_terminal_done, generator_module in STEPS:
        count = counts[entity]
        csv_path = data_dir / f"{entity.replace('-', '_')}.csv"

        print(f"\n=== Generating {entity} ({count} rows) -> {csv_path} ===")
        gen_argv = [
            "--count",
            str(count),
            "--output",
            str(csv_path),
            "--manifest",
            str(manifest_path),
        ]
        if args.fresh:
            gen_argv.append("--fresh")
        generator_module.main(gen_argv)

        print(f"=== Importing {entity} from {csv_path} ===")
        result, detail_path, summary_path = run_import(
            entity,
            endpoint_path,
            expects_terminal_done,
            csv_path,
            args.base_url,
            token,
            args.timeout,
            args.report_dir,
        )
        print_result(result, detail_path, summary_path)
        summaries.append(result.summary_row())

        if args.fail_fast and result.outcome.failed:
            print(f"\n--fail-fast: aborting suite after {entity} reported failures")
            break

    combined_path = args.report_dir / f"run_all_{run_started_at.replace(':', '').replace('+', '_')}.json"
    args.report_dir.mkdir(parents=True, exist_ok=True)
    with open(combined_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "started_at": run_started_at,
                "data_dir": str(data_dir),
                "manifest": str(manifest_path),
                "steps": summaries,
            },
            f,
            indent=2,
        )

    total_wall = sum(row["wall_seconds"] for row in summaries)
    print(f"\n=== Suite complete: {len(summaries)}/{len(STEPS)} steps run, total wall={total_wall:.2f}s ===")
    print(f"Combined report: {combined_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
