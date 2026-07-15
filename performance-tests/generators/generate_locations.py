"""Generates a CSV for POST /api/bulk-import/locations: a building -> ward ->
room tree anchored to hospital-level organizations already present in the
manifest (produced by generate_organizations.py). Requires organizations to
exist first -- fails fast with a clear message otherwise.

Nodes are emitted strictly parent-before-child, matching the README's
ordering note, avoiding the server's early-batch-flush path by default.
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
    "physical_type",
    "level",
    "longitude",
    "latitude",
    "source_id",
    "parent_id",
    "source_parent_id",
    "org_id",
    "source_org_id",
]

DEFAULT_OUTPUT = Path("data/locations.csv")

WARDS_PER_BUILDING = (2, 4)
ROOMS_PER_WARD = (2, 6)
HOSPITAL_LEVEL = 2


def pick_anchor_orgs(organizations: list[dict]) -> list[dict]:
    hospitals = [o for o in organizations if o.get("level") == HOSPITAL_LEVEL]
    return hospitals or organizations


def generate_nodes(count: int, start_index: int, organizations: list[dict]) -> list[dict]:
    anchors = pick_anchor_orgs(organizations)
    nodes: list[dict] = []
    counter = start_index

    def next_source_id() -> str:
        nonlocal counter
        source_id = f"LOC-{counter:06d}"
        counter += 1
        return source_id

    anchor_index = 0
    while len(nodes) < count:
        anchor = anchors[anchor_index % len(anchors)]
        anchor_index += 1
        city = anchor.get("city") or faker_kenya.kenyan_county()
        base_lat, base_lon = faker_kenya.base_coordinate(city)
        building_lat = faker_kenya.jittered_coordinate(base_lat, 0.03)
        building_lon = faker_kenya.jittered_coordinate(base_lon, 0.03)

        building = {
            "source_id": next_source_id(),
            "name": f"{anchor['name']} Main Building",
            "parent_source_id": None,
            "org_source_id": anchor["source_id"],
            "physical_type": "Building",
            "latitude": building_lat,
            "longitude": building_lon,
        }
        nodes.append(building)
        if len(nodes) >= count:
            break

        for ward_num in range(1, random.randint(*WARDS_PER_BUILDING) + 1):
            if len(nodes) >= count:
                break
            ward_lat = faker_kenya.jittered_coordinate(building_lat, 0.002)
            ward_lon = faker_kenya.jittered_coordinate(building_lon, 0.002)
            ward = {
                "source_id": next_source_id(),
                "name": f"Ward {ward_num}",
                "parent_source_id": building["source_id"],
                "org_source_id": anchor["source_id"],
                "physical_type": "Ward",
                "latitude": ward_lat,
                "longitude": ward_lon,
            }
            nodes.append(ward)
            if len(nodes) >= count:
                break

            for room_num in range(1, random.randint(*ROOMS_PER_WARD) + 1):
                if len(nodes) >= count:
                    break
                room = {
                    "source_id": next_source_id(),
                    "name": f"Room {ward_num}.{room_num}",
                    "parent_source_id": ward["source_id"],
                    "org_source_id": anchor["source_id"],
                    "physical_type": "Room",
                    "latitude": faker_kenya.jittered_coordinate(ward_lat, 0.0005),
                    "longitude": faker_kenya.jittered_coordinate(ward_lon, 0.0005),
                }
                nodes.append(room)

    return nodes


def to_csv_row(node: dict) -> dict:
    return {
        "id": "",
        "name": node["name"],
        "physical_type": node["physical_type"],
        "level": "",
        "longitude": node["longitude"],
        "latitude": node["latitude"],
        "source_id": node["source_id"],
        "parent_id": "",
        "source_parent_id": node["parent_source_id"] or "",
        "org_id": "",
        "source_org_id": node["org_source_id"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_generator_parser(
        "Generate a CSV for POST /api/bulk-import/locations", DEFAULT_OUTPUT
    )
    args = parser.parse_args(argv)
    apply_seed(args.seed)

    pool = IdPool.load(args.manifest)
    if not pool.organizations:
        raise SystemExit(
            "No organizations found in the manifest. Run generate_organizations.py "
            f"first, or pass --manifest pointing at one that already has organizations "
            f"({args.manifest})."
        )
    if args.fresh:
        pool.reset("locations")

    start_index = len(pool.locations) + 1
    nodes = generate_nodes(args.count, start_index, pool.organizations)
    write_csv(args.output, FIELDNAMES, (to_csv_row(n) for n in nodes))

    pool.extend(
        "locations",
        [
            {
                "source_id": n["source_id"],
                "name": n["name"],
                "parent_source_id": n["parent_source_id"],
                "org_source_id": n["org_source_id"],
            }
            for n in nodes
        ],
    )
    pool.save(args.manifest)

    print(
        f"Wrote {len(nodes)} locations to {args.output}; "
        f"manifest now has {len(pool.locations)} locations ({args.manifest})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
