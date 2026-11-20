"""Check the packaged demo over loopback HTTP without storing response bodies."""
import argparse
import json
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, ProxyHandler


def validate_base_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.username or parsed.password or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment):
        raise ValueError("Use a loopback HTTP origin without credentials or a path")
    # Force validation of malformed/out-of-range ports too.
    parsed.port
    return value.rstrip("/")


def check(fetch):
    status, kind, body = fetch("/api/commerce/runtime")
    if status != 200 or "application/json" not in kind:
        raise ValueError("Runtime API did not respond with JSON")
    runtime = json.loads(body)
    if runtime.get("application") != "tv3-commerce" or runtime.get("isolatedData") is not True:
        raise ValueError("Runtime must identify an isolated commerce database")
    status, kind, body = fetch("/cart")
    if status != 200 or "text/html" not in kind:
        raise ValueError("Packaged cart did not serve HTML")
    match = re.search(rb'<script\b[^>]*\bsrc=[\'"](/assets/[A-Za-z0-9_.-]+\.js)[\'"]', body)
    if not match:
        raise ValueError("Packaged HTML is missing a local Vite script")
    status, kind, deep = fetch("/orders/123")
    if status != 200 or "text/html" not in kind or match.group(1) not in deep:
        raise ValueError("Deep link did not serve the packaged SPA")
    status, kind, body = fetch(match.group(1).decode("ascii"))
    if status != 200 or "javascript" not in kind or not body:
        raise ValueError("Packaged JavaScript asset is unavailable")
    status, kind, body = fetch("/assets/ci-missing.js")
    if status != 404 or "text/html" in kind:
        raise ValueError("Missing asset must return 404 without SPA HTML")
    status, kind, body = fetch("/api/ci-missing")
    if status != 404 or "text/html" in kind:
        raise ValueError("Unknown API must return 404 without SPA HTML")
    return {"checks": 6, "isolated_database": True, "same_origin_assets": True}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--output", type=Path, default=Path("target/ci-results/packaged-summary.json"))
    args = parser.parse_args()
    try:
        base = validate_base_url(args.base_url)
        if not 0 < args.timeout <= 300:
            raise ValueError("Timeout must be between 0 and 300 seconds")
        opener = build_opener(ProxyHandler({}), NoRedirect())

        def fetch(path):
            try:
                response = opener.open(base + path, timeout=5)
            except HTTPError as error:
                response = error
            with response:
                return response.code, response.headers.get("Content-Type", ""), response.read(2 * 1024 * 1024)

        deadline = time.monotonic() + args.timeout
        while True:
            try:
                if fetch("/api/commerce/runtime")[0] == 200:
                    break
            except (URLError, TimeoutError, ConnectionError):
                pass
            if time.monotonic() >= deadline:
                raise ValueError("Packaged app did not become ready within the timeout")
            time.sleep(1)
        result = check(fetch)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print("Passed six packaged HTTP checks on an isolated database.")
        return 0
    except (ValueError, OSError) as error:
        # Do not print HTTP/JSON response content, cookies or environment data.
        print("Packaged HTTP smoke failed: " + (str(error) if type(error) is ValueError else "request failed"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
