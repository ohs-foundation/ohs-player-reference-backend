# Bulk Import Performance Tests

A Python harness for load-testing the 4 bulk-import endpoints documented in
the project [README](../README.md#bulk-import-api) at realistic scale
(5,000-10,000+ rows). It generates relationally-coherent CSV datasets
(organizations/locations/user-assignments reference real, previously
generated `source_id`s, with parents ordered before children) and posts them
to a running FHIR Gateway instance, timing the SSE response stream.

## Setup

Requires Python 3.11+.

```sh
cd performance-tests
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

The rest of this doc assumes the venv is activated (so `python` resolves to `.venv/bin/python`). If you'd rather not activate it (e.g. in a script), call `.venv/bin/python` explicitly instead everywhere below.

## Configuration

| Variable | Used by | Description |
| --- | --- | --- |
| `BASE_URL` | runners | FHIR Server/Gateway base URL (default `http://localhost:8080`) |
| `BEARER_TOKEN` | runners | JWT with the `bulk-import.manage` role. Required -- there is no login flow in this harness. |

Everything above can also be passed as a CLI flag (`--base-url`, `--token`), which takes precedence over the env var.

## Layout

- `perfharness/` -- shared library (CSV sanitization, Kenyan-flavored Faker helpers, the `source_id` manifest, the SSE HTTP client, report writing, shared CLI flags).
- `generators/generate_*.py` -- write a CSV for one entity and (for users/organizations/locations) append the entities they created to `data/manifest.json` so later generators can reference them.
- `runners/import_*.py` -- POST one CSV to its endpoint and print/record timing.
- `runners/run_all.py` -- generate + import all 4 entities in one shot, in README order, sharing one manifest.
- `data/` and `reports/` are gitignored; generated at runtime.

## Quick start: run everything

```sh
export BEARER_TOKEN=eyJhbGciOi...
python -m runners.run_all --count 5000
```

This generates and imports Users -> Organizations -> Locations -> User Assignments (in that order, matching the README), writing that run's CSVs to `data/run_<timestamp>/` and a report per step plus a combined `reports/run_all_<timestamp>.json` under `reports/`. The `source_id` manifest itself is **not** per-run -- see [Resetting / re-running](#resetting--re-running) below.

Useful flags: `--users-count`/`--organizations-count`/`--locations-count`/`--user-assignments-count` to override `--count` per entity, `--seed` for reproducible data, `--fail-fast` to stop the suite as soon as a step reports any failed rows.

## Running one entity at a time

Entities must be generated in this order, since each later one references `source_id`s from the manifest built by the earlier ones:

```sh
# 1. Users (-> Practitioners)
python -m generators.generate_users --count 5000 --output data/users.csv --manifest data/manifest.json
python -m runners.import_users --csv data/users.csv

# 2. Organizations (hierarchy: national body -> county -> hospital -> department -> team)
python -m generators.generate_organizations --count 5000 --output data/organizations.csv --manifest data/manifest.json
python -m runners.import_organizations --csv data/organizations.csv

# 3. Locations (hierarchy: building -> ward -> room, anchored to hospital-level orgs)
python -m generators.generate_locations --count 5000 --output data/locations.csv --manifest data/manifest.json
python -m runners.import_locations --csv data/locations.csv

# 4. User assignments (samples practitioners/orgs/locations from the manifest)
python -m generators.generate_user_assignments --count 5000 --output data/user_assignments.csv --manifest data/manifest.json
python -m runners.import_user_assignments --csv data/user_assignments.csv
```

`generate_organizations.py` must run before `generate_locations.py` (locations anchor to org `source_id`s), and `generate_users.py` before `generate_user_assignments.py` (assignments sample practitioner `source_id`s). Each generator fails fast with a clear message if a required entity is missing from the manifest.

Pass `--fresh` to a generator to reset just that entity's section of the manifest (e.g. regenerate organizations without touching the practitioners already recorded).

## Resetting / re-running

**Default behavior needs no manual reset.** The `source_id` manifest defaults to a single persistent file, `data/manifest.json` -- not something scoped to one run -- so every generator invocation (including each step inside `run_all.py`) picks up where the last one left off and keeps incrementing (`PRAC-000001`, `PRAC-000002`, ...). Running `run_all.py --count 5000` twice in a row against the same server therefore imports 10,000 distinct practitioners/organizations/locations in total, not the same 5,000 twice. `run_all.py`'s `--data-dir` (default `data/run_<timestamp>/`) only affects where that invocation's *CSVs* land, so old CSVs are never overwritten -- it's independent of the manifest.

**What `--fresh` actually does.** It resets the *local* manifest counters for that entity back to 1 -- it does not delete or reset anything on the FHIR server. If you pass `--fresh` (or delete `data/manifest.json`) without also clearing out the target server's data, the next run will generate the same `source_id`s the very first run used, and the bulk-import endpoints will resolve those via identifier lookup and **update** the existing resources instead of creating new ones (see the main README's create/update resolution rules) -- which quietly changes what you're measuring from "create N new rows" to "update N existing rows." Only use `--fresh`/delete the manifest when the target server itself is also empty or has been reset out-of-band (e.g. a fresh HAPI FHIR instance).

**This harness never deletes server-side data.** There's no bulk-delete in the bulk-import API to call even if it did. Repeated runs against a shared or long-lived FHIR server will accumulate Organizations/Locations/Practitioners/PractitionerRoles indefinitely (by design, so throughput numbers reflect real creates) -- plan to point performance runs at a disposable/dev FHIR instance you're comfortable growing or periodically wiping yourself.

**Local artifacts**, for comparison, are cheap to reset any time since they carry no relational meaning across a wipe:
- `data/manifest.json` -- delete to restart ID numbering at 1 (see caveat above).
- `data/*.csv`, `data/run_*/` -- just generated files; delete freely, nothing depends on them existing.
- `reports/summary.csv` -- accumulates one row per import *by design*, so you can compare runs over time. Delete it if you want a clean comparison table for a new benchmarking session.

## Reports

Every import writes two files under `reports/` (or `--report-dir`):

- `{entity}_{row_count}_{timestamp}.json` -- full detail, including the first 50 row-level errors.
- `summary.csv` -- one row appended per run (`timestamp, entity, csv_path, row_count, wall_seconds, ttfb_seconds, processed, failed, total, rows_per_second, stopped_early, error_count`). Open this in a spreadsheet to compare e.g. 5,000 vs 10,000-row runs.

## Notes

- **CSV generation avoids literal commas.** The server's CSV parser (`CsvProcessor.java`) does a naive comma-split with no quoting, so every field the generators write is sanitized to strip commas/newlines. Don't hand-edit generated CSVs with comma-containing values.
- **Users endpoint stops on first error**; Organizations/Locations/User-assignments continue past row errors and always emit a final `done` event. The harness's `SseImportClient` handles both protocols.
- **Re-running the same CSV**: Organizations and Locations are idempotent via `source_id` (safe to re-run). User Assignments are create-only -- re-running creates duplicate `PractitionerRole` resources (matches the main README's documented behavior).
