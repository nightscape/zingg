"""LLM-backed labeller — port of ``LlmLabeller.scala``.

Calls an OpenAI-compatible chat-completions endpoint, one request per pair, and
parses a single 1/0/-1 verdict. Pairs the model can't decide (-1, unparseable,
or HTTP error) are skipped. Uses stdlib ``urllib`` — no extra dependency.

Env (constructor args override):
  ZINGG_AI_ENDPOINT  full URL  (default http://localhost:11434/v1/chat/completions)
                     — or set OPENAI_BASE (e.g. http://127.0.0.1:8080/v1) and the
                       /chat/completions suffix is appended.
  ZINGG_AI_MODEL     default llama3
  ZINGG_AI_KEY       sent as `Authorization: Bearer …` if set
"""

from __future__ import annotations

import json
import os
import re
import socket
import urllib.parse
import urllib.request

from .labeller import Decision, RowLabeller
from .schema import ZinggConf

_LAST_SIGNED_DIGIT = re.compile(r"(?<![0-9])(-?[01])(?![0-9])")


def _default_endpoint() -> str:
    if "ZINGG_AI_ENDPOINT" in os.environ:
        return os.environ["ZINGG_AI_ENDPOINT"]
    base = os.environ.get("OPENAI_BASE")
    if base:
        return base.rstrip("/") + "/chat/completions"
    return "http://localhost:11434/v1/chat/completions"


def endpoint_reachable(url: str | None = None, timeout: float = 1.0) -> bool:
    """Cheap TCP reachability check for the configured LLM endpoint."""
    parsed = urllib.parse.urlparse(url or _default_endpoint())
    host = parsed.hostname
    if host is None:
        return False
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def first_signed_digit(s: str):
    """LAST standalone -1/0/1 in the text (reasoning models end with the answer)."""
    matches = _LAST_SIGNED_DIGIT.findall(s)
    return int(matches[-1]) if matches else None


def parse_label(body: str):
    """Extract the verdict from an OpenAI chat-completions response."""
    root = json.loads(body)
    choices = root.get("choices")
    if not isinstance(choices, list) or not choices:
        return None
    msg = choices[0].get("message", {})
    for key in ("content", "reasoning_content", "reasoning"):
        text = msg.get(key) or ""
        if text.strip():
            return first_signed_digit(text)
    return None


class LlmLabeller(RowLabeller):
    def __init__(self, endpoint: str | None = None, model: str | None = None,
                 api_key: str | None = None, timeout: float = 60.0):
        self.endpoint = endpoint or _default_endpoint()
        self.model = model or os.environ.get("ZINGG_AI_MODEL", "llama3")
        self.api_key = api_key if api_key is not None else os.environ.get("ZINGG_AI_KEY")
        self.timeout = timeout

    def decide(self, r, cfg: ZinggConf) -> Decision:
        v = self._ask_one(r, cfg)
        if v == 1:
            return Decision.Match
        if v == 0:
            return Decision.NonMatch
        return Decision.Unknown

    def _ask_one(self, r, cfg: ZinggConf):
        try:
            return parse_label(self._call_api(self._build_prompt(r, cfg)))
        except Exception:
            return None

    def _build_prompt(self, r, cfg: ZinggConf) -> str:
        def val(prefix, name):
            v = r.get(f"{prefix}{name}")
            return "" if v is None else str(v)

        rendered = "\n".join(
            f"  {f.name:<20} | A: {val(ZinggConf.LEFT_PREFIX, f.name)}\n"
            f"  {'':<20}  | B: {val(ZinggConf.RIGHT_PREFIX, f.name)}"
            for f in cfg.fields
        )
        return (
            "You are deciding whether two records refer to the same real-world entity.\n"
            "Answer with EXACTLY one digit on a line by itself, no other text:\n"
            "  1  = same entity (match)\n"
            "  0  = different entities (non-match)\n"
            " -1  = cannot tell from the information given\n\n"
            f"Record pair:\n{rendered}\n\nAnswer:"
        )

    def _call_api(self, prompt: str) -> str:
        payload = {
            "model": self.model,
            "temperature": 0.0,
            "messages": [{"role": "user", "content": prompt}],
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(self.endpoint, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            if resp.status != 200:
                raise RuntimeError(f"LLM HTTP {resp.status}")
            return resp.read().decode("utf-8")
