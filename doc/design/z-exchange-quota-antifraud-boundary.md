# Architecture Decision: Separate Quota Service from Anti-Fraud

**Status:** Accepted  
**Project:** Z-Exchange / Z-Anti-Fraud  
**Decision date:** 2026-11-06

## Decision

Introduce a **dedicated Quota Service** in the **Z-Exchange business domain** to enforce mandatory limits. Keep **Z-Anti-Fraud** focused on identifying suspicious activity and returning risk decisions. The Order Service orchestrates both checks before executing an operation.

## Responsibility boundaries

| Component | Responsibility | Data and consistency |
|---|---|---|
| **Quota Service** | Enforce hard business limits, e.g. daily transfer total ≤ 100,000; manage quota policies, reservations, usage and releases | Authoritative transactional store (e.g. MySQL); atomic, durable updates |
| **Z-Anti-Fraud** | Evaluate suspicious behavior using features, rules and decision policies, e.g. >20 transfers/hour or >50,000 transferred/hour | Historical events in ClickHouse; best-effort/eventually consistent statistical features |
| **Order Service** | Coordinate risk evaluation, quota reservation and the actual business operation | Orchestration, idempotency and recovery |
| **Wallet / Ledger** | Execute and record financial movements | Authoritative balances and financial records |

## Request flow

```text
Client
  |
  v
Order Service (orchestrator)
  |----> Z-Anti-Fraud: risk evaluation (ALLOW / REVIEW / REJECT)
  |----> Quota Service: atomic reservation of mandatory quota
  |
  +----> Wallet / Ledger: execute only when required checks pass
  |
  +----> Quota Service: commit on success / release on definitive failure
```

Risk and quota checks are **logically independent**. Their physical order may be optimized to avoid unnecessary work; do not hold a database transaction open while waiting for remote calls.

## Quota Service contract (initial scope)

- `ReserveQuota(operationId, subject, limitType, amount, period)`: atomically reserve available quota or reject the request.
- `CommitQuota(operationId)`: finalize reserved usage after successful execution.
- `ReleaseQuota(operationId)`: return a reservation after **confirmed** failure or cancellation.
- `GetQuotaUsage(subject, limitType, period)`: read consumed, reserved and available quota.

**Correctness requirements:** stable operation IDs; idempotent reserve/commit/release; safe concurrent requests; durable reservation records; recovery and reconciliation for unknown or pending outcomes; defined currencies, periods and timezone semantics. If the mandatory quota cannot be verified or reserved, **do not execute** the operation.

## Why separate them?

1. **Different purposes:** quota rules guarantee business invariants; anti-fraud rules detect potentially suspicious behavior.
2. **Different consistency needs:** hard limits require transactional concurrency control, while historical risk signals may tolerate limited ingestion delay.
3. **Clear ownership:** the business domain owns authoritative quota usage, rather than relying on ClickHouse-based event aggregates.
4. **Shared reuse:** transfers, withdrawals and other order types can enforce consistent limits through one service.

## Implementation notes

- Start with a daily transfer quota in the dedicated Quota Service, backed by MySQL (or an equivalently transactional store).
- Keep Z-Anti-Fraud V1's synchronous **Feature Service → hard-coded Rule Engine → Decision Engine** workflow. Its historical event store can use ClickHouse.
- A suspicious-but-permitted transfer may trigger `REVIEW` or additional verification even if it fits the daily hard limit; a transfer that exceeds quota is rejected regardless of its fraud score.
- Anti-Fraud may **recommend** limit adjustments, but must not directly modify Quota Service storage. Authorized policy changes go through Quota Service APIs.

## Trade-offs

A separate service introduces an extra network dependency and distributed workflow complexity. In particular, reserving quota and executing a transfer across services is **not** one local ACID transaction: the Order Service must handle retries, timeouts, partial failures and reconciliation explicitly.
