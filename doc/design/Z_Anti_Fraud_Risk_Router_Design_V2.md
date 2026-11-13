# Z-Anti-Fraud Risk Router Design V2

The consolidated V2.0 design defines the sync API, final component interfaces, and startup configuration lifecycle. [End-to-end V2 design](Z_Anti_Fraud_End_to_End_Design_V2.md)

## 1. Scope

The Risk Router maps a normalized incoming `EventType` to the canonical rule IDs selected for that event. It runs in the Risk Coordinator process as a read-only lookup over a validated routing snapshot. The router owns rule selection. The Rule Engine owns input validation and rule execution, the Feature Service owns historical queries, and the Decision Engine owns the advisory recommendation.

V2 routes by exact event type. The first route covers transfers and selects the three existing transfer rules. Ingestion has its own API and uses the same event decoder as evaluation.

## 2. Ingestion and evaluation APIs

| Operation | HTTP endpoint | Input |
| --- | --- | --- |
| `ingestEvent` | `POST /api/v2/events` | `JsonEventEnvelope` |
| `evaluateRisk` | `POST /api/v1/risk-evaluations` | `RiskEvaluationRequest` with a `JsonEventEnvelope` and request metadata such as the request ID and idempotency key |

**Ingestion.** Authenticate the caller and authorize the event source. Decode the envelope into an `EventModel`. The event adapter projects it into one or more `FeatureEvent` rows. The sync API writes the rows directly to ClickHouse and returns `200` after write confirmation. Upstream retries a transient failure or lost response with the same envelope. Ingest every submitted transfer, including transfers that receive a `BLOCK` recommendation, to preserve the history measured by the velocity rules. [Sync API and retries](Z_Anti_Fraud_End_to_End_Design_V2.md#22-write-acknowledgment-and-retries)

**Evaluation.** Authenticate the caller, authorize the event source, and decode the envelope into the same `EventModel`. Use that model to select rules, derive feature query scope and the current event identity, and supply current-event facts. Evaluation is read-only with respect to event history. The two paths have separate outputs from decoding and projection, so a combined `AdaptedEvent` wrapper is unnecessary.

## 3. Event and activity identity

Every stored activity carries the identity and type of its incoming event and its own derived identity and type. For a transfer, `money_movement.transfer` decodes to `EventType.TRANSFER`. The adapter produces one activity per leg. An event can have several activities of the same type. The router runs once for `TRANSFER`.

| Java field | ClickHouse column | First outgoing leg | Second outgoing leg | Incoming leg |
| --- | --- | --- | --- | --- |
| `eventId` | `event_id` | `evt-123` | `evt-123` | `evt-123` |
| `eventType` | `event_type` | `TRANSFER` | `TRANSFER` | `TRANSFER` |
| `activityId` | `activity_id` | `evt-123-1` | `evt-123-2` | `evt-123-3` |
| `activityType` | `activity_type` | `TRANSFER_OUT` | `TRANSFER_OUT` | `TRANSFER_IN` |

Both `eventId` and `activityId` are strings. The producer supplies `eventId`, which is unique within its `source`. The adapter derives `activityId` as `eventId + "-" + legNumber`. Use an immutable upstream leg number when available, or assign numbers using a stable order defined by the original event. Each number is unique across all legs of that event. Replaying the same event reproduces the same activity IDs. These strings are directly usable in diagnostics.

An incoming event is identified by `(source, eventId)` and a stored activity by `(source, activityId)`. Include `source` and `activity_id` in the transfer table's ClickHouse sorting key. `FINAL` deduplicates rows that share the full `ORDER BY` tuple, so replay must also preserve the other key values, including account and event time.

The source registry exposes `eventType`, `activityType`, and other allowed fields as feature filters. For example, the 24-hour outgoing transfer group filters on `activityType = TRANSFER_OUT`. The coordinator takes the sender account, the event time at millisecond precision for `asOf`, and the current upstream identity `(source, eventId)` from the `EventModel`. A history query can bind that identity in `AND NOT (source = ? AND event_id = ?)`. This excludes all activities of the current transfer even if they have already been ingested.

Upstream assigns a unique `operationId` per transaction and emits one qualifying transfer submission for it. Retries reuse the same `(source, eventId)`. The historical count uses `COUNT` with `distinct: ["operationId"]`, so multiple outgoing legs contribute one operation. The amount feature sums the qualifying leg amounts. The transfer-count rule adds one for the current transaction after its activities have been excluded from history. `featureId` identifies a configured feature output such as `transfer-out-count-24h`.

## 4. Routing configuration and validation

```json
{
  "version": "routing-v2-1",
  "routes": {
    "TRANSFER": [
      "transfer-out-amount-24h-limit",
      "transfer-out-count-24h-limit",
      "transfer-single-amount-limit"
    ]
  }
}
```

Route keys use names from the normalized business `EventType` enum. Route values contain rule IDs. The DRL rule name is the canonical rule ID. List order provides stable configuration and diagnostics. Drools determines firing order.

At publication, validate the routing JSON and reject duplicate event keys, empty routes, duplicate rule IDs within a route, event types without a registered request adapter, and references to missing or disabled rules. Validate that each routed rule's declared event type supports the route key. At runtime, the routing JSON determines the selected set. The rule's `@eventType` declaration validates compatibility at publication.

`RiskRouter.select(EventType eventType, RoutingSnapshot snapshot)` returns either the selected rule IDs or `NOT_CONFIGURED` for an otherwise valid event with no route. The result includes the normalized event type and routing version. The coordinator handles the missing-route outcome before any history lookup or Drools session creation.

## 5. Coordinator flow

1. Capture one active immutable evaluation bundle. Decode the request and use its normalized `EventType` to select rule IDs from the bundle's routing snapshot.
2. Read the selected rules' feature dependencies from their compiled metadata. Resolve each qualified `groupId.featureId` against the bundle's feature configuration and request the union of those features. Rules with no historical dependencies can run using current-event facts alone.
3. Read available history using the pinned feature configuration, the account scope, one `asOf`, and the current upstream event identity. Upstream waits for sync success when an evaluation depends on a particular prior event. Give the feature query a deadline that leaves time to execute the rules within the request budget.
4. Validate the returned history per selected rule. Missing or invalid history makes only dependent rules `UNAVAILABLE`. Create a `RuleInput` for each selected rule with usable inputs. The shared `KieBase` may contain other rules. Each rule binds its own `RuleInput`, so the session activates only rules with a supplied input. An agenda filter may enforce the same runnable set at firing time.
5. Return the per-rule outcomes to the Decision Engine. The existing policy returns `COMPLETED / BLOCK` for any match, including a current-event match while a history rule is unavailable. If every applicable rule completes without a match, it returns `COMPLETED / ALLOW`. If required inputs are unavailable and no rule matches, it returns `UNAVAILABLE`.

### Open item for future alignment

The shared evaluation interface between the coordinator, Feature Service, and Rule Engine is open. Align the request and result types, how the upstream event identity and pinned feature configuration cross the component boundaries, and where feature values and errors are adapted into rule inputs. The workflow above records the agreed behavior. [Feature Service design](Z_Anti_Fraud_Feature_Service_Design_V2.md) · [Rule Engine design](Z_Anti_Fraud_Rule_Engine_Design_V2.md)

## 6. Publication and failure handling

Publish routing, compiled DRL, rule parameters, and feature configuration as one compatible immutable bundle. Validate every route against the candidate rule catalog and every rule dependency against the candidate feature snapshot. A missing qualified dependency, including one whose feature moved to another group, rejects publication with `FEATURE_NOT_FOUND` and identifies the affected rule and reference. Activate or roll back the full bundle with one local atomic switch. In-flight requests use the bundle they captured.

| Condition | Coordinator behavior |
| --- | --- |
| Invalid envelope or unsupported event type and source | Use the existing HTTP validation error contract. |
| Valid event with no configured route | Return HTTP `503` with `UNAVAILABLE` and reason `NOT_CONFIGURED`. |
| History query fails | Run the selected rules with usable inputs and let the Decision Engine apply its policy to the outcomes. |
| Drools execution fails | Report an execution error and apply the Rule Engine's unavailable-outcome contract. |

Record the captured routing, rule bundle, parameter, and feature versions, normalized event type, selected rule IDs, matched rule IDs, and evaluation status. Trace feature and rule latency separately. Keep raw financial and personal values out of routine logs.

## 7. Acceptance criteria

- One transfer envelope is routed once as `TRANSFER` and selects the three existing transfer rules. Ingestion produces one row per leg with a shared `eventId` and distinct `activityId` values. Replaying a transfer with two outgoing legs preserves the activity IDs and the full sorting key, so `FINAL` returns one row per leg.
- History can filter on `eventType` and `activityType`. When the current event is already stored, the lookup excludes all rows for its `(source, eventId)` and retains eligible rows from a different source with the same local `eventId`.
- Two historical outgoing legs of one transaction contribute one operation to the count and both leg amounts to the sum. The count rule adds the current transaction once.
- Evaluation uses the decoded `EventModel` and reads history without projecting or persisting activities. A transfer that receives `BLOCK` is eligible for ingestion as submitted activity.
- Only selected rules with usable inputs receive a `RuleInput` and can produce findings. The two existing historical transfer features share one SQL statement when requested together.
- A feature outage leaves the rolling rules unavailable. An oversized current transfer can still match the single-transfer rule and produce `BLOCK`.
- An unconfigured route returns `NOT_CONFIGURED`. An invalid route or moved feature reference rejects the candidate bundle. Activation or rollback during evaluation leaves the in-flight request on its captured routing, rule, parameter, and feature versions.
