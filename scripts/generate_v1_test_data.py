#!/usr/bin/env python3
"""Generate deterministic, offline V1 transfer fixtures. Requires Python 3.10+."""

import argparse
import base64
import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path


AMOUNT_RULE = "transfer-out-amount-24h-limit"
COUNT_RULE = "transfer-out-count-24h-limit"
SINGLE_RULE = "transfer-single-amount-limit"
SOURCE = "urn:service:transfers"
LIMITS = {AMOUNT_RULE: 500_000, COUNT_RULE: 50, SINGLE_RULE: 100_000}
FILES = ("history.jsonl", "requests.jsonl", "expected.jsonl", "manifest.json",
         "schema.sql", "seed.sql")


@dataclass(frozen=True)
class Transfer:
    event_id: str
    operation_id: str
    sender: str
    recipient: str
    amount: int
    time: datetime


@dataclass
class Scenario:
    case_id: str
    description: str
    current: Transfer
    expected_amount: int
    expected_count: int
    matched_rules: tuple[str, ...]
    history: list[Transfer] = field(default_factory=list)


def iso_time(value: datetime) -> str:
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def build_scenarios(run_id: str, as_of: datetime) -> list[Scenario]:
    scenarios = []

    def case(name, description, amounts, current, rules=()):
        prefix = f"{run_id}:{name}"
        sender = f"{prefix}:sender"
        candidate = Transfer(f"{prefix}:current", f"{prefix}:current-op",
                             sender, f"{prefix}:recipient", current, as_of)
        history = [Transfer(
            f"{prefix}:history-{i:03d}", f"{prefix}:operation-{i:03d}", sender,
            f"{prefix}:recipient-{i:03d}", amount,
            as_of - timedelta(minutes=i + 1),
        ) for i, amount in enumerate(amounts)]
        scenario = Scenario(name, description, candidate, sum(amounts),
                            len(amounts), tuple(rules), history)
        scenarios.append(scenario)
        return scenario

    case("empty-history", "New sender; current submission counts once.", [], 1)
    for suffix, current, rules in (
        ("below", 49_999, ()), ("equal", 50_000, ()),
        ("above", 50_001, (AMOUNT_RULE,)),
    ):
        case(f"amount-{suffix}", "Only the 24-hour amount boundary varies.",
             [90_000] * 5, current, rules)
    for suffix, count, rules in (
        ("below", 48, ()), ("equal", 49, ()),
        ("above", 50, (COUNT_RULE,)),
    ):
        case(f"count-{suffix}", "Only the projected distinct-operation count varies.",
             [1_000] * count, 1_000, rules)
    for suffix, amount, rules in (
        ("below", 99_999, ()), ("equal", 100_000, ()),
        ("above", 100_001, (SINGLE_RULE,)),
    ):
        case(f"single-{suffix}", "Only the single-transfer amount boundary varies.",
             [], amount, rules)

    case("amount-and-count", "Both rolling limits exceed their thresholds.",
         [10_000] * 50, 1, (AMOUNT_RULE, COUNT_RULE))
    case("amount-and-single", "Rolling amount and single-transfer limits match.",
         [90_000] * 5, 100_001, (AMOUNT_RULE, SINGLE_RULE))
    case("count-and-single", "Count and single-transfer limits match.",
         [1_000] * 50, 100_001, (COUNT_RULE, SINGLE_RULE))
    case("all-three", "All three rule IDs must be reported.",
         [9_000] * 50, 100_001, (AMOUNT_RULE, COUNT_RULE, SINGLE_RULE))

    boundary = case("cutoff-included", "History exactly 24 hours old is included.",
                    [90_000] * 5, 1, (AMOUNT_RULE,))
    extra = Transfer(f"{run_id}:cutoff-included:edge", f"{run_id}:cutoff-included:edge-op",
                     boundary.current.sender, boundary.current.recipient, 50_000,
                     as_of - timedelta(hours=24))
    boundary.history.append(extra)
    boundary.expected_amount += extra.amount
    boundary.expected_count += 1

    for name, offset in (
        ("cutoff-excluded", -timedelta(hours=24, milliseconds=1)),
        ("same-time-excluded", timedelta(0)),
        ("future-excluded", timedelta(milliseconds=1)),
    ):
        scenario = case(name, "Out-of-window history must not affect the decision.",
                        [90_000] * 5, 50_000)
        scenario.history.append(Transfer(
            f"{run_id}:{name}:noise", f"{run_id}:{name}:noise-op",
            scenario.current.sender, scenario.current.recipient, 1, as_of + offset))

    for name, amounts, current in (
        ("duplicate-amount", [90_000] * 5, 50_000),
        ("duplicate-count", [1_000] * 49, 1_000),
    ):
        scenario = case(name, "Identical redelivery must not inflate history.", amounts, current)
        scenario.history.append(scenario.history[-1])

    incoming = case("incoming-excluded", "Incoming transfers are not outgoing history.",
                    [90_000] * 5, 50_000)
    incoming.history.append(Transfer(
        f"{run_id}:incoming:noise", f"{run_id}:incoming:noise-op",
        f"{run_id}:incoming:external", incoming.current.sender,
        1, as_of - timedelta(seconds=1)))
    other = case("other-account-excluded", "Other senders do not affect this sender.",
                 [90_000] * 5, 50_000)
    other.history.append(Transfer(
        f"{run_id}:other:noise", f"{run_id}:other:noise-op", f"{run_id}:other:sender",
        other.current.recipient, 1, as_of - timedelta(seconds=1)))
    return scenarios


def history_totals(scenario: Scenario) -> tuple[int, int]:
    """Independent check against declared scenario totals; identical deliveries deduplicate."""
    unique = {transfer.event_id: transfer for transfer in scenario.history}
    eligible = [transfer for transfer in unique.values()
                if transfer.sender == scenario.current.sender
                and scenario.current.time - timedelta(hours=24) <= transfer.time < scenario.current.time
                and transfer.event_id != scenario.current.event_id]
    return sum(transfer.amount for transfer in eligible), len({t.operation_id for t in eligible})


def validate(scenarios: list[Scenario]) -> None:
    for scenario in scenarios:
        amount, count = history_totals(scenario)
        if (amount, count) != (scenario.expected_amount, scenario.expected_count):
            raise ValueError(f"Incorrect history expectations: {scenario.case_id}")
        matched = tuple(rule for rule, value in (
            (AMOUNT_RULE, amount + scenario.current.amount),
            (COUNT_RULE, count + 1), (SINGLE_RULE, scenario.current.amount),
        ) if value > LIMITS[rule])
        if matched != scenario.matched_rules:
            raise ValueError(f"Incorrect rule expectations: {scenario.case_id}")


def request(transfer: Transfer) -> dict:
    return {
        "requestId": f"request:{transfer.event_id}",
        "idempotencyKey": f"risk:{transfer.event_id}",
        "event": {
            "eventId": transfer.event_id, "operationId": transfer.operation_id,
            "source": SOURCE, "eventType": "money_movement.transfer", "schemaVersion": 1,
            "eventTime": {"value": iso_time(transfer.time), "precision": "MILLISECOND"},
            "data": {"fromAccountId": transfer.sender, "toAccountId": transfer.recipient,
                     "amount": transfer.amount, "currency": "EUR"},
        },
    }


def movement_rows(transfer: Transfer) -> list[dict]:
    encoded = base64.urlsafe_b64encode(transfer.event_id.encode()).decode().rstrip("=")
    timestamp = transfer.time.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    return [{
        "event_id": f"transfer:v1:{encoded}:{direction}",
        "source_event_id": transfer.event_id, "operation_id": transfer.operation_id,
        "correlation_id": None, "source": SOURCE,
        "event_type": f"money_movement.transfer.{direction}", "schema_version": 1,
        "account_id": account, "amount": transfer.amount, "currency": "EUR",
        "event_time": timestamp, "received_at": timestamp,
    } for direction, account in (("out", transfer.sender), ("in", transfer.recipient))]


def sql_value(column: str, value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    literal = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    return f"toDateTime64({literal}, 3, 'UTC')" if column in ("event_time", "received_at") else literal


def write_jsonl(path: Path, records: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in records),
                    encoding="utf-8", newline="\n")


def generate(output: Path, run_id: str, as_of: datetime, overwrite: bool = False) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", run_id):
        raise ValueError("run-id must contain 1-64 letters, digits, underscores or hyphens")
    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as-of must include Z or a UTC offset")
    as_of = as_of.astimezone(timezone.utc)
    if as_of.microsecond % 1000:
        raise ValueError("as-of must have at most millisecond precision")
    scenarios = build_scenarios(run_id, as_of)
    validate(scenarios)
    if not overwrite and any((output / name).exists() for name in FILES):
        raise ValueError("Output files already exist; use --overwrite to regenerate them")
    schema = (Path(__file__).resolve().parents[1] / "src/main/resources/db/clickhouse"
              / "account_asset_movements.sql").read_text(encoding="utf-8")
    rows = [row for scenario in scenarios for transfer in scenario.history
            for row in movement_rows(transfer)]
    expected = [{
        "caseId": s.case_id, "description": s.description,
        "requestId": f"request:{s.current.event_id}", "eventId": s.current.event_id,
        "expectedHistory": {"transfer-out-amount-24h": s.expected_amount,
                            "transfer-out-count-24h": s.expected_count},
        "expectedProjectedAmount24h": s.expected_amount + s.current.amount,
        "expectedProjectedCount24h": s.expected_count + 1,
        "expectedHttpStatus": 200, "expectedStatus": "COMPLETED",
        "expectedAction": "BLOCK" if s.matched_rules else "ALLOW",
        "expectedMatchedRuleIds": list(s.matched_rules),
    } for s in scenarios]
    manifest = {
        "generatorVersion": 1, "runId": run_id, "asOf": iso_time(as_of),
        "scenarioCount": len(scenarios), "historyRowCount": len(rows),
        "currency": "EUR", "amountUnit": "raw upstream integer units; no conversion",
        "thresholds": LIMITS, "window": "[asOf - 24 hours, asOf)",
        "includeCurrentInProjectedTotals": True,
        "historySemantics": "submitted transfers, including blocked or unsuccessful submissions",
        "ruleSetVersion": "rules-v2",
        "requirements": [
            "Use a dedicated test database, initially empty, and load history once.",
            "Set the application's 24-hour amount limit to 500000.",
            "Set the count limit to 50 and single-transfer amount limit to 100000.",
            "Seed history externally: current API persistence is a placeholder.",
            "Expect policy outcomes, not confirmed-fraud labels; compare rule IDs as a set.",
        ],
    }
    columns = list(rows[0])
    sql = "-- Load once into a dedicated test database after schema.sql. No deletes or drops.\n"
    sql += "INSERT INTO account_asset_movements (" + ", ".join(columns) + ") VALUES\n"
    sql += ",\n".join("(" + ", ".join(sql_value(c, row[c]) for c in columns) + ")"
                       for row in rows) + ";\n"
    output.mkdir(parents=True, exist_ok=True)
    write_jsonl(output / "history.jsonl", rows)
    write_jsonl(output / "requests.jsonl", [request(s.current) for s in scenarios])
    write_jsonl(output / "expected.jsonl", expected)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "schema.sql").write_text(schema, encoding="utf-8")
    (output / "seed.sql").write_text(sql, encoding="utf-8", newline="\n")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("target/synthetic-v1"))
    parser.add_argument("--run-id", default="synthetic-v1",
                        help="Namespace for IDs; change when loading another run into the same database")
    parser.add_argument("--as-of", default="2026-11-10T12:00:00.000Z",
                        help="Fixed evaluation timestamp with explicit offset, at millisecond precision")
    parser.add_argument("--overwrite", action="store_true", help="Replace the six generated files")
    args = parser.parse_args()
    try:
        as_of = datetime.fromisoformat(args.as_of.replace("Z", "+00:00"))
        manifest = generate(args.output, args.run_id, as_of, args.overwrite)
    except (ValueError, OSError) as error:
        parser.exit(2, f"error: {error}\n")
    print(f"Generated {manifest['scenarioCount']} cases and {manifest['historyRowCount']} "
          f"history rows in {args.output.resolve()}")
    print("Expected results target rules-v2 with limits: amount=500000, count=50, single=100000.")


if __name__ == "__main__":
    main()
