"""Shared argparse builders for the generator and runner scripts."""

import argparse
import random
from pathlib import Path

from faker import Faker

from perfharness.config import get_base_url, get_bearer_token

DEFAULT_MANIFEST = Path("data/manifest.json")
DEFAULT_REPORT_DIR = Path("reports")


def build_generator_parser(description: str, default_output: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        "--count", "-n", type=int, required=True, help="Number of rows to generate"
    )
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        default=default_output,
        help=f"Output CSV path (default: {default_output})",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help=f"Shared source_id manifest path (default: {DEFAULT_MANIFEST})",
    )
    parser.add_argument(
        "--seed", type=int, default=None, help="Random seed for reproducible output"
    )
    parser.add_argument(
        "--fresh",
        action="store_true",
        help="Reset this entity's section of the manifest before generating",
    )
    return parser


def build_runner_parser(description: str) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--csv", type=Path, required=True, help="CSV file to import")
    parser.add_argument(
        "--base-url",
        default=get_base_url(),
        help="FHIR Server/Gateway base URL (default: env BASE_URL or http://localhost:8080)",
    )
    parser.add_argument(
        "--token",
        default=get_bearer_token(),
        help="Bearer token (default: env BEARER_TOKEN)",
    )
    parser.add_argument(
        "--report-dir",
        type=Path,
        default=DEFAULT_REPORT_DIR,
        help=f"Directory to write timing reports to (default: {DEFAULT_REPORT_DIR})",
    )
    parser.add_argument(
        "--timeout", type=float, default=600.0, help="HTTP request timeout in seconds"
    )
    return parser


def apply_seed(seed: int | None) -> None:
    if seed is None:
        return
    random.seed(seed)
    Faker.seed(seed)


def require_token(token: str | None) -> str:
    if not token:
        raise SystemExit(
            "A bearer token is required: pass --token or set the BEARER_TOKEN env var."
        )
    return token
