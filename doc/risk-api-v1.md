# Risk evaluation HTTP API

`POST /api/v1/risk-evaluations` with `Content-Type: application/json`.

The HTTP contract uses a non-generic `RiskEvaluationRequest` containing a
`JsonEventEnvelope`. Its `data` field is a JSON object, not JSON encoded inside a
string. Future Kafka/Protobuf input should have its own adapter into the same
internal event model; it does not need to pass through this JSON representation.

## Current implementation

The controller calls `RiskEvaluationCoordinator`, which resolves an `EventAdapter`
by event type and source. The adapter validates the schema version and payload,
then returns an `AdaptedEvent` with the decoded `EventModel` and an immutable
`List<FeatureEvent>`. The decoded event keeps the upstream identity and metadata,
with an `EventPayload` in place of the envelope's JSON data. The coordinator
passes the derived list to `FeatureService.persistEvents` and then to
`FeatureService.queryFeatures`. V1 builds one snapshot using the sender's
`TRANSFER_OUT` leg. The coordinator passes the decoded event and snapshot to
`RuleEngine.evaluate`, then passes the findings to `DecisionEngine.decide`.

The MVP registers `TransferEventAdapter` for `TRANSFER` (`money_movement.transfer`)
from `urn:service:transfers`, schema version 1. It parses `Transfer` and derives
two `TransferLeg` payloads, with internal types `TRANSFER_OUT` and `TRANSFER_IN`.
Internal types use a separate `InternalEventType` enum and cannot be supplied
through the external API. The previous request/completion/confirmation wire
types are no longer accepted.
Multiple adapters claiming the same type/source key fail application startup.
This source registration is routing configuration, not caller authentication.

`ClickHouseFeatureService.queryFeatures` reads the 24-hour outgoing transfer count
and amount from ClickHouse for the OUT leg's account. `persistEvents` is a
placeholder.

Authentication/source authorization, durable idempotency, received-time stamping,
and persistence remain unimplemented.
Authentication/source authorization must precede routing, and idempotency must
precede mutation when those operations are added.

## Example request

Each upstream event represents one transfer submitted for risk evaluation,
with `fromAccountId`, `toAccountId`, `amount`, and `currency`. V1 supports one
sender and one recipient with the same amount and currency; fees, multiple
recipients, and currency conversion require a future payload extension.
Each derived leg contains only its own `accountId`, amount, and currency.
Direction comes from the internal event type, not from the upstream caller.

`amount` is a positive JSON integer in the range 1 through 9223372036854775807,
mapped exactly to Java `Long`. Fractions, numeric strings, and out-of-range values
are rejected. Units follow the upstream currency/asset convention (for example,
10000 represents EUR 100 if the agreed unit is cents); this service performs no
scaling or currency conversion. Producers must preserve integer precision when
serializing JSON, including values beyond JavaScript's safe integer range.
`fromAccountId`, `toAccountId`, and `currency` must be nonblank strings.
The amount is positive for both OUT and IN legs.

The upstream `eventId` is globally unique and must be preserved on retries.
Both internal legs retain it as `sourceEventId`, share `operationId`, and copy
source, schema version, event time, and optional correlation ID. Their internal
`eventId` values are deterministic: `transfer:v1:<base64url(UTF-8 sourceEventId)>:out`
and the corresponding `:in`, using Base64 URL encoding without padding.
For `evt-1`, these are `transfer:v1:ZXZ0LTE:out` and `transfer:v1:ZXZ0LTE:in`.
This scheme must remain stable on retries and replays; direction keeps the IDs
distinct even when both accounts are equal. The HTTP response returns the
original upstream event ID, not either derived ID. `correlationId` groups broader
workflows; it is not a deduplication key.

V1 does not distinguish request from confirmation. History therefore represents
submitted activity, including transfers later blocked or unsuccessful, rather
than completed balance changes. Producers must not send a second `TRANSFER`
event for completion. A retry must preserve the same event ID and input;
`operationId` alone does not deduplicate amount history.

```json
{
  "requestId": "req-001",
  "idempotencyKey": "transfer-001-risk",
  "event": {
    "eventId": "evt-001",
    "operationId": "transfer-001",
    "correlationId": "workflow-001",
    "source": "urn:service:transfers",
    "eventType": "money_movement.transfer",
    "schemaVersion": 1,
    "eventTime": {
      "value": "2026-11-09T12:00:00Z",
      "precision": "SECOND"
    },
    "data": {
      "fromAccountId": "account-A",
      "toAccountId": "account-B",
      "amount": 10000,
      "currency": "EUR"
    }
  }
}
```

`decisionPoint` is optional, reserved, and ignored in V1. `correlationId` is optional.
`eventTime` is required; missing or null values are rejected with HTTP 400.
`source` must be an absolute URI; the string itself
does not authenticate a producer. Received time must eventually be stamped by
the platform, not accepted from the caller.

`eventTime.value` must include an explicit UTC offset, such as
`2026-11-09T12:00:00Z` or `2026-11-09T14:00:00+02:00`. A local timestamp without
an offset is rejected with HTTP 400. The upstream producer owns timezone
resolution; the API does not infer an offset.

## ClickHouse storage contract

The table definition is in `src/main/resources/db/clickhouse/account_asset_movements.sql`.
Apply it explicitly to the intended ClickHouse database; the application does not
execute it at startup. `IF NOT EXISTS` does not migrate an existing table.

`AccountAssetMovementRow` under `feature.persistence.clickhouse.model` maps one
internal `FeatureEvent` to one table row using `from(event, receivedAt)`. It preserves
the derived event ID, upstream `source_event_id`, operation ID, and integer amount. Source and event type
are stored as wire strings. Both timestamps are UTC instants truncated (not rounded)
to milliseconds for `DateTime64(3, 'UTC')`. The caller must supply the platform's
original receipt timestamp and preserve it on delivery retries; the mapper does
not read the clock or substitute receipt time for event time.

The primary index is `(account_id, event_time)` and the replacement/sorting key is
`(account_id, event_time, event_id)`. Retries must preserve all row values. Queries
requiring deduplicated history must use `FINAL` and exclude the current `event_id`.
The table does not reject conflicting content under the same identity.
If a table was created from the earlier DDL, its nullable `counterparty_id` column
may be left in place during rollout; it is no longer read or modeled. Applying
the revised `CREATE TABLE IF NOT EXISTS` will not remove that existing column.
The revised schema also requires `source_event_id String`. Existing installations
need an explicit schema/data migration before writing the new rows. Old confirmed
history cannot simply be relabeled as submitted history; rebuild from original
submissions or retain it separately. No existing database is migrated automatically.

## Transfer history features

The feature service selects the sender's `TRANSFER_OUT` leg and queries submitted
outgoing history (`money_movement.transfer.out`) for that leg's `accountId`.
It aggregates raw upstream units across currencies.

| Feature key | Java value | Meaning |
| --- | --- | --- |
| `transfer-out-count-24h` | `Long` | Distinct `operation_id` values among matching OUT legs. |
| `transfer-out-amount-24h` | `Long` | Sum of matching OUT leg amounts in upstream integer units. |

The window is `[eventTime - 24 hours, eventTime)`, using the event's UTC instant
truncated to milliseconds, consistent with storage. It is an elapsed 24 hours,
not a calendar day or a window anchored to the server clock. Events exactly at
the lower bound are included; all events at or after the upper bound are excluded.
The query also explicitly excludes the current derived OUT `eventId`, without excluding
other legs of the same operation. It does not add the current event's amount.

`AccountAssetMovementRepository` uses `FINAL`, `uniqExact(operation_id)`,
and `sum(toInt128(amount))` in a single parameterized query. Multiple outgoing
legs of one operation contribute once to the count and each contributes to the
amount. Incoming legs and other accounts are excluded. A successful
empty-history query returns zero values. The repository converts both aggregates
to signed 64-bit integers exactly. Failed queries and out-of-range aggregates
mark both feature keys unavailable.
Late arrivals remain subject to the agreed eventual-consistency semantics.

Configure `CLICKHOUSE_JDBC_URL` (default
`jdbc:clickhouse:http://localhost:8123/default`), `CLICKHOUSE_USERNAME` (default
`default`), and `CLICKHOUSE_PASSWORD` (default empty). The URL selects the database;
apply the table DDL there and populate history through an external ingestion path
until event persistence is implemented. The application does not create the table.
`feature.clickhouse.connection-timeout-ms`, `socket-timeout-ms`, and
`query-timeout-seconds` default to 2000, 5000, and 5 respectively.

The optional `ClickHouseTransferHistoryIntegrationTest` runs against a real
ClickHouse when `CLICKHOUSE_TEST_JDBC_URL` is set (plus optional
`CLICKHOUSE_TEST_USERNAME` and `CLICKHOUSE_TEST_PASSWORD`). Use a test database:
the test creates and drops a uniquely named table there. It checks retry
deduplication, distinct operations, filtering, time boundaries, current-event
exclusion, cross-currency aggregation, empty history, and totals exceeding Int64.
Without that URL it is skipped.

## Transfer rules

Rule set `rules-v2` evaluates all three rules independently and reports every
matching rule. Limits use strict `>` comparisons: equality does not match.

| Rule ID / property under `risk.rules` | Environment variable | Default | Evaluated value |
| --- | --- | --- | --- |
| `transfer-out-amount-24h-limit` | `TRANSFER_OUT_AMOUNT_24H_LIMIT` | 500000 | Historical outgoing amount plus the current transfer amount. |
| `transfer-out-count-24h-limit` | `TRANSFER_OUT_COUNT_24H_LIMIT` | 50 | Historical distinct operation count plus one for the current transfer. |
| `transfer-single-amount-limit` | `TRANSFER_SINGLE_AMOUNT_LIMIT` | 100000 | Current transfer amount, independent of history. |

All configured limits must be positive 64-bit integers. Amounts use raw upstream
integer units; no currency scaling or conversion is performed. Both historical
features use the sender's submitted outgoing activity in the window defined above.
The 50th submission is permitted by the count rule; the 51st matches.

Missing, incorrectly typed, or negative history marks its respective rolling rule
`UNAVAILABLE`. The single-transfer rule still evaluates without history. A single
transfer above its limit therefore produces `COMPLETED` / `BLOCK` even when history
cannot be read, following the decision policy below. If nothing matches and a
required rolling rule is unavailable, no recommendation is returned.

Violation reason codes are `TRANSFER_OUT_AMOUNT_24H_LIMIT_EXCEEDED`,
`TRANSFER_OUT_COUNT_24H_LIMIT_EXCEEDED`, and `TRANSFER_SINGLE_AMOUNT_LIMIT_EXCEEDED`.
Unavailable amount/count history uses `TRANSFER_OUT_HISTORY_UNAVAILABLE` and
`TRANSFER_OUT_COUNT_HISTORY_UNAVAILABLE`, respectively.

## Decision policy

Any matched rule means a violation and produces `COMPLETED` with `BLOCK`.
The response includes the matched rule IDs and reason codes. If no rules match
and a required rule is unavailable, the evaluation is `UNAVAILABLE`. An empty
rule evaluation is also `UNAVAILABLE`, with reason `NO_APPLICABLE_RULES`.
When all applicable rules run and none match, the result is `COMPLETED`
with `ALLOW`.

Example HTTP 503 response when transfer history is unavailable.

```json
{
  "requestId": "req-001",
  "decisionId": null,
  "eventId": "evt-001",
  "operationId": "transfer-001",
  "status": "UNAVAILABLE",
  "recommendedAction": null,
  "reasons": [{
    "code": "TRANSFER_OUT_HISTORY_UNAVAILABLE",
    "message": "A required risk rule could not be evaluated.",
    "ruleId": "transfer-out-amount-24h-limit"
  }],
  "ruleSetVersion": null,
  "policyVersion": null,
  "evaluatedAt": null
}
```

## Response contract

| HTTP | Evaluation status | Meaning |
| --- | --- | --- |
| 200 | COMPLETED | The policy reached an ALLOW or BLOCK recommendation. |
| 200 | DEGRADED | Explicit fallback policy produced a recommendation with incomplete evidence. |
| 503 | UNAVAILABLE | No recommendation; decision metadata is null. |
| 400 | Not applicable | Malformed JSON or invalid envelope/request; problem-details body with `code: INVALID_REQUEST`. |

Adapter failures also return HTTP 400 with a problem-details body:

- `UNSUPPORTED_EVENT`: no adapter for the event type/source combination.
- `UNSUPPORTED_SCHEMA_VERSION`: the adapter does not support the version.
- `INVALID_EVENT_DATA`: the transfer payload cannot be converted or fails validation.

An accepted best-effort history read does not itself mean DEGRADED. A failed query
must not be treated as zero history. BLOCK is a successful evaluation, not an HTTP
error. The calling service owns execution and its unavailable/degraded policy.

Reasons contain a stable `code`, a human-readable `message`, and an optional
`ruleId`. Clients should use codes rather than parsing messages. Detailed feature
values and rule evidence belong in the future internal audit record.

Successful responses include a decision ID, rule-set and policy versions, and
evaluation timestamp. The MVP generates a new UUID and timestamp per attempt.
Durable replay will return the original decision with the current attempt's request
ID, return HTTP 409 for conflicting reuse of an idempotency key, and resolve
concurrent retries to the same logical evaluation.

## Run locally

Run `mvn spring-boot:run`. Save the request example as `request.json`, then call:

```powershell
curl.exe -i http://localhost:8080/api/v1/risk-evaluations -H "Content-Type: application/json" --data-binary "@request.json"
```
