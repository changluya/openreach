#!/usr/bin/env python3
"""OpenReach Python tools + CLI.

Standard-library-only client for OpenReach's Web primitives. A downloaded Skill can
be initialized once with a user-provided service address; the resulting config.json is then reused by
all commands automatically. Agent initialization-state checks are deliberately
read-only: config existence + exactly one no-upstream API probe.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

SKILL_DIR = Path(__file__).resolve().parents[1]
CONFIG_PATH = SKILL_DIR / "config.json"
DEFAULT_TIMEOUT = 20.0


class OpenReachError(RuntimeError):
    """Raised when OpenReach returns an HTTP/API error or cannot be reached."""


def _normalize_base_url(value: str) -> str:
    value = value.strip().rstrip("/")
    if not value:
        raise ValueError("OpenReach server address cannot be empty")
    if not value.startswith(("http://", "https://")):
        value = "http://" + value
    parsed = urllib.parse.urlsplit(value)
    if not parsed.hostname:
        raise ValueError("Invalid OpenReach server address")
    return value




def _validate_read_target(url: str) -> None:
    """Fail fast for obvious caller-side misuse before creating a failed OpenReach request.

    This deliberately performs no DNS lookup: the server remains the source of truth for
    DNS rebinding/private-address protection. The Skill only catches literal/private and
    non-Web targets that an Agent can recognize locally.
    """
    if not isinstance(url, str) or not url.strip():
        raise OpenReachError("READ_TARGET_INVALID: url is required")
    try:
        parsed = urllib.parse.urlsplit(url.strip())
    except ValueError as exc:
        raise OpenReachError("READ_TARGET_INVALID: malformed URL") from exc
    if parsed.scheme.lower() not in {"http", "https"}:
        raise OpenReachError("READ_TARGET_INVALID: OpenReach read only accepts public HTTP/HTTPS web pages")
    if not parsed.hostname:
        raise OpenReachError("READ_TARGET_INVALID: URL host is required")
    if parsed.username is not None or parsed.password is not None:
        raise OpenReachError("READ_TARGET_INVALID: URLs containing user-info are not supported")

    host = parsed.hostname.lower().rstrip(".")
    if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
        raise OpenReachError(
            "READ_TARGET_PRIVATE: OpenReach read is for public web pages, not localhost/internal resources. "
            "Use the caller's local file/image/resource capability instead."
        )
    try:
        literal = ipaddress.ip_address(host)
    except ValueError:
        literal = None
    if literal is not None and not literal.is_global:
        raise OpenReachError(
            "READ_TARGET_PRIVATE: OpenReach read is for public web pages and rejects private/local/reserved IPs. "
            "Do not send internal attachment/file URLs to read; use the caller's local file/image/resource capability instead."
        )

    try:
        port = parsed.port
    except ValueError as exc:
        raise OpenReachError("READ_TARGET_INVALID: invalid URL port") from exc
    effective_port = port or (443 if parsed.scheme.lower() == "https" else 80)
    if effective_port not in {80, 443}:
        raise OpenReachError(
            "READ_TARGET_PORT: OpenReach read only accepts public Web ports 80/443. "
            "Non-standard service/attachment ports must be handled by the caller, not proxied through OpenReach."
        )

    lower_path = parsed.path.lower()
    binary_suffixes = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".avif", ".heic", ".zip", ".tar", ".gz", ".7z")
    if lower_path.endswith(binary_suffixes):
        raise OpenReachError(
            "READ_TARGET_BINARY: OpenReach read extracts HTML/XHTML/plain-text pages and is not a binary/image downloader. "
            "Use image-search or the caller's direct file/image capability for this URL."
        )


def build_base_url(host: str, port: int = 8080, https: bool = False) -> str:
    """Build a Base URL from an IP/domain or accept a complete http(s) URL."""
    host = host.strip()
    if host.startswith(("http://", "https://")):
        parsed = urllib.parse.urlsplit(host)
        if parsed.port is not None:
            return _normalize_base_url(host)
        scheme = parsed.scheme
        hostname = parsed.hostname or ""
        if ":" in hostname and not hostname.startswith("["):
            hostname = f"[{hostname}]"
        return _normalize_base_url(f"{scheme}://{hostname}:{port}")
    scheme = "https" if https else "http"
    # Accept host:port as an advanced shortcut; plain IP/domain only needs one argument.
    candidate = host if ":" in host and host.count(":") == 1 else f"{host}:{port}"
    return _normalize_base_url(f"{scheme}://{candidate}")


def load_config(path: Path | None = None) -> dict[str, Any]:
    config_path = path or CONFIG_PATH
    if not config_path.exists():
        return {}
    try:
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise OpenReachError(f"Invalid config file {config_path}: {exc}") from exc
    return data if isinstance(data, dict) else {}


def save_config(base_url: str, path: Path | None = None) -> Path:
    config_path = path or CONFIG_PATH
    config_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"base_url": _normalize_base_url(base_url)}
    config_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return config_path


def resolve_base_url(explicit: str | None = None) -> str:
    """Resolution priority: explicit override > environment > skill config.json. Never guess a fallback host."""
    if explicit:
        return _normalize_base_url(explicit)
    env = os.getenv("OPENREACH_BASE_URL")
    if env:
        return _normalize_base_url(env)
    configured = load_config().get("base_url")
    if configured:
        return _normalize_base_url(str(configured))
    raise OpenReachError(
        "OpenReach is not initialized. Ask the user to provide <OPENREACH_BASE_URL>; "
        "do not guess localhost/private IPs, scan ports, or run init without the user-provided address."
    )


@dataclass(frozen=True)
class OpenReachClient:
    base_url: str
    timeout: float = DEFAULT_TIMEOUT

    def __post_init__(self) -> None:
        object.__setattr__(self, "base_url", _normalize_base_url(self.base_url))

    def _request_json(self, request: urllib.request.Request) -> dict[str, Any]:
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                detail = json.loads(raw)
                message = detail.get("message") or raw
                code = detail.get("code") or f"HTTP_{exc.code}"
                failure_type = detail.get("failureType")
                upstream_status = detail.get("upstreamStatus")
                retryable = detail.get("retryable")
                context = []
                if failure_type:
                    context.append(f"failureType={failure_type}")
                if upstream_status is not None:
                    context.append(f"upstreamStatus={upstream_status}")
                if retryable is not None:
                    context.append(f"retryable={str(bool(retryable)).lower()}")
                suffix = f" ({', '.join(context)})" if context else ""
                raise OpenReachError(f"{code}: {message}{suffix}") from exc
            except json.JSONDecodeError:
                raise OpenReachError(f"HTTP {exc.code}: {raw or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise OpenReachError(f"Cannot reach OpenReach at {self.base_url}: {exc.reason}") from exc
        except TimeoutError as exc:
            raise OpenReachError(f"OpenReach request timed out after {self.timeout:g}s") from exc

    def _post(self, path: str, payload: Mapping[str, Any]) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "openreach-skill/0.1.3",
            },
        )
        return self._request_json(request)

    def health(self) -> dict[str, Any]:
        """Check service reachability through the public static homepage.

        OpenReach v0.1.3 intentionally exposes only three JSON APIs; no separate
        health/debug API is public. A successful GET / is therefore the zero-upstream
        connectivity check used by the Skill.
        """
        request = urllib.request.Request(
            f"{self.base_url}/",
            method="GET",
            headers={"Accept": "text/html", "User-Agent": "openreach-skill/0.1.3"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                # Read only a small prefix; the body is not part of the doctor contract.
                response.read(512)
                return {"status": "UP", "service": "openreach", "httpStatus": response.status}
        except urllib.error.HTTPError as exc:
            raise OpenReachError(f"HTTP {exc.code}: OpenReach homepage check failed") from exc
        except urllib.error.URLError as exc:
            raise OpenReachError(f"Cannot reach OpenReach at {self.base_url}: {exc.reason}") from exc
        except TimeoutError as exc:
            raise OpenReachError(f"OpenReach request timed out after {self.timeout:g}s") from exc

    def search(self, query: str, *, limit: int = 10, region: str = "auto", provider: str = "auto",
               time_range: str = "any") -> dict[str, Any]:
        return self._post("/api/web/search", {
            "query": query, "limit": limit, "region": region, "provider": provider, "timeRange": time_range
        })

    def image_search(self, query: str, *, limit: int = 10, region: str = "auto", provider: str = "auto") -> dict[str, Any]:
        return self._post("/api/web/image-search", {"query": query, "limit": limit, "region": region, "provider": provider})

    def read(self, url: str, *, max_chars: int = 50000) -> dict[str, Any]:
        _validate_read_target(url)
        return self._post("/api/web/read", {"url": url, "maxChars": max_chars})


def _client(base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> OpenReachClient:
    return OpenReachClient(resolve_base_url(base_url), timeout)


# Agent-friendly Python tool functions.

def check_initialized(config_path: Path | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Check initialization state without changing anything.

    Contract for Agents:
    1. Check only whether config.json exists. If absent, return immediately with
       initialized=false and perform zero network requests.
    2. If present, read only its base_url and perform exactly one side-effect-free
       API probe: POST /api/web/search with an empty JSON object. OpenReach should
       reject it locally with HTTP 400 / VALIDATION_ERROR before any upstream
       provider is invoked.
    3. Never create, rewrite, delete, repair or initialize configuration here; never
       retry or scan alternative hosts/ports.
    """
    path = config_path or CONFIG_PATH
    if not path.exists():
        return {
            "initialized": False,
            "config": str(path),
            "reason": "CONFIG_MISSING",
            "networkProbes": 0,
            "userActionRequired": True,
            "nextAction": "ASK_USER_FOR_SERVICE_ADDRESS",
            "message": (
                "OpenReach is not initialized. Ask the user to provide <OPENREACH_BASE_URL>. "
                "Stop until the user provides it; do not guess an address, scan ports, or run init automatically."
            ),
        }

    try:
        data = load_config(path)
        configured = data.get("base_url")
        if not configured:
            return {
                "initialized": False,
                "config": str(path),
                "reason": "BASE_URL_MISSING",
                "networkProbes": 0,
                "userActionRequired": True,
                "nextAction": "ASK_USER_FOR_SERVICE_ADDRESS",
                "message": (
                    "OpenReach config has no service address. Ask the user to provide <OPENREACH_BASE_URL>; "
                    "do not repair the file or run init automatically."
                ),
            }
        base_url = _normalize_base_url(str(configured))
    except (OpenReachError, ValueError, OSError) as exc:
        return {
            "initialized": False,
            "config": str(path),
            "reason": "CONFIG_INVALID",
            "error": str(exc),
            "networkProbes": 0,
            "userActionRequired": True,
            "nextAction": "ASK_USER_FOR_SERVICE_ADDRESS",
            "message": (
                "OpenReach config is invalid. Ask the user to provide the intended <OPENREACH_BASE_URL>. "
                "Do not overwrite, delete, or repair the config automatically."
            ),
        }

    request = urllib.request.Request(
        f"{base_url}/api/web/search",
        data=b"{}",
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "openreach-skill/0.1.3",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            # A 2xx response to an empty SearchRequest is not the expected OpenReach
            # validation contract; still only one probe has been performed.
            response.read(512)
            return {
                "initialized": False,
                "config": str(path),
                "base_url": base_url,
                "reason": "UNEXPECTED_PROBE_RESPONSE",
                "httpStatus": response.status,
                "networkProbes": 1,
            }
    except urllib.error.HTTPError as exc:
        raw = exc.read(4096).decode("utf-8", errors="replace")
        try:
            detail = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            detail = {}
        code = str(detail.get("code", ""))
        if exc.code == 400 and code == "VALIDATION_ERROR":
            return {
                "initialized": True,
                "config": str(path),
                "base_url": base_url,
                "status": "READY",
                "probe": "POST /api/web/search",
                "httpStatus": 400,
                "code": code,
                "networkProbes": 1,
            }
        return {
            "initialized": False,
            "config": str(path),
            "base_url": base_url,
            "reason": "PROBE_REJECTED",
            "httpStatus": exc.code,
            "code": code or None,
            "networkProbes": 1,
        }
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        detail = getattr(exc, "reason", exc)
        return {
            "initialized": False,
            "config": str(path),
            "base_url": base_url,
            "reason": "PROBE_FAILED",
            "error": str(detail),
            "networkProbes": 1,
        }

def doctor(base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Manual connectivity diagnostic. Normal Agents should use check_initialized() once instead."""
    result = _client(base_url, timeout).health()
    if str(result.get("status", "")).upper() != "UP":
        raise OpenReachError(f"Unexpected health response: {result}")
    return result


def initialize(host: str, port: int = 8080, https: bool = False, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Validate an OpenReach server and persist it to this Skill's config.json."""
    base_url = build_base_url(host, port=port, https=https)
    health = doctor(base_url=base_url, timeout=timeout)  # connectivity is the init precondition
    config_path = save_config(base_url)
    return {"status": "OK", "base_url": base_url, "config": str(config_path), "health": health}


def search(query: str, limit: int = 10, region: str = "auto", provider: str = "auto",
           time_range: str = "any", base_url: str | None = None,
           timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Search public Web sources; time_range supports any/day/week/month/year."""
    return _client(base_url, timeout).search(
        query, limit=limit, region=region, provider=provider, time_range=time_range
    )


def image_search(query: str, limit: int = 10, region: str = "auto", provider: str = "auto",
                 base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Search images; returned imageUrl values have passed direct-download validation."""
    return _client(base_url, timeout).image_search(query, limit=limit, region=region, provider=provider)


def read(url: str, max_chars: int = 50000, base_url: str | None = None,
         timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Read and extract a public Web page with OpenReach."""
    return _client(base_url, timeout).read(url, max_chars=max_chars)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="openreach", description="OpenReach Skill CLI: check, init, doctor, search, image-search and read.")
    parser.add_argument("--base-url", default=None, help="Temporary OpenReach URL override. Otherwise env/config.json is used.")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="HTTP timeout in seconds")
    parser.add_argument("--compact", action="store_true", help="Print compact JSON")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check", help="Read-only initialization check: config.json + exactly one API probe")

    p_init = sub.add_parser("init", help="Initialize this Skill with the OpenReach service address explicitly provided by the user")
    p_init.add_argument("host", help="User-provided OpenReach host or full http(s) service URL; no default address is assumed")
    p_init.add_argument("--port", type=int, default=8080)
    p_init.add_argument("--https", action="store_true")

    sub.add_parser("doctor", help="Manual homepage connectivity diagnostic; not part of normal Agent flow")

    p_search = sub.add_parser("search", help="Search Web pages")
    p_search.add_argument("query")
    p_search.add_argument("--limit", type=int, default=10)
    p_search.add_argument("--region", default="auto", help="Search region; default: auto. Examples: CN, US, JP, wt-wt")
    p_search.add_argument("--provider", default="auto")
    p_search.add_argument("--time-range", default="any", choices=("any", "day", "week", "month", "year"),
                          help="Upstream-enforced Web time filter")

    p_image = sub.add_parser("image-search", aliases=["image_search"], help="Search images")
    p_image.add_argument("query")
    p_image.add_argument("--limit", type=int, default=10)
    p_image.add_argument("--region", default="auto", help="Search region; default: auto")
    p_image.add_argument("--provider", default="auto")

    p_read = sub.add_parser("read", help="Read a public Web page")
    p_read.add_argument("url")
    p_read.add_argument("--max-chars", type=int, default=50000)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "check":
            result = check_initialized(timeout=args.timeout)
        elif args.command == "init":
            result = initialize(args.host, port=args.port, https=args.https, timeout=args.timeout)
        elif args.command == "doctor":
            result = {"base_url": resolve_base_url(args.base_url), **doctor(args.base_url, args.timeout)}
        else:
            client = _client(args.base_url, args.timeout)
            if args.command == "search":
                result = client.search(args.query, limit=args.limit, region=args.region, provider=args.provider, time_range=args.time_range)
            elif args.command in {"image-search", "image_search"}:
                result = client.image_search(args.query, limit=args.limit, region=args.region, provider=args.provider)
            else:
                result = client.read(args.url, max_chars=args.max_chars)
    except (OpenReachError, ValueError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if args.compact:
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
