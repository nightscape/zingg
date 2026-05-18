"""Stage 2c — the LLM judge.

`Judge` is the pluggable interface; `OpenAIJudge` is the text backend (prompt v3).
The judge is the accuracy bottleneck, so it is the easiest thing to swap: point
`ModelConfig.judge_model` at a bigger model, or implement a new `Judge`.

The interface accepts an optional `images` argument so a vision judge (VLM) can
be added without touching callers. No vision backend ships yet — `OpenAIJudge`
rejects images rather than silently ignoring them.
"""

from __future__ import annotations

import re
from typing import Protocol

from .http import post

# Verdict codes used throughout the pipeline.
MATCH, NON_MATCH, UNSURE = 1, 0, -1
VERDICT_LABEL = {MATCH: "match", NON_MATCH: "non-match", UNSURE: "unsure"}

# Unambiguous verdict words (avoids the "-1" minus-plus-digit ambiguity).
_VERDICT_RX = re.compile(r"(?i)\b(yes|no|unsure)\b")
_VERDICT_CODE = {"yes": MATCH, "no": NON_MATCH, "unsure": UNSURE}

# Prompt v3: an explicit "identify component -> did the ticket act on THAT
# component" chain plus a hard NO-list (host copy/clone, reboots, routine
# patching, risk/verification tickets, vuln-restating tickets). On the 180-pair
# ground truth this lifts the judge from F1 0.43 to 0.65 (recall 0.92) vs the
# old prompt, which both rejected real "remove obsolete Java"/rotation matches
# and rubber-stamped hubs. Do not regress this prompt without re-measuring.
_PROMPT_V3 = (
    "You are matching a security VULNERABILITY (found repeatedly across many "
    "machines) to the OPERATIONS ticket that actually REMEDIATES it. The real fix "
    "is applied once at the infrastructure level and covers many machines.\n\n"
    "VULNERABILITY:\n{vuln}\n\nOPERATIONS TICKET:\n{ops}\n\n"
    "Answer YES only if the ticket performs a concrete fixing ACTION ON THE EXACT "
    "software/component/misconfiguration the vulnerability is about.\n"
    "Identify the component first (use product knowledge: Azul Zulu, JRockit, "
    "Oracle Java SE, GraalVM, OpenJDK = the JAVA runtime; RHEL = the Linux OS; "
    "Log4j = a Java logging library, NOT the JDK). Then check the ticket acts on "
    "THAT component: upgrade/remove/replace it, rotate THOSE keys, fix THAT "
    "certificate, harden THAT setting.\n\n"
    "Answer NO if the ticket is any of these, even when it mentions the same topic:\n"
    "- copying, cloning, or migrating a host/server (e.g. 'Copy <host>')\n"
    "- a reboot, or routine/scheduled/quarterly OS patching not aimed at this CVE\n"
    "- risk tracking, verification, crosscheck, recertification, or status tickets\n"
    "- a ticket that only RESTATES the vulnerability without fixing it\n"
    "- work on a DIFFERENT component/asset that merely shares a word or theme\n\n"
    "Reason in ONE short sentence naming the component and the action, then end "
    "with exactly one word on its own line: YES, NO, or UNSURE."
)


def parse_verdict(content: str, reasoning: str) -> int:
    """The final verdict word in `content` (the post-think answer); fall back to
    the last verdict word in the reasoning trace if the thinking toggle left
    content empty; UNSURE if neither has one."""
    for text in (content or "", reasoning or ""):
        found = _VERDICT_RX.findall(text)
        if found:
            return _VERDICT_CODE[found[-1].lower()]
    return UNSURE


class Judge(Protocol):
    def judge(self, vulnerability: str, ops_ticket: str, images: list | None = None) -> int:
        """Return MATCH / NON_MATCH / UNSURE for whether the ops ticket
        remediates the vulnerability. `images` is reserved for vision judges."""
        ...


class OpenAIJudge:
    """Chat-completions judge against any OpenAI-compatible endpoint.

    `api_key` adds `Authorization: Bearer <key>`; `extra_headers` carries
    gateway-specific headers (e.g. GitHub Copilot's `Copilot-Integration-Id`,
    `Editor-Version`). `disable_thinking` adds the LocalAI / LM-Studio
    thinking-off switches — leave it off for strict gateways (Copilot, OpenAI)
    that reject unknown fields with a 400.

    Determinism: temperature 0 + a fixed seed. OpenAI/Copilot honour `seed`;
    LocalAI randomises it unless set, so it is sent unconditionally.
    """

    def __init__(self, base: str, model: str, *, api_key: str | None = None,
                 extra_headers: dict | None = None, max_tokens: int = 2048,
                 temperature: float = 0.0, seed: int = 42, disable_thinking: bool = False):
        self.base = base
        self.model = model
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.seed = seed
        self.disable_thinking = disable_thinking
        self.headers = dict(extra_headers or {})
        if api_key:
            self.headers["Authorization"] = f"Bearer {api_key}"

    def judge(self, vulnerability: str, ops_ticket: str, images: list | None = None) -> int:
        if images:
            raise NotImplementedError(
                "OpenAIJudge is text-only; implement a vision Judge to use images")
        prompt = _PROMPT_V3.format(vuln=vulnerability, ops=ops_ticket)
        payload = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "seed": self.seed,
        }
        if self.disable_thinking:
            # enable_thinking=false is honored as a top-level field (LM Studio MLX)
            # and as a string under metadata (LocalAI). Only for endpoints that
            # accept these non-standard fields.
            payload["enable_thinking"] = False
            payload["metadata"] = {"enable_thinking": "false"}
        msg = post(self.base, "/chat/completions", payload, headers=self.headers)["choices"][0]["message"]
        return parse_verdict(msg.get("content") or "", msg.get("reasoning_content") or "")
