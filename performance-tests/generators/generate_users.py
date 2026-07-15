"""Generates a CSV for POST /api/bulk-import/users and appends the generated
practitioners' source_ids to the shared manifest so downstream generators
(user-assignments) can reference them.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from perfharness import faker_kenya
from perfharness.cli import apply_seed, build_generator_parser
from perfharness.csv_writer import write_csv
from perfharness.id_pool import IdPool

FIELDNAMES = [
    "id",
    "username",
    "first_name",
    "last_name",
    "email",
    "group",
    "password",
    "is_password_temp",
    "dob",
    "gender",
    "national_id",
    "phone",
    "source_id",
]

DEFAULT_OUTPUT = Path("data/users.csv")


def generate_rows(count: int, start_index: int) -> list[dict]:
    rows = []
    for i in range(start_index, start_index + count):
        first_name, last_name = faker_kenya.practitioner_full_name()
        username = faker_kenya.kenyan_username(first_name, i)
        rows.append(
            {
                "id": "",
                "username": username,
                "first_name": first_name,
                "last_name": last_name,
                "email": faker_kenya.kenyan_email(username),
                "group": "Practitioner",
                "password": "mypassword",
                "is_password_temp": "false",
                "dob": faker_kenya.kenyan_dob(),
                "gender": faker_kenya.gender(),
                "national_id": faker_kenya.kenyan_national_id(),
                "phone": faker_kenya.kenyan_mobile_phone(),
                "source_id": f"PRAC-{i:06d}",
            }
        )
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = build_generator_parser(
        "Generate a CSV for POST /api/bulk-import/users", DEFAULT_OUTPUT
    )
    args = parser.parse_args(argv)
    apply_seed(args.seed)

    pool = IdPool.load(args.manifest)
    if args.fresh:
        pool.reset("practitioners")

    start_index = len(pool.practitioners) + 1
    rows = generate_rows(args.count, start_index)
    write_csv(args.output, FIELDNAMES, rows)

    pool.extend(
        "practitioners",
        [{"source_id": row["source_id"], "username": row["username"]} for row in rows],
    )
    pool.save(args.manifest)

    print(
        f"Wrote {len(rows)} users to {args.output}; "
        f"manifest now has {len(pool.practitioners)} practitioners ({args.manifest})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
