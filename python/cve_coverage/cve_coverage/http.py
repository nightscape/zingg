"""Minimal OpenAI-compatible HTTP client (stdlib only)."""

from __future__ import annotations

import json
import urllib.request


def post(base: str, path: str, payload: dict, headers: dict | None = None,
         timeout: int = 120) -> dict:
    hdrs = {"Content-Type": "application/json"}
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(
        base + path, method="POST",
        data=json.dumps(payload).encode(), headers=hdrs,
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())
