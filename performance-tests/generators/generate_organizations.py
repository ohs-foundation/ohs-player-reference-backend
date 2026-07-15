"""Generates a CSV for POST /api/bulk-import/organizations: a 5-level
hierarchy (national body -> county health services -> hospital -> department
-> team), styled after gen/test-orgs.csv. Nodes are emitted strictly
level-by-level, so parents always appear before their children in the CSV
(the README's ordering note) -- no separate sort step needed.

Appends every generated org's {source_id, name, parent_source_id, level} to
the manifest so generate_locations.py can anchor location trees to hospitals
(level 2).
"""

import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from perfharness import faker_kenya
from perfharness.cli import apply_seed, build_generator_parser
from perfharness.csv_writer import write_csv
from perfharness.id_pool import IdPool

FIELDNAMES = [
    "id",
    "name",
    "is_team",
    "source_id",
    "parent_id",
    "parent_name",
    "source_parent_id",
    "phone",
    "email",
    "physical_address",
    "postal_address",
]

DEFAULT_OUTPUT = Path("data/organizations.csv")

LEVEL_ROOT = 0
LEVEL_COUNTY = 1
LEVEL_HOSPITAL = 2
LEVEL_DEPARTMENT = 3
LEVEL_TEAM = 4


def allocate_levels(count: int) -> tuple[int, int, int, int, int]:
    if count <= 0:
        return (0, 0, 0, 0, 0)
    remaining = count
    root = 1
    remaining -= root
    county = min(max(1, round(count * 0.02)), len(faker_kenya.KENYAN_COUNTIES), remaining)
    remaining -= county
    hospital = min(round(count * 0.15), remaining)
    remaining -= hospital
    department = min(round(count * 0.35), remaining)
    remaining -= department
    team = remaining
    return root, county, hospital, department, team


def generate_nodes(count: int, start_index: int) -> list[dict]:
    root_n, county_n, hospital_n, department_n, team_n = allocate_levels(count)
    nodes: list[dict] = []
    counter = start_index

    def next_source_id() -> str:
        nonlocal counter
        source_id = f"ORG-{counter:06d}"
        counter += 1
        return source_id

    by_level: dict[int, list[dict]] = {level: [] for level in range(5)}

    if root_n:
        source_id = next_source_id()
        node = {
            "source_id": source_id,
            "name": "National Health Service Kenya",
            "parent_source_id": None,
            "level": LEVEL_ROOT,
            "is_team": False,
            "city": "Nairobi",
            "has_contact": True,
        }
        nodes.append(node)
        by_level[LEVEL_ROOT].append(node)

    counties = random.sample(
        faker_kenya.KENYAN_COUNTIES, k=min(county_n, len(faker_kenya.KENYAN_COUNTIES))
    )
    for i in range(county_n):
        city = counties[i % len(counties)]
        parent = random.choice(by_level[LEVEL_ROOT]) if by_level[LEVEL_ROOT] else None
        node = {
            "source_id": next_source_id(),
            "name": f"{city} County Health Services",
            "parent_source_id": parent["source_id"] if parent else None,
            "level": LEVEL_COUNTY,
            "is_team": False,
            "city": city,
            "has_contact": True,
        }
        nodes.append(node)
        by_level[LEVEL_COUNTY].append(node)

    for _ in range(hospital_n):
        parent = random.choice(by_level[LEVEL_COUNTY]) if by_level[LEVEL_COUNTY] else None
        city = parent["city"] if parent else faker_kenya.kenyan_county()
        node = {
            "source_id": next_source_id(),
            "name": faker_kenya.hospital_name(city),
            "parent_source_id": parent["source_id"] if parent else None,
            "level": LEVEL_HOSPITAL,
            "is_team": False,
            "city": city,
            "has_contact": True,
        }
        nodes.append(node)
        by_level[LEVEL_HOSPITAL].append(node)

    for _ in range(department_n):
        parent = random.choice(by_level[LEVEL_HOSPITAL]) if by_level[LEVEL_HOSPITAL] else None
        city = parent["city"] if parent else faker_kenya.kenyan_county()
        node = {
            "source_id": next_source_id(),
            "name": faker_kenya.department_name(),
            "parent_source_id": parent["source_id"] if parent else None,
            "level": LEVEL_DEPARTMENT,
            "is_team": False,
            "city": city,
            "has_contact": False,
        }
        nodes.append(node)
        by_level[LEVEL_DEPARTMENT].append(node)

    for _ in range(team_n):
        parent = (
            random.choice(by_level[LEVEL_DEPARTMENT]) if by_level[LEVEL_DEPARTMENT] else None
        )
        department_name = parent["name"] if parent else faker_kenya.department_name()
        city = parent["city"] if parent else faker_kenya.kenyan_county()
        node = {
            "source_id": next_source_id(),
            "name": faker_kenya.team_name(department_name),
            "parent_source_id": parent["source_id"] if parent else None,
            "level": LEVEL_TEAM,
            "is_team": True,
            "city": city,
            "has_contact": False,
        }
        nodes.append(node)
        by_level[LEVEL_TEAM].append(node)

    return nodes


def to_csv_row(node: dict) -> dict:
    has_contact = node["has_contact"]
    return {
        "id": "",
        "name": node["name"],
        "is_team": "true" if node["is_team"] else "",
        "source_id": node["source_id"],
        "parent_id": "",
        "parent_name": "",
        "source_parent_id": node["parent_source_id"] or "",
        "phone": faker_kenya.kenyan_landline_phone() if has_contact else "",
        "email": faker_kenya.kenyan_email(node["source_id"].lower(), "ohs.dev") if has_contact else "",
        "physical_address": faker_kenya.kenyan_physical_address(node["city"]) if has_contact else "",
        "postal_address": faker_kenya.kenyan_postal_address(node["city"]) if has_contact else "",
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_generator_parser(
        "Generate a CSV for POST /api/bulk-import/organizations", DEFAULT_OUTPUT
    )
    args = parser.parse_args(argv)
    apply_seed(args.seed)

    pool = IdPool.load(args.manifest)
    if args.fresh:
        pool.reset("organizations")

    start_index = len(pool.organizations) + 1
    nodes = generate_nodes(args.count, start_index)
    write_csv(args.output, FIELDNAMES, (to_csv_row(n) for n in nodes))

    pool.extend(
        "organizations",
        [
            {
                "source_id": n["source_id"],
                "name": n["name"],
                "parent_source_id": n["parent_source_id"],
                "level": n["level"],
                "city": n["city"],
            }
            for n in nodes
        ],
    )
    pool.save(args.manifest)

    print(
        f"Wrote {len(nodes)} organizations to {args.output}; "
        f"manifest now has {len(pool.organizations)} organizations ({args.manifest})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
