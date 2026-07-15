"""Cross-generator relational glue: a JSON manifest of previously generated
source_ids, so e.g. generate_locations.py can reference orgs produced by an
earlier generate_organizations.py run, without a database.

Manifest shape:
{
  "organizations": [{"source_id": ..., "name": ..., "parent_source_id": ..., "level": ...}],
  "locations": [{"source_id": ..., "name": ..., "parent_source_id": ..., "org_source_id": ...}],
  "practitioners": [{"source_id": ..., "username": ...}]
}
"""

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

ENTITY_KEYS = ("organizations", "locations", "practitioners")


@dataclass
class IdPool:
    organizations: list[dict] = field(default_factory=list)
    locations: list[dict] = field(default_factory=list)
    practitioners: list[dict] = field(default_factory=list)

    @classmethod
    def load(cls, manifest_path: Path) -> "IdPool":
        if not manifest_path.exists():
            return cls()
        with open(manifest_path, encoding="utf-8") as f:
            data = json.load(f)
        return cls(
            organizations=data.get("organizations", []),
            locations=data.get("locations", []),
            practitioners=data.get("practitioners", []),
        )

    def save(self, manifest_path: Path) -> None:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "organizations": self.organizations,
            "locations": self.locations,
            "practitioners": self.practitioners,
        }
        tmp_path = manifest_path.with_suffix(manifest_path.suffix + ".tmp")
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)
        os.replace(tmp_path, manifest_path)

    def reset(self, entity: str) -> None:
        if entity not in ENTITY_KEYS:
            raise ValueError(f"Unknown entity: {entity}")
        setattr(self, entity, [])

    def extend(self, entity: str, entries: list[dict]) -> None:
        if entity not in ENTITY_KEYS:
            raise ValueError(f"Unknown entity: {entity}")
        getattr(self, entity).extend(entries)


def load_or_reset(manifest_path: Path, entity: str, fresh: bool) -> IdPool:
    pool = IdPool.load(manifest_path)
    if fresh:
        pool.reset(entity)
    return pool
