#!/usr/bin/env python3
"""OpenReach Python tools + CLI.

Standard-library-only client for OpenReach's Web primitives. A downloaded Skill can
be initialized once with a server IP; the resulting config.json is then reused by
all commands automatically.
"""
from __future__ import annotations

import argparse
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
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
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
    """Resolution priority: CLI/Python explicit > env > skill config.json > localhost."""
    if explicit:
        return _normalize_base_url(explicit)
    env = os.getenv("OPENREACH_BASE_URL")
    if env:
        return _normalize_base_url(env)
    configured = load_config().get("base_url")
    if configured:
        return _normalize_base_url(str(configured))
    return DEFAULT_BASE_URL


@dataclass(frozen=True)
class OpenReachClient:
    base_url: str = DEFAULT_BASE_URL
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
                raise OpenReachError(f"{code}: {message}") from exc
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
                "User-Agent": "openreach-skill/0.2.0",
            },
        )
        return self._request_json(request)

    def health(self) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{self.base_url}/api/web/health",
            method="GET",
            headers={"Accept": "application/json", "User-Agent": "openreach-skill/0.2.0"},
        )
        return self._request_json(request)

    def search(self, query: str, *, limit: int = 10, region: str = "auto", provider: str = "auto") -> dict[str, Any]:
        return self._post("/api/web/search", {"query": query, "limit": limit, "region": region, "provider": provider})

    def image_search(self, query: str, *, limit: int = 10, region: str = "auto", provider: str = "auto") -> dict[str, Any]:
        return self._post("/api/web/image-search", {"query": query, "limit": limit, "region": region, "provider": provider})

    def read(self, url: str, *, max_chars: int = 50000) -> dict[str, Any]:
        return self._post("/api/web/read", {"url": url, "maxChars": max_chars})


def _client(base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> OpenReachClient:
    return OpenReachClient(resolve_base_url(base_url), timeout)


# Agent-friendly Python tool functions.
def doctor(base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Check whether the configured OpenReach service is reachable before other tools are used."""
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
           base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Search public Web sources with OpenReach."""
    return _client(base_url, timeout).search(query, limit=limit, region=region, provider=provider)


def image_search(query: str, limit: int = 10, region: str = "auto", provider: str = "auto",
                 base_url: str | None = None, timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Search images and their source pages with OpenReach."""
    return _client(base_url, timeout).image_search(query, limit=limit, region=region, provider=provider)


def read(url: str, max_chars: int = 50000, base_url: str | None = None,
         timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any]:
    """Read and extract a public Web page with OpenReach."""
    return _client(base_url, timeout).read(url, max_chars=max_chars)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="openreach", description="OpenReach Skill CLI: init, doctor, search, image-search and read.")
    parser.add_argument("--base-url", default=None, help="Temporary OpenReach URL override. Otherwise env/config.json is used.")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="HTTP timeout in seconds")
    parser.add_argument("--compact", action="store_true", help="Print compact JSON")
    sub = parser.add_subparsers(dest="command", required=True)

    p_init = sub.add_parser("init", help="Initialize this Skill with an OpenReach server IP/domain")
    p_init.add_argument("host", help="Server IP/domain, e.g. 10.0.0.8. Full http(s) URL is also accepted.")
    p_init.add_argument("--port", type=int, default=8080)
    p_init.add_argument("--https", action="store_true")

    sub.add_parser("doctor", help="Check OpenReach connectivity; run this before using Web tools")

    p_search = sub.add_parser("search", help="Search Web pages")
    p_search.add_argument("query")
    p_search.add_argument("--limit", type=int, default=10)
    p_search.add_argument("--region", default="auto", help="Search region; default: auto. Examples: CN, US, JP, wt-wt")
    p_search.add_argument("--provider", default="auto")

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
        if args.command == "init":
            result = initialize(args.host, port=args.port, https=args.https, timeout=args.timeout)
        elif args.command == "doctor":
            result = {"base_url": resolve_base_url(args.base_url), **doctor(args.base_url, args.timeout)}
        else:
            client = _client(args.base_url, args.timeout)
            if args.command == "search":
                result = client.search(args.query, limit=args.limit, region=args.region, provider=args.provider)
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
