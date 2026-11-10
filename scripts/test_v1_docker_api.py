#!/usr/bin/env python3
"""Seed namespaced fixtures and test the existing local Docker API without reconfiguring it."""

import argparse
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

import generate_v1_test_data as generator


ROOT = Path(__file__).resolve().parents[1]


def docker(*args, input_text=None):
    result = subprocess.run(["docker", "compose", *args], cwd=ROOT, input=input_text,
                            text=True, encoding="utf-8", capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "Docker command failed")
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", default="http://127.0.0.1:8080/api/v1/risk-evaluations")
    parser.add_argument("--output", type=Path, default=ROOT / "target/docker-v1-test")
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.api_url)
    if parsed.hostname not in ("localhost", "127.0.0.1", "::1") or parsed.scheme != "http":
        parser.error("api-url must be a local HTTP endpoint")
    args.output.mkdir(parents=True, exist_ok=True)
    log_lines = []

    def log(line):
        print(line, flush=True)
        log_lines.append(line)
        (args.output / "test.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")

    try:
        run_id = "docker-v1-" + uuid.uuid4().hex[:12]
        fixture_dir = args.output / run_id
        clock = datetime(2026, 11, 10, 12, tzinfo=timezone.utc)
        manifest = generator.generate(fixture_dir, run_id, clock)
        log(f"Endpoint: {args.api_url}")
        log(f"Fixture namespace: {run_id}; expected limits: amount=500000 count=50 single=100000")
        env_names = ("TRANSFER_OUT_AMOUNT_24H_LIMIT", "TRANSFER_OUT_COUNT_24H_LIMIT",
                     "TRANSFER_SINGLE_AMOUNT_LIMIT")
        limits = dict(zip(env_names, docker("exec", "-T", "app", "printenv", *env_names).splitlines()))
        for name, limit in limits.items():
            log(f"Deployed {name}={limit}")
        log("Application configuration unchanged; fixture accounts are unique to this run.")
        client = ['exec', '-T', 'clickhouse', 'sh', '-c',
                  'exec clickhouse-client --user "$CLICKHOUSE_USER" '
                  '--password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DB" --multiquery']
        sql = (fixture_dir / "schema.sql").read_text(encoding="utf-8") + "\n"
        sql += (fixture_dir / "seed.sql").read_text(encoding="utf-8")
        docker(*client, input_text=sql)
        log(f"Seed: inserted {manifest['historyRowCount']} movement rows, "
            "including intentional duplicate deliveries.")
        requests = [json.loads(line) for line in (fixture_dir / "requests.jsonl").read_text().splitlines()]
        expected = [json.loads(line) for line in (fixture_dir / "expected.jsonl").read_text().splitlines()]
        # Verify actual ClickHouse totals before evaluating API behavior.
        checks = []
        for payload in requests:
            sender = payload['event']['data']['fromAccountId']
            checks.append("SELECT sum(toInt128(amount)), uniqExact(operation_id) "
                          "FROM account_asset_movements FINAL "
                          f"WHERE account_id = '{sender}' "
                          "AND event_type = 'money_movement.transfer.out' "
                          "AND event_time >= toDateTime64('2026-11-09 12:00:00', 3, 'UTC') "
                          "AND event_time < toDateTime64('2026-11-10 12:00:00', 3, 'UTC');")
        totals = docker(*client, input_text="\n".join(checks)).splitlines()
        if len(totals) != len(expected):
            raise RuntimeError("Unexpected number of ClickHouse feature-query results")
        for want, total in zip(expected, totals):
            actual_amount, actual_count = map(int, total.split("\t"))
            history = want['expectedHistory']
            if (actual_amount, actual_count) != (history['transfer-out-amount-24h'], history['transfer-out-count-24h']):
                raise RuntimeError(f"History mismatch for {want['caseId']}: {total}")
        log(f"History verification: PASS {len(totals)}/{len(expected)} ClickHouse amount/count queries")
        results = []
        # Do not send loopback test requests through a configured external HTTP proxy.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for payload, want in zip(requests, expected):
            started = time.perf_counter()
            req = urllib.request.Request(args.api_url, json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
            try:
                try:
                    response = opener.open(req, timeout=15)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    status = response.code
                    body = json.loads(response.read().decode())
                matched = sorted({reason.get("ruleId") for reason in body.get("reasons", [])
                                  if reason.get("ruleId")})
                actual = {"httpStatus": status, "status": body.get("status"),
                          "action": body.get("recommendedAction"), "matchedRuleIds": matched}
                passed = (status == want["expectedHttpStatus"]
                          and body.get("status") == want["expectedStatus"]
                          and body.get("recommendedAction") == want["expectedAction"]
                          and matched == sorted(want["expectedMatchedRuleIds"])
                          and body.get("requestId") == payload["requestId"]
                          and body.get("eventId") == payload["event"]["eventId"])
            except (OSError, ValueError) as error:
                body, actual, passed = None, {"error": str(error)}, False
            elapsed = round((time.perf_counter() - started) * 1000, 1)
            result = {"caseId": want["caseId"], "passed": passed, "elapsedMs": elapsed,
                      "expected": want, "actual": actual, "response": body}
            results.append(result)
            expected_rules = ",".join(want['expectedMatchedRuleIds']) or "none"
            actual_rules = ",".join(actual.get('matchedRuleIds', [])) or "none"
            log(f"{'PASS' if passed else 'FAIL'} {want['caseId']}: "
                f"expected={want['expectedAction']} [{expected_rules}] "
                f"actual={actual.get('action', 'ERROR')} [{actual_rules}] "
                f"HTTP={actual.get('httpStatus', '-')} ({elapsed} ms)")
        passed = sum(r['passed'] for r in results)
        summary = {"apiUrl": args.api_url, "runId": run_id,
                   "deployedLimits": limits, "historyChecksPassed": len(totals),
                   "passed": passed, "failed": len(results) - passed, "total": len(results),
                   "results": results}
        (args.output / "results.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        log(f"RESULT: {passed}/{len(results)} passed; {len(results) - passed} failed.")
        log(f"Saved detailed responses: {(args.output / 'results.json').resolve()}")
        return 0 if passed == len(results) else 1
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        log(f"TEST ERROR: {error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
