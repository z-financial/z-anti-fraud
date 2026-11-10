# Z-Anti-Fraud — Asynchronous Ingestion & Decision Freshness (V1)

## 1. Goal

Balance fraud-detection accuracy, latency, and throughput without globally serializing events or decisions. **Z-Anti-Fraud is advisory**: it evaluates suspicious activity and returns recommendations. **Z-Exchange** owns final execution decisions; a separate **Quota Service** enforces strict financial limits using authoritative transactional state.

## 2. Two Request Paths

### A. Event synchronization (asynchronous)

1. Business services publish events through a durable mechanism (preferably transactional outbox → Kafka).
2. Partition events by a defined business-context key (initially `userId`) to preserve ordering within that stream.
3. An Anti-Fraud consumer writes events into **ClickHouse** for historical feature queries.
4. A **Progress Tracker** marks a checkpoint ready only after the corresponding events are query-visible to the Feature Service.

An ingestion acknowledgment means *accepted for processing*, **not** necessarily available for risk queries. Use stable event IDs and idempotent ingestion/replay.

### B. Risk decision (synchronous)

The Risk Gateway receives the current event and an optional dependency checkpoint, then invokes Feature Service → Rule Engine → Decision Engine when the request's freshness condition is satisfied.

| Mode | Behavior | Suitable for |
|---|---|---|
| `BEST_EFFORT` | Evaluate immediately using available history; report freshness/availability. | Normal or low-risk checks |
| `REQUIRE_EVENTS` | Evaluate only when specified prior event checkpoints are query-visible; otherwise wait briefly or return `NOT_READY`. | Sensitive checks requiring known preceding events |

**Decisions are not serialized in V1.** Requests with satisfied dependencies can evaluate concurrently, including requests for the same user. A checkpoint guarantees only specified historical event visibility; it does **not** guarantee visibility of earlier or concurrent risk decisions, their business outcomes, or all concurrent events.

## 3. Decision Responses

| Status | Meaning | Caller action |
|---|---|---|
| `READY` | Evaluation completed under requested freshness condition. | Apply recommendation and business policies. |
| `NOT_READY` | Required preceding events are still processing. | Retry after backoff or wait within deadline. |
| `INCOMPLETE` | Deadline expired without confirming required visibility. | Apply explicit fallback policy; do not assume zero risk. |
| `SYNC_FAILED` | Known ingestion failure or unrecoverable gap. | Initiate repair/replay and alert. |

**Do not automatically resend all events on timeout.** Check processing status first; replay only missing/failed events, idempotently.

## 4. Proposed V1 Components

```text
Z-Exchange services
   ├─ Event publication → Kafka → Event Consumer → ClickHouse
   │                                └─ Progress Tracker (query-visible checkpoints)
   └─ Risk request → Risk Gateway (freshness mode + checkpoint)
                       → Feature Service (current event + ClickHouse history)
                       → Rule Engine (hard-coded V1 rules)
                       → Decision Engine (advisory outcome)

Z-Exchange Order/Wallet + Quota Service → authoritative execution and hard limits
```

- **ClickHouse:** historical events; on-demand counts, sums, distinct counts, and last-event queries.
- **Progress Tracker:** durable/recoverable, contiguous per-stream progress; not just Kafka-consumed offsets. Track ClickHouse write acknowledgment and actual visibility on the read path.
- **Feature Service:** attach freshness/completeness metadata; distinguish `UNKNOWN`/unavailable from a genuine zero. Include the current request event directly, avoiding unnecessary write-and-read-back and double counting.
- **Rule/Decision Engines:** apply V1 hard-coded detection logic; produce recommendations rather than transactional guarantees.

## 5. Concurrent Decisions: Explicit V1 Limitation

Two risk decisions for the same account may overlap:

```text
DC1 → DC2 → DC3 → RD1
DC4 → DC5 → DC6 → RD2
```

`RD2` may require `DC4–DC6` to be query-visible before evaluation. However, this **does not require RD2 to wait for RD1 or observe its result**. Both decisions can use overlapping historical state, potentially missing combined suspicious activity (false negatives). Even if RD1 finishes first, RD2 sees its outcome only if that outcome is published as an event and is covered by RD2's dependency checkpoint.

**V1 consistency contract:** Z-Anti-Fraud guarantees requested historical event visibility within the defined dependency scope, **not serializable or sequential risk decisions per user/account**. This is acceptable because its results are advisory; the separate Quota Service still enforces strict limits.

Per-context decision serialization or risk-state reservations are **postponed** unless measured false negatives justify the added coordination cost.

## 6. Scope, Risks, and Evaluation

**V1 scope:** primarily user-scoped dependencies; two freshness modes; bounded wait/retry; observable ingestion lag, query latency, and missing-feature rates.

**Known limitations:** delayed or out-of-order publication, duplicate events, multi-topic/multi-entity dependencies, and concurrent same-account decisions can still produce incomplete risk assessments. A query-visible checkpoint is not a hard financial constraint; strict quotas remain in the Quota Service.

**Measure:** decision p95/p99 latency, throughput, event visibility lag, `NOT_READY`/`INCOMPLETE` rates, detection differences between `BEST_EFFORT` and `REQUIRE_EVENTS`, and false-negative rates under concurrent versus sequential evaluations.

**Future:** consider optional per-context risk-decision serialization, multi-entity dependency tracking, and asynchronous feature aggregation/Redis online features only if measurements justify them.
