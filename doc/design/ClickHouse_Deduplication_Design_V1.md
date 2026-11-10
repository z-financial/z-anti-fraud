# Anti-Fraud V1 — Event Ingestion and ClickHouse Deduplication Design

**Status:** Proposed V1 design  
**Last updated:** 2026-11-10  
**Scope:** Data Sync (DS) ingestion, persistence, duplicate handling, and historical Feature Service reads

## 1. Purpose

The anti-fraud system receives immutable business events (e.g., login, transfer, withdrawal) and stores them in ClickHouse for historical risk-feature queries. The pipeline must tolerate request retries, worker crashes, and uncertain ClickHouse write acknowledgments without allowing duplicate events to inflate risk features.

The proposed design uses:

1. **MySQL Inbox** as the authoritative admission point for event identity, immutable content, and conflicts.
2. **A retryable Inbox worker** to deliver accepted events to ClickHouse.
3. **ClickHouse `ReplacingMergeTree`** to collapse repeated writes of the same immutable event.
4. **`SELECT ... FINAL`** in Feature Service queries to deduplicate before aggregating, including before background merges complete.

**Key separation:** MySQL decides which event payload is accepted; ClickHouse stores and analyzes that accepted payload. This is **idempotent event acceptance plus deduplicated analytical reads**, not a cross-database exactly-once transaction.

## 2. Requirements and assumptions

### Functional requirements

- A business event has a globally stable `event_id` (or a composite identity such as `(source_service, event_id)` if IDs are only source-unique).
- **First successfully committed Inbox acceptance wins.** Later submissions with the same identity and identical immutable payload are idempotent retries.
- The same identity with a different immutable payload is a **conflict**; it must be rejected and logged, not silently overwritten.
- Acknowledged, accepted events are retried until ClickHouse ingestion succeeds or an operational failure requires intervention.
- Feature queries must **count each accepted logical event at most once**, even when ClickHouse contains repeated physical rows from retries.

### Assumptions

- Accepted event payloads are **immutable**; corrections, reversals, or cancellations are modeled as **new events with new IDs** rather than updates to prior events.
- `account_id` and `event_time` cannot change across deliveries of the same event.
- The ClickHouse schema uses the same partition and sorting-key values on every retry.
- V1 anti-fraud decisions are **advisory**; it does not enforce global financial quotas or serialize concurrent Risk Decision (RD) requests.
- Features requiring all prerequisite DS events must wait for the corresponding **ingestion completion/readiness**, rather than merely Inbox admission.

## 3. Architecture

```text
Business Service (stable event_id + immutable payload)
        |
        v
Data Sync API
        |
        v
+---------------------------+
| MySQL Inbox (UNIQUE ID)   |
| - Accept once             |
| - Identical retry: reuse  |
| - Changed payload: reject |
| - Durable pending state   |
+---------------------------+
        |
        v
Inbox Worker (claim / retry / recover)
        |
        v
ClickHouse: ReplacingMergeTree
        |
        v
Feature Service: SELECT ... FINAL
        |
        v
Rule Engine --> Decision Engine --> Advisory risk result
```

**Why two deduplication layers?** The Inbox prevents conflicting event definitions from being accepted. ClickHouse deduplication protects analytical results against **delivery retries after acceptance**, including the crash window between a successful ClickHouse write and a MySQL status update.

## 4. Identity and conflict semantics

Do **not** use `(account_id, event_time)` alone as the business event ID: two legitimate transactions may share the same account and timestamp. Instead:

- **Logical identity:** `event_id` (globally unique), or `(source_service, event_id)` if namespaced.
- **Inbox uniqueness:** a MySQL unique/primary-key constraint on that identity.
- **ClickHouse sorting key:** `(account_id, event_time, event_id)` for the account/time-range access pattern.
- **Conflict definition:** same logical identity but different canonical immutable payload.

| Incoming event | Inbox result | ClickHouse handling |
|---|---|---|
| New event ID | Accept and enqueue for delivery | Insert once or more if retried |
| Same ID, identical payload | Return existing acceptance/status | Do not create new work solely for the retry |
| Same ID, changed payload | Reject and audit conflict | Never forward conflicting version |
| Different event IDs with same account/time | Accept both | Distinct rows because `event_id` differs |

`ReplacingMergeTree` **does not enforce uniqueness on INSERT**. Rows are considered duplicates only when the **complete `ORDER BY` tuple matches**. An explicit `PRIMARY KEY` in ClickHouse, if used, is a sparse query index—not a relational uniqueness constraint.

## 5. MySQL Inbox

### 5.1 Example schema

```sql
CREATE TABLE fraud_event_inbox (
    event_id        VARCHAR(64) PRIMARY KEY,
    payload_hash    BINARY(32) NOT NULL,
    payload         JSON NOT NULL,
    status          ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
                        NOT NULL DEFAULT 'PENDING',
    attempt_count   INT UNSIGNED NOT NULL DEFAULT 0,
    next_retry_at   DATETIME(3) NULL,
    lease_until     DATETIME(3) NULL,
    claim_token     VARCHAR(64) NULL,
    accepted_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at    DATETIME(3) NULL,
    last_error      TEXT NULL
);
```

`payload_hash` is SHA-256 over **canonically serialized immutable fields**. Canonicalization must standardize timestamps, decimal amounts, field ordering, nulls, and any other representation differences. Store the accepted payload itself so a collision, serialization bug, or disputed conflict can be investigated.

If a consumer uses multi-tenant or service-local event IDs, update the primary key and ClickHouse identity consistently.

### 5.2 Atomic API admission algorithm

1. Validate the event and build its canonical immutable payload and hash.
2. `INSERT` into the Inbox in a MySQL transaction; rely on the unique constraint as the concurrency arbiter.
3. On success, commit; return **accepted**, with status `PENDING`.
4. On duplicate-key error, load the existing record and compare canonical content:
   - **Identical:** return the existing event identity and current status (accepted/processing/completed), without adding new work.
   - **Different:** return a conflict (e.g., HTTP `409`) and write an audit/alert record.
5. Never update the authoritative payload of an accepted event as part of retry handling.

**Concurrency guarantee:** If two different payloads race with the same event ID, the one whose **MySQL insert commits successfully** becomes authoritative. This is not necessarily the first network request to arrive.

**API acknowledgment meaning:** `ACCEPTED` means *durably admitted to MySQL*, not *already visible in ClickHouse*.

### 5.3 Worker processing

- Claim a `PENDING` row (or an expired `PROCESSING` lease) atomically, using a lease plus a unique `claim_token`/fencing mechanism.
- Insert the stored, immutable payload into ClickHouse.
- On a confirmed successful write, mark the matching claimed Inbox row `COMPLETED` using the claim token.
- On timeout/unknown outcome, retry with bounded backoff; **do not assume the insert failed**.
- Reclaim abandoned leases after crashes; alert on repeated failures or long `PENDING` durations.
- Use a DLQ/manual review path for poison events. Preserve the original payload and failure reason.

A worker crash can cause duplicate ClickHouse rows, which is expected and handled by the read strategy below. MySQL and ClickHouse do not share an atomic transaction.

## 6. ClickHouse schema and physical deduplication

```sql
CREATE TABLE fraud_events
(
    event_id   String,
    account_id String,
    event_time DateTime64(3, 'UTC'),
    event_type LowCardinality(String),
    amount     Nullable(Decimal(18, 2))
)
ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMM(event_time)
ORDER BY (account_id, event_time, event_id);
```

### 6.1 Why `ReplacingMergeTree()` without `version`?

Events are immutable and the Inbox prevents the admission of distinct payloads under the same event ID. A delivery retry therefore writes **the same row** with the same `ORDER BY` tuple. No version ordering is needed to select a business-authoritative revision.

**Caution:** Without a version column, if conflicting rows somehow bypass the Inbox, ClickHouse may retain a later physical row rather than the original one. That would violate our intended semantics. The Inbox is therefore a **correctness boundary**, not an optional optimization.

### 6.2 What `ReplacingMergeTree` does—and does not do

- Deduplication is determined by the **complete `ORDER BY` tuple**, not automatically by `event_id` alone.
- Background merges may eventually discard duplicate rows within a partition, but timing is nondeterministic and some duplicates may remain physically present.
- A normal `SELECT` can still read both physical rows before a merge.
- Partitioning must be consistent for retries: background merges do not reconcile different partitions.
- Keep `event_time` (and all sorting-key fields) unchanged on retries. A regenerated ingestion timestamp must **not** replace the original event time.

**Data-model note:** If later requirements allow changes to an event's account or timestamp, this `ORDER BY` design is no longer a safe logical deduplication key; redesign the event identity/storage model rather than treating that change as an ordinary retry.

## 7. Correct Feature Service reads with `FINAL`

Use `FINAL` when evaluating features that must not count duplicate physical rows:

```sql
SELECT
    countIf(event_type = 'TRANSFER') AS transfer_count_24h,
    sumIf(amount, event_type = 'TRANSFER') AS transfer_amount_24h,
    countIf(event_type = 'LOGIN') AS login_count_24h
FROM fraud_events FINAL
WHERE account_id = 'U1'
  AND event_time >= toDateTime64('2026-11-09 12:00:00', 3, 'UTC') - INTERVAL 24 HOUR
  AND event_time <  toDateTime64('2026-11-09 12:00:00', 3, 'UTC');
```

`FINAL` applies `ReplacingMergeTree` replacement semantics **at query time**, prior to the aggregates. For example, two physical copies of event E100 with amount 5,000 contribute **one count and 5,000 total**, not two counts and 10,000 total.

### Query rules

- Use `FINAL` on every exact feature query reading this table (or a separately validated deduplicated representation).
- Include a stable `evaluation_time` from the RD workflow, and use consistent time-window boundaries across features.
- Benchmark `FINAL` cost with representative data volume, duplicate rates, partition counts and concurrency.
- Do **not** repeatedly run `OPTIMIZE TABLE ... FINAL` just to make queries correct; forcing physical merges is expensive and not a substitute for `SELECT ... FINAL`.
- Avoid relying on incremental materialized views over raw inserts for exact counts/sums without a separate deduplication-aware design: a view may process inserts before `ReplacingMergeTree` has collapsed duplicates. ClickHouse version-specific insert deduplication improvements do not by themselves establish permanent event-level uniqueness.
- If setting `do_not_merge_across_partitions_select_final = 1` for performance, first prove that all copies of a logical event always belong to the same partition.

## 8. Optional ClickHouse insert deduplication

An additional defense is a **stable `insert_deduplication_token`** for equivalent retry batches (or content-based block deduplication where applicable). This can prevent some duplicate physical writes and reduce the work performed by `FINAL`.

It must **not** be the sole event-level guarantee because deduplication history is bounded, retry batches can change, and settings/defaults depend on the engine, version and synchronous/asynchronous insertion mode.

If using ClickHouse asynchronous inserts, configure and test acknowledgment semantics—for example `wait_for_async_insert = 1`—rather than treating a buffered enqueue acknowledgment as a completed storage write. Check the actual target deployment/version configuration rather than assuming defaults.

## 9. Failure scenarios and expected outcomes

| Scenario | Expected result |
|---|---|
| API retries same ID/payload | Inbox returns existing record; no conflicting event admitted |
| API retries same ID/different amount | MySQL detects conflict; reject and audit |
| Two different IDs have identical account/time | Both are valid; both count in features |
| Worker crashes before ClickHouse INSERT | Inbox lease expires; event is retried |
| ClickHouse writes, but acknowledgment is lost | Worker retries; duplicate physical rows possible; `FINAL` reads one logical event |
| Worker crashes after ClickHouse ACK but before MySQL `COMPLETED` | Inbox recovery retries; same deduplicated query result |
| Background merge has not happened | `SELECT ... FINAL` still deduplicates rows with matching sort keys |
| Same ID is replayed with changed event time | Inbox rejects it; must not be inserted as a new ClickHouse row |
| Required event has not reached ClickHouse yet | RD must not claim complete features; wait/retry or return `NOT_READY`/`INCOMPLETE` per DS/RD protocol |
| Two RDs execute concurrently for one account | May see different admitted histories; no global serializable decision guarantee |

## 10. Consistency guarantees and non-goals

### What this design provides (when assumptions hold)

- **Atomic first-accept-wins conflict detection** at the MySQL API boundary.
- **Durable accepted event payload** with recoverable worker processing.
- **Idempotent analytical representation at query time** for retries that use the same ClickHouse sorting/partition keys and payload.
- **No duplicate inflation** in `FINAL`-based aggregates caused by those repeated physical rows.

### What it does not provide

- An atomic commit across MySQL and ClickHouse.
- Guaranteed immediate visibility on every replica/node in a distributed topology.
- Automatic recovery from an incorrectly accepted payload, bad event identity, or unstable sorting key.
- Permanent correctness of derived incremental aggregates/materialized views without additional design.
- Global ordering or serializable execution among concurrent Risk Decisions.
- Exactly-once delivery to ClickHouse at the physical row level. **At-least-once delivery + deduplicated reads** is the more precise description.

For the planned advisory V1, these limitations are acceptable provided DS readiness is explicit. If a rule is a **hard financial limit**, enforce it in an appropriate transactional/Quota Service rather than relying on these advisory historical reads.

## 11. Implementation checklist

- [ ] Define a unique, stable event identity (and source namespace if needed).
- [ ] Specify immutable canonical payload and SHA-256 hash rules.
- [ ] Create MySQL Inbox unique constraint and conflict audit.
- [ ] Implement race-safe duplicate submission tests.
- [ ] Implement lease-based Inbox worker, retries, error handling, and recovery.
- [ ] Preserve original `account_id`, `event_time`, and `event_id` on every ClickHouse retry.
- [ ] Create ClickHouse `ReplacingMergeTree` table; validate monthly partition choice.
- [ ] Use `FINAL` in all exact historical feature queries.
- [ ] Define DS acknowledgment and RD readiness semantics explicitly.
- [ ] Simulate ACK loss, duplicate physical inserts, crash recovery, and conflicting payloads.
- [ ] Benchmark insert throughput and `FINAL` latency with realistic event volumes.
- [ ] Monitor Inbox backlog, retry count, conflict count, ingestion latency, query latency and error rates.

## 12. References

- ClickHouse, *When to use OPTIMIZE TABLE ... FINAL*: https://clickhouse.com/resources/engineering/clickhouse-optimize-table-final
- ClickHouse, *10 best practice tips — ReplacingMergeTree*: https://clickhouse.com/blog/10-best-practice-tips
- ClickHouse, *Getting started issues — duplicate rows and FINAL*: https://clickhouse.com/blog/common-getting-started-issues-with-clickhouse
- ClickHouse, *Release 26.1 — async insert deduplication and materialized views*: https://clickhouse.com/blog/clickhouse-release-26-01
- ClickHouse, *ClickHouse 22.2 — custom insert deduplication token*: https://clickhouse.com/blog/clickhouse-22-2-released
