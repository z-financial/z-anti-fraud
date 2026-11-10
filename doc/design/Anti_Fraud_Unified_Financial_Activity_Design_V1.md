# Anti-Fraud V1 — Unified Financial Activity Event Design

**Status:** Proposed V1 design  
**Scope:** Normalising financial activity into a shared ClickHouse analytical table  
**Related design:** `ClickHouse_Deduplication_Design_V1.md` (MySQL Inbox + `ReplacingMergeTree` + `FINAL`)

## 1. Objective

Provide the Anti-Fraud Feature Service with a consistent, query-friendly representation of different financial operations (transfers, combined transfers, top-ups, withdrawals, reservations, releases, etc.) without forcing every business event to use the same detailed payload schema.

**Decision:** Keep a **common event envelope and typed business payloads** at the API boundary, but normalise relevant financial movements into a shared ClickHouse table, `account_activity_events`. Each logical activity **leg** has a stable, unique `event_id`; all legs belonging to the same business operation share an `operation_id`.

This table is **analytical history**, not the authoritative wallet/ledger or the source of truth for balances.

## 2. Core Identifiers and Concepts

| Concept | Definition | Example |
| --- | --- | --- |
| `operation_id` | Stable ID for one business operation, potentially comprising multiple legs and lifecycle events | `OP100` |
| `event_id` | Stable ID for one logical, immutable activity event/leg; remains unchanged across retries | `E101` |
| `operation_type` | Business operation category | `COMBINED_TRANSFER`, `TOPUP`, `WITHDRAWAL` |
| `event_type` | The movement or lifecycle action represented by **this** event | `TRANSFER_OUT`, `TRANSFER_IN`, `RESERVE`, `RELEASE`, `CONSUME` |
| `account_id` | Account whose activity the leg represents | `A1` |
| `reservation_id` | Stable reference identifying a particular reservation, where relevant | `R100` |
| `counterparty_id` | Other account or counterparty when applicable; optional | `A2` |

### Identity rules

1. One business operation may generate **many events** with the same `operation_id`.
2. Every distinct logical leg must have a **different `event_id`**. A retry of that same leg must reuse its original `event_id`.
3. Do **not** use `(account_id, event_time)` as a unique business identifier: multiple legitimate events can share a timestamp.
4. The Inbox enforces event identity and immutable payload consistency. A repeated `event_id` with the **same** payload is an idempotent retry; the **same** ID with a different payload is a conflict and must be rejected/alerted.
5. If one source event is deterministically expanded into several analytical rows, assign **stable and distinct output event IDs**, e.g. from `(source_event_id, leg_id)`. Never generate fresh random IDs on each retry.

## 3. Examples

### 3.1 Combined transfer: one outflow, multiple inflows

Account `A` sends 300 EUR, with 100 EUR to `B` and 200 EUR to `C`.

| event_id | operation_id | account_id | event_type | amount | counterparty_id |
| --- | --- | --- | --- | ---: | --- |
| `E101` | `OP100` | `A` | `TRANSFER_OUT` | 300 | `NULL` |
| `E102` | `OP100` | `B` | `TRANSFER_IN` | 100 | `A` |
| `E103` | `OP100` | `C` | `TRANSFER_IN` | 200 | `A` |

All rows have `operation_type = COMBINED_TRANSFER` and `currency = EUR`.

**Interpretation:**

- Outgoing amount for `A` = 300, from its `TRANSFER_OUT` leg.
- Incoming amount for `B` = 100; for `C` = 200, from their `TRANSFER_IN` legs.
- Business transfer operation count for `A` = **one**, using distinct `operation_id` for qualifying outgoing legs.
- **Do not sum every leg of `OP100` to calculate transferred value**: 300 + 100 + 200 = 600 double-counts the same movement.
- The outbound header leg records the sender's aggregate outflow. For recipient-level analysis, inspect incoming legs; if sender-to-recipient relationships are required, include an explicit recipient-leg reference or a separate mapping rather than guessing.

### 3.2 Reservation lifecycle

A reservation is created, then cancelled and released:

| event_id | operation_id | reservation_id | account_id | event_type | amount |
| --- | --- | --- | --- | --- | ---: |
| `E201` | `OP200` | `R200` | `A` | `RESERVE` | 300 |
| `E202` | `OP200` | `R200` | `A` | `RELEASE` | 300 |

- `RESERVE` represents funds made unavailable; it is **not automatically completed spending**.
- `RELEASE` reverses a hold; it is **not new income**.
- If a reservation is consumed, represent consumption with a separate `CONSUME` activity as appropriate for the wallet's business semantics. **Do not double-count `CONSUME` and `TRANSFER_OUT`** when computing outgoing transfer totals.
- Multiple reservations under one operation must have different `reservation_id` values.
- Separate event rows preserve the historical sequence; do not overwrite the original reservation event.

### 3.3 Other business operations

- **Top-up:** Can map to a `TOPUP`/`CREDIT` analytical activity on the receiving account, with payment-provider details in a typed source payload.
- **Withdrawal:** Can map to `WITHDRAWAL`/`DEBIT` activity, with payout-specific fields in the source payload.
- **Authentication:** Uses a **separate analytical table** (e.g. `authentication_events`) because logins/device/IP history have different schemas and query patterns.

Keep the mapping from detailed source event types to analytical `event_type` explicit and versioned. The names above are examples: choose canonical vocabulary before implementation and avoid using both `CREDIT` and `TOPUP` interchangeably without a mapping rule.

## 4. Model Boundaries and Architecture

```text
Business Services (Wallet / Order / Payment / Auth)
          |
          |  common envelope + typed business payload
          v
     Data Sync API
          |
          v
 MySQL Inbox (unique event identity, payload hash, status)
          |
          v
 Ingestion Worker + Type-specific Normalisers
          |
          +---- financial events ----> ClickHouse: account_activity_events
          |
          +---- login/auth events ---> ClickHouse: authentication_events
                                          |
                                          v
                                  Feature Service
                                          |
                                          v
                              Rule Engine -> Decision Engine
```

### Responsibilities

| Component | Responsibilities |
| --- | --- |
| Source business service | Generate immutable events and stable `operation_id` / `event_id`; define the actual business meaning of each leg |
| Data Sync API / MySQL Inbox | Atomic first-accepted identity; compare payload hashes/content on retries; persist accepted payload for recovery |
| Normaliser / ingestion worker | Validate schema, map typed payloads to canonical analytical legs, route writes, retry safely and track completion |
| ClickHouse | Store time-series financial activities and perform aggregations |
| Feature Service | Query historical movements; combine financial and authentication features; avoid double-counting current events |
| Rule Engine | Evaluate configured/hard-coded suspicious activity rules; not responsible for financial quota enforcement |

## 5. Initial ClickHouse Schema

```sql
CREATE TABLE account_activity_events
(
    event_id String,
    operation_id String,
    account_id String,

    operation_type LowCardinality(String),
    event_type LowCardinality(String),

    amount Decimal(18, 2),
    currency LowCardinality(String),

    reservation_id Nullable(String),
    counterparty_id Nullable(String),

    event_time DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMM(event_time)
ORDER BY (account_id, event_time, event_id);
```

### Design notes

- `amount` is a **non-negative magnitude** in the specified currency; direction and lifecycle meaning are indicated by `event_type`. Do not assume a uniform signed-balance interpretation.
- `ORDER BY (account_id, event_time, event_id)` matches frequent account/time-window feature queries. **It is not a unique constraint.**
- `ReplacingMergeTree()` collapses rows with the same complete sorting key during background merges. `SELECT ... FINAL` provides read-time deduplication for queries that must count each accepted logical event once.
- No version column is necessary **only because the source event/normalised leg is immutable**. A correction should be a new, explicitly linked event; conflicting payloads under the same `event_id` must be blocked by the Inbox.
- Retries must preserve `account_id`, `event_time`, `event_id`, and partition membership. Otherwise, rows may not deduplicate as intended.
- Monthly partitions are an initial retention/management choice; validate with real event volume and retention requirements.
- `operation_id` and `reservation_id` are **correlation identifiers**, not ClickHouse uniqueness or transactional guarantees.
- The schema intentionally omits operation-specific metadata that is not needed for current rules. Add focused columns or supplementary tables only when queries require them.

## 6. Example Feature Queries

Use a single evaluation timestamp/window across a risk decision. Below, parameters such as `{accountId:String}` are illustrative ClickHouse query parameters.

### 6.1 Outgoing transfer count and amount in 24 hours

```sql
SELECT
    uniqExactIf(operation_id, event_type = 'TRANSFER_OUT')
        AS transfer_operation_count_24h,
    countIf(event_type = 'TRANSFER_OUT')
        AS outgoing_leg_count_24h,
    sumIf(amount, event_type = 'TRANSFER_OUT')
        AS outgoing_amount_24h
FROM account_activity_events FINAL
WHERE account_id = {accountId:String}
  AND currency = {currency:String}
  AND event_time >= {startTime:DateTime64(3, 'UTC')}
  AND event_time < {endTime:DateTime64(3, 'UTC')};
```

**Semantics:** `uniqExactIf(operation_id, ...)` counts qualifying business operations; `countIf` counts qualifying legs. One combined transfer normally counts as one outgoing operation. If the producer emits **several** `TRANSFER_OUT` legs for one operation, counts and sums must be defined accordingly.

### 6.2 Incoming amount in 24 hours

```sql
SELECT sumIf(amount, event_type = 'TRANSFER_IN') AS incoming_amount_24h
FROM account_activity_events FINAL
WHERE account_id = {accountId:String}
  AND currency = {currency:String}
  AND event_time >= {startTime:DateTime64(3, 'UTC')}
  AND event_time < {endTime:DateTime64(3, 'UTC')};
```

### 6.3 Reservation and release activity

```sql
SELECT
    countIf(event_type = 'RESERVE') AS reserve_count_24h,
    sumIf(amount, event_type = 'RESERVE') AS reserve_amount_24h,
    countIf(event_type = 'RELEASE') AS release_count_24h
FROM account_activity_events FINAL
WHERE account_id = {accountId:String}
  AND currency = {currency:String}
  AND event_time >= {startTime:DateTime64(3, 'UTC')}
  AND event_time < {endTime:DateTime64(3, 'UTC')};
```

**Important:** Counts of reserves and releases are *activity indicators*, not balance calculations. Net reserved funds cannot be computed safely by a naive sum without handling partial releases, consumption, lifecycle semantics, and the full relevant event history.

### Query semantics checklist

- Use `event_time` for business-time analysis; if needed, add separate `received_at` for ingestion-lag analysis.
- Define whether the current decision's event is already included in the historical query. Do not add it twice.
- Define whether a feature counts *attempts*, *successful operations*, *settled transfers*, or *wallet movements*; event names alone do not establish this.
- Filter or normalise currency before summing amounts; never aggregate EUR and USD as though they share units.
- `FINAL` has performance overhead and must be benchmarked at realistic volume.

## 7. Idempotency, Consistency, and Failure Handling

Continue using the previously agreed deduplication model:

1. **MySQL Inbox:** `UNIQUE(event_id)` (or `(source_service, event_id)` if producer-scoped IDs), immutable payload + canonical hash. Same ID and same payload = accepted retry; same ID and different payload = conflict.
2. **Recoverable worker:** Keep `PENDING`, `PROCESSING`, and `COMPLETED` states with a lease/timeout or equivalent recovery path. Retries produce the **same analytical leg IDs and sorting keys**.
3. **ClickHouse:** `ReplacingMergeTree` absorbs repeated copies of the same logical leg eventually; `FINAL` avoids duplicate counting before merges complete.
4. **Ingestion acknowledgement:** Mark a source event completed only after all required ClickHouse writes have been acknowledged using appropriate synchronous/wait-for-flush settings.
5. **Risk Decision:** Where the decision relies on the synced event, only proceed when the relevant Data Sync completion and query visibility conditions are met. An acknowledged event does **not** make all other legs of its operation automatically complete.

### Important limits

- MySQL Inbox + ClickHouse do **not** form one atomic distributed transaction. The design provides **idempotent acceptance and deduplicated analytical reads**, not end-to-end exactly-once execution.
- Multi-leg, multi-account operations may be partially visible during ingestion; define a business-level `operation_complete` signal or equivalent expected-leg tracking **only when rules actually need whole-operation completeness**.
- Concurrent Risk Decisions are not serialised by this architecture. The Feature Service supplies advisory risk signals; hard quotas stay with the business-side quota enforcement mechanism.
- `ReplacingMergeTree` deduplicates by the **full `ORDER BY` key**, not by `event_id` alone. Keep keys stable, and do not use `ReplacingMergeTree` to resolve conflicting values.
- Future materialized/aggregate views need their **own deduplication-aware design**. `FINAL` on the source table does not retroactively repair aggregates computed from duplicate inserts.

## 8. V1 Scope and Open Decisions

### Implement in V1

- One unified Data Sync endpoint with an event envelope and typed payloads.
- One MySQL Inbox enforcing event identity and detecting conflicting retries.
- A type-aware normalisation layer and one ClickHouse `account_activity_events` table for financial activity.
- Stable `operation_id` across related legs and stable `event_id` per leg.
- `ReplacingMergeTree` + `FINAL` for deduplicated historical feature queries.
- Initial rules: outgoing transfer count/amount; incoming amount; reserve/release count/amount.
- Separate authentication activity storage when login/device rules are introduced.

### Decide and document before implementation

1. **Event taxonomy:** exact mapping of wallet actions (`RESERVE`, `CONSUME`, `TRANSFER_OUT`, `TRANSFER_IN`, `RELEASE`) and top-up/withdrawal events to canonical analytical event types.
2. **Attempt vs settled:** which stage each suspicious-activity rule should measure.
3. **Combined transfer encoding:** one aggregate `TRANSFER_OUT` for sender + per-recipient `TRANSFER_IN`, or another explicit rule; keep it consistent to prevent double counting.
4. **Identity generation:** whether IDs originate from the ledger/business service or are deterministically derived by normalisers.
5. **Completion semantics:** when an individual event is considered queryable and when a multi-leg operation is considered complete.
6. **Currency and timestamp standards:** currency-specific amounts, precision, UTC event time and evaluation-time boundaries.
7. **Benchmarking:** `FINAL` query latency and insertion throughput with representative account distributions, retry rates, and event volumes.

## 9. Summary

> **One business operation (`operation_id`) can have many immutable analytical legs (`event_id`).** Normalise domain-specific financial payloads into one account-activity table for common features, while retaining the original typed payload in the source/Inbox. MySQL establishes event identity; ClickHouse stores and analyses the accepted activity history. Explicit feature semantics prevent double-counting transfers, reservations and releases.
