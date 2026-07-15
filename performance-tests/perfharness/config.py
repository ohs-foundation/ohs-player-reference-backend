"""Environment-driven defaults shared by generators and runners."""

import os

DEFAULT_BASE_URL = "http://localhost:8080"


def get_base_url() -> str:
    return os.environ.get("BASE_URL", DEFAULT_BASE_URL)


def get_bearer_token() -> str | None:
    return os.environ.get("BEARER_TOKEN")
