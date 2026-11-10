# Risk evaluation HTTP API

`POST /api/v1/risk-evaluations` with `Content-Type: application/json`.

The HTTP contract uses a non-generic `RiskEvaluationRequest` containing a
`JsonEventEnvelope`. Its `data` field is a JSON object, not JSON encoded inside a
string. Future Kafka/Protobuf input should have its own adapter into the same
internal event model; it does not need to pass through this JSON representation.

## Current implementation

The controller calls `RiskEvaluationCoordinator`, which resolves an `EventAdapter`
by event type and source. The adapter validates the schema version and payload,
then builds a transport-independent `EventModel`. The coordinator passes that
model to `FeatureService`; adapters do not query features.

The MVP registers `TransferRequestedEventAdapter` for
`money_movement.transfer.requested` from `urn:service:transfers`, schema version 1.
Multiple adapters claiming the same type/source key fail application startup.
This source registration is routing configuration, not caller authentication.

`EmptyFeatureService` computes no values and marks any requested features as
unavailable. Until rule selection is implemented, the coordinator requests an
empty feature set. Valid supported events return HTTP 503 with
`EVALUATION_NOT_CONFIGURED`; no event is ingested and no decision is issued.

Authentication/source authorization, durable idempotency, received-time stamping,
feature computation, rules, decision policy, and persistence remain unimplemented.
Authentication/source authorization must precede routing, and idempotency must
precede mutation when those operations are added.

## Example request

The minimal transfer schema requires a positive decimal string `amount` and a
nonblank string `currency`. Numeric JSON amounts are rejected to avoid
floating-point rounding before conversion to `BigDecimal`. Account identifiers and
additional transfer attributes are outside this MVP payload.

```json
{
  "requestId": "req-001",
  "idempotencyKey": "transfer-001-risk",
  "event": {
    "eventId": "evt-001",
    "operationId": "transfer-001",
    "correlationId": "workflow-001",
    "source": "urn:service:transfers",
    "eventType": "money_movement.transfer.requested",
    "schemaVersion": 1,
    "eventTime": {
      "value": "2026-11-09T12:00:00Z",
      "precision": "SECOND"
    },
    "data": {
      "amount": "100.00",
      "currency": "EUR"
    }
  }
}
```

`decisionPoint` is optional, reserved, and ignored in V1. `correlationId` and
`eventTime` are also optional. `source` must be an absolute URI; the string itself
does not authenticate a producer. Received time must eventually be stamped by
the platform, not accepted from the caller.

When supplied, `eventTime.value` must include an explicit UTC offset, such as
`2026-11-09T12:00:00Z` or `2026-11-09T14:00:00+02:00`. A local timestamp without
an offset is rejected with HTTP 400. The upstream producer owns timezone
resolution; the API does not infer an offset.

## Current response

HTTP 503:

```json
{
  "requestId": "req-001",
  "decisionId": null,
  "eventId": "evt-001",
  "operationId": "transfer-001",
  "status": "UNAVAILABLE",
  "recommendedAction": null,
  "reasons": [{
    "code": "EVALUATION_NOT_CONFIGURED",
    "message": "The risk evaluation engine is not configured.",
    "ruleId": null
  }],
  "ruleSetVersion": null,
  "policyVersion": null,
  "evaluatedAt": null
}
```

## Response contract

| HTTP | Evaluation status | Meaning |
| --- | --- | --- |
| 200 | COMPLETED | Required evidence evaluated; recommendation is ALLOW or BLOCK. |
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

When decision evaluation is implemented, successful responses must include a stored
decision ID, rule-set and policy versions, and evaluation timestamp. Retries must
return the original decision with the current attempt's request ID. Conflicting
reuse of an idempotency key must return HTTP 409; this is not implemented by the
MVP coordinator. Concurrent retries must resolve to the same logical evaluation.

## Run locally

Run `mvn spring-boot:run`. Save the request example as `request.json`, then call:

```powershell
curl.exe -i http://localhost:8080/api/v1/risk-evaluations -H "Content-Type: application/json" --data-binary "@request.json"
```
