"""Generates a CSV for POST /api/bulk-import/user-assignments by sampling
practitioner/organization/location source_ids already present in the
manifest (produced by generate_users.py, generate_organizations.py, and
generate_locations.py). Requires practitioners to exist first.
"""

import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from perfharness.cli import apply_seed, build_generator_parser
from perfharness.csv_writer import write_csv
from perfharness.id_pool import IdPool

FIELDNAMES = [
    "practitioner_id",
    "practitioner_source_id",
    "org_id",
    "org_source_id",
    "location_id",
    "location_source_id",
]

DEFAULT_OUTPUT = Path("data/user_assignments.csv")

ORG_BLANK_PROBABILITY = 0.05


def build_locations_by_org(locations: list[dict]) -> dict[str, list[str]]:
    by_org: dict[str, list[str]] = {}
    for loc in locations:
        org_source_id = loc.get("org_source_id")
        if org_source_id:
            by_org.setdefault(org_source_id, []).append(loc["source_id"])
    return by_org


def pick_locations(
    org_source_id: str,
    locations_by_org: dict[str, list[str]],
    all_location_ids: list[str],
    min_n: int,
    max_n: int,
) -> list[str]:
    if not all_location_ids:
        return []
    n = min(random.randint(min_n, max_n), len(all_location_ids))
    candidates = locations_by_org.get(org_source_id) if org_source_id else None
    pool = candidates if candidates and len(candidates) >= n else all_location_ids
    return random.sample(pool, k=min(n, len(pool)))


def generate_rows(
    count: int,
    practitioners: list[dict],
    organizations: list[dict],
    locations: list[dict],
    allow_duplicate_practitioners: bool,
    min_locations: int,
    max_locations: int,
) -> list[dict]:
    org_ids = [o["source_id"] for o in organizations]
    all_location_ids = [loc["source_id"] for loc in locations]
    locations_by_org = build_locations_by_org(locations)

    shuffled = [p["source_id"] for p in practitioners]
    random.shuffle(shuffled)

    rows = []
    cursor = 0
    for _ in range(count):
        if cursor >= len(shuffled):
            if not allow_duplicate_practitioners:
                break
            random.shuffle(shuffled)
            cursor = 0
        practitioner_source_id = shuffled[cursor]
        cursor += 1

        org_source_id = ""
        if org_ids and random.random() > ORG_BLANK_PROBABILITY:
            org_source_id = random.choice(org_ids)

        location_ids = pick_locations(
            org_source_id, locations_by_org, all_location_ids, min_locations, max_locations
        )

        rows.append(
            {
                "practitioner_id": "",
                "practitioner_source_id": practitioner_source_id,
                "org_id": "",
                "org_source_id": org_source_id,
                "location_id": "",
                "location_source_id": ";".join(location_ids),
            }
        )
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = build_generator_parser(
        "Generate a CSV for POST /api/bulk-import/user-assignments", DEFAULT_OUTPUT
    )
    parser.add_argument(
        "--locations-per-assignment-min",
        type=int,
        default=1,
        help="Minimum locations per assignment row (default: 1)",
    )
    parser.add_argument(
        "--locations-per-assignment-max",
        type=int,
        default=3,
        help="Maximum locations per assignment row (default: 3)",
    )
    parser.add_argument(
        "--allow-duplicate-practitioners",
        dest="allow_duplicate_practitioners",
        action="store_true",
        default=True,
        help="Allow a practitioner to appear in more than one row once the pool is exhausted (default)",
    )
    parser.add_argument(
        "--no-allow-duplicate-practitioners",
        dest="allow_duplicate_practitioners",
        action="store_false",
        help="Stop once every practitioner has been used once, instead of cycling",
    )
    args = parser.parse_args(argv)
    apply_seed(args.seed)

    pool = IdPool.load(args.manifest)
    if not pool.practitioners:
        raise SystemExit(
            "No practitioners found in the manifest. Run generate_users.py first, "
            f"or pass --manifest pointing at one that already has practitioners ({args.manifest})."
        )
    if not args.allow_duplicate_practitioners and args.count > len(pool.practitioners):
        raise SystemExit(
            f"Requested {args.count} assignments but only {len(pool.practitioners)} "
            "practitioners exist and --no-allow-duplicate-practitioners was set."
        )

    rows = generate_rows(
        args.count,
        pool.practitioners,
        pool.organizations,
        pool.locations,
        args.allow_duplicate_practitioners,
        args.locations_per_assignment_min,
        args.locations_per_assignment_max,
    )
    write_csv(args.output, FIELDNAMES, rows)

    print(f"Wrote {len(rows)} user assignments to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
