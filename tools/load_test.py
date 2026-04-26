#!/usr/bin/env python3
"""Small dependency-free HTTP load tester for Comatching.

This is intentionally modest: it is good for smoke/ramp checks from a dev
machine, while k6/Gatling are still better for long formal performance tests.
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Tuple


MATCHING_BODY = {
    "ageOption": None,
    "mbtiOption": None,
    "hobbyOption": None,
    "contactFrequency": None,
    "sameMajorOption": False,
    "importantOption": None,
    "minAgeOffset": None,
    "maxAgeOffset": None,
}


@dataclass(frozen=True)
class Endpoint:
    name: str
    method: str
    path: str
    body: Optional[dict] = None
    auth_required: bool = False


@dataclass
class Sample:
    name: str
    status: int
    ms: float
    error: str = ""


SCENARIOS: Dict[str, List[Endpoint]] = {
    "public": [
        Endpoint("participants", "GET", "/api/auth/participants"),
    ],
    "read": [
        Endpoint("participants", "GET", "/api/auth/participants"),
        Endpoint("items", "GET", "/api/items", auth_required=True),
        Endpoint("matching_history", "GET", "/api/matching/history", auth_required=True),
    ],
    "matching": [
        Endpoint("matching", "POST", "/api/matching", MATCHING_BODY, auth_required=True),
    ],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a small Comatching load test.")
    parser.add_argument("--base-url", required=True, help="Gateway base URL, e.g. http://1.2.3.4:8080")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS), default="public")
    parser.add_argument("--vus", type=int, default=10, help="Number of concurrent virtual users")
    parser.add_argument("--duration", type=int, default=60, help="Duration in seconds")
    parser.add_argument("--iterations", type=int, help="Total request count. Overrides duration when set")
    parser.add_argument("--think-time", type=float, default=1.0, help="Sleep seconds between each VU iteration")
    parser.add_argument("--timeout", type=float, default=5.0, help="Per-request timeout seconds")
    parser.add_argument("--token", help="Single access token to send as a cookie")
    parser.add_argument("--tokens-file", help="File with one access token per line")
    parser.add_argument("--cookie-name", default="accessToken")
    parser.add_argument("--header", action="append", default=[], help="Extra header, format: Name: value")
    parser.add_argument(
        "--allow-write",
        action="store_true",
        help="Required for write scenarios such as matching",
    )
    return parser.parse_args()


def load_tokens(args: argparse.Namespace) -> List[str]:
    tokens: List[str] = []
    if args.token:
        tokens.append(args.token.strip())
    if args.tokens_file:
        with open(args.tokens_file, "r", encoding="utf-8") as token_file:
            tokens.extend(line.strip() for line in token_file if line.strip() and not line.startswith("#"))
    return tokens


def parse_headers(raw_headers: Iterable[str]) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    for raw in raw_headers:
        if ":" not in raw:
            raise ValueError(f"Invalid --header value: {raw!r}. Expected 'Name: value'.")
        name, value = raw.split(":", 1)
        headers[name.strip()] = value.strip()
    return headers


def build_request(
    base_url: str,
    endpoint: Endpoint,
    headers: Dict[str, str],
    tokens: List[str],
    cookie_name: str,
    token_override: Optional[str] = None,
) -> urllib.request.Request:
    url = urllib.parse.urljoin(base_url.rstrip("/") + "/", endpoint.path.lstrip("/"))
    request_headers = dict(headers)
    data = None

    if endpoint.auth_required:
        token = token_override or (random.choice(tokens) if tokens else None)
        if token:
            request_headers["Cookie"] = f"{cookie_name}={token}"

    if endpoint.body is not None:
        data = json.dumps(endpoint.body).encode("utf-8")
        request_headers.setdefault("Content-Type", "application/json")

    return urllib.request.Request(url, data=data, headers=request_headers, method=endpoint.method)


def send_once(
    base_url: str,
    endpoint: Endpoint,
    headers: Dict[str, str],
    tokens: List[str],
    cookie_name: str,
    timeout: float,
    request_index: int = 0,
) -> Sample:
    started = time.perf_counter()
    status = 0
    error = ""
    try:
        token_override = tokens[request_index % len(tokens)] if tokens else None
        request = build_request(base_url, endpoint, headers, tokens, cookie_name, token_override)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            response.read()
    except urllib.error.HTTPError as exc:
        status = exc.code
        error = exc.reason or "http_error"
        try:
            exc.read()
        except Exception:
            pass
    except Exception as exc:
        error = type(exc).__name__
    elapsed_ms = (time.perf_counter() - started) * 1000
    return Sample(endpoint.name, status, elapsed_ms, error)


def worker(
    worker_id: int,
    args: argparse.Namespace,
    endpoints: List[Endpoint],
    headers: Dict[str, str],
    tokens: List[str],
    stop_at: float,
    samples: List[Sample],
    lock: threading.Lock,
    counter: Dict[str, int],
) -> None:
    index = worker_id % len(endpoints)
    while True:
        with lock:
            if args.iterations is not None:
                if counter["sent"] >= args.iterations:
                    return
                request_index = counter["sent"]
                counter["sent"] += 1
            else:
                request_index = counter["sent"]
                counter["sent"] += 1

        if args.iterations is None and time.perf_counter() >= stop_at:
            return

        endpoint = endpoints[index % len(endpoints)]
        sample = send_once(
            args.base_url,
            endpoint,
            headers,
            tokens,
            args.cookie_name,
            args.timeout,
            request_index,
        )
        with lock:
            samples.append(sample)
        index += 1
        if args.think_time > 0:
            time.sleep(args.think_time)


def percentile(values: List[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct))
    return ordered[index]


def summarize(samples: List[Sample], duration: float) -> None:
    total = len(samples)
    ok = [sample for sample in samples if 200 <= sample.status < 400]
    failed = total - len(ok)
    latencies = [sample.ms for sample in samples]
    print()
    print("=== Load test summary ===")
    print(f"requests: {total}")
    print(f"duration: {duration:.1f}s")
    print(f"throughput: {(total / duration if duration > 0 else 0):.2f} req/s")
    print(f"failures: {failed} ({(failed / total * 100 if total else 0):.2f}%)")
    print(f"latency avg: {(statistics.mean(latencies) if latencies else 0):.1f} ms")
    print(f"latency p50: {percentile(latencies, 0.50):.1f} ms")
    print(f"latency p95: {percentile(latencies, 0.95):.1f} ms")
    print(f"latency max: {(max(latencies) if latencies else 0):.1f} ms")
    print()
    print("by endpoint:")
    for name in sorted({sample.name for sample in samples}):
        group = [sample for sample in samples if sample.name == name]
        group_latencies = [sample.ms for sample in group]
        group_failed = len([sample for sample in group if not 200 <= sample.status < 400])
        print(
            f"  {name}: count={len(group)} fail={group_failed} "
            f"p95={percentile(group_latencies, 0.95):.1f}ms"
        )
    print()
    print("status counts:")
    counts: Dict[Tuple[int, str], int] = {}
    for sample in samples:
        key = (sample.status, sample.error)
        counts[key] = counts.get(key, 0) + 1
    for (status, error), count in sorted(counts.items(), key=lambda item: (item[0][0], item[0][1])):
        label = str(status) if status else error
        if status and error:
            label = f"{status} {error}"
        print(f"  {label}: {count}")


def main() -> int:
    args = parse_args()
    endpoints = SCENARIOS[args.scenario]
    writes = any(endpoint.method not in {"GET", "HEAD", "OPTIONS"} for endpoint in endpoints)
    if writes and not args.allow_write:
        print(f"Scenario {args.scenario!r} sends write requests. Re-run with --allow-write to confirm.")
        return 2

    tokens = load_tokens(args)
    if any(endpoint.auth_required for endpoint in endpoints) and not tokens:
        print(f"Scenario {args.scenario!r} requires --token or --tokens-file.")
        return 2

    headers = parse_headers(args.header)
    samples: List[Sample] = []
    lock = threading.Lock()
    stop_at = time.perf_counter() + args.duration
    counter = {"sent": 0}
    started = time.perf_counter()
    run_limit = f"iterations={args.iterations}" if args.iterations is not None else f"duration={args.duration}s"
    print(
        f"Running scenario={args.scenario} base_url={args.base_url} "
        f"vus={args.vus} {run_limit}"
    )

    with ThreadPoolExecutor(max_workers=args.vus) as executor:
        futures = [
            executor.submit(worker, i, args, endpoints, headers, tokens, stop_at, samples, lock, counter)
            for i in range(args.vus)
        ]
        for future in futures:
            future.result()

    elapsed = time.perf_counter() - started
    summarize(samples, elapsed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
