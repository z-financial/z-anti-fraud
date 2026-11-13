# Z-Anti-Fraud Feature Service Design V2

The consolidated V2.0 design defines the sync API, final component interfaces, and startup configuration lifecycle. [End-to-end V2 design](Z_Anti_Fraud_End_to_End_Design_V2.md)

## 1. Scope

The Feature Service reads account-scoped history from ClickHouse and returns named feature values to the Rule Engine. Configuration authors define query groups in JSON. Each selected group produces one parameterized SQL statement and one aggregate result row. A group can return event counts, distinct counts, and numeric aggregates together.

V2 keeps the existing transfer rules and their feature IDs. The sync API owns event ingestion. The first release supports equality filters and scalar aggregates. Joins, derived expressions, automatic regrouping, approximate counts, and precomputed windows are deferred.

## 2. Request and result

The risk coordinator supplies the account, history cutoff, feature IDs required by the selected rules, and the current upstream event identity `(source, eventId)`. Feature evaluation uses the feature configuration pinned by the coordinator's evaluation bundle and the remaining query deadline. Results carry feature values and per-feature errors, the feature configuration version used, and `asOf`.

Every requested feature ID appears in exactly one of `values` and `errors`. A `null` value represents a successful query with no observation for `MIN`, `MAX`, or `AVG`. Zero is a valid result for `COUNT` and `SUM`. Preserve these distinctions in the result and when passing it to the Rule Engine. A missing definition produces an error for that ID. A failed or timed-out statement produces an error for each requested feature in its group. A value conversion failure affects the corresponding feature.

The request uses one `asOf` for all groups. For transfers, the coordinator uses the timestamp from the decoded `EventModel` at millisecond precision. A group covers `[asOf - window, asOf)`. A `DAY` is exactly 24 hours.

The exact Java request and result types, how the pinned configuration is supplied, and where results are adapted into rule inputs remain open for alignment across the three designs. [Open evaluation interface](Z_Anti_Fraud_Risk_Router_Design_V2.md#open-item-for-future-alignment)

## 3. Feature configuration

A query group defines its source, window, time unit, and optional shared filters. A feature defines its ID, aggregate operation, and optional feature filters. `field` supplies the operand for a numeric or comparable aggregate. `distinct` supplies the key for a feature-level `COUNT`.

The first group below uses the V2 transfer schema and the existing feature IDs. The login group illustrates an additional source once its table and mappings are registered.

```json
{
  "version": "feature-config-v2-1",
  "queryGroups": [
    {
      "id": "transfer-out-24h",
      "source": "account-asset-movement",
      "window": 24,
      "timeUnit": "HOUR",
      "filters": { "activityType": "TRANSFER_OUT" },
      "features": [
        {
          "id": "transfer-out-count-24h",
          "operation": "COUNT",
          "distinct": ["operationId"]
        },
        {
          "id": "transfer-out-amount-24h",
          "operation": "SUM",
          "field": "amountMinor"
        },
        {
          "id": "transfer-out-eur-count-24h",
          "operation": "COUNT",
          "distinct": ["operationId"],
          "filters": { "currency": "EUR" }
        }
      ]
    },
    {
      "id": "transfer-out-7d",
      "source": "account-asset-movement",
      "window": 7,
      "timeUnit": "DAY",
      "filters": { "activityType": "TRANSFER_OUT" },
      "features": [
        {
          "id": "transfer-out-amount-7d",
          "operation": "SUM",
          "field": "amountMinor"
        }
      ]
    },
    {
      "id": "login-24h",
      "source": "user-event",
      "window": 24,
      "timeUnit": "HOUR",
      "filters": { "eventType": "LOGIN_COMPLETED" },
      "features": [
        { "id": "login-count-24h", "operation": "COUNT" }
      ]
    }
  ]
}
```

### Operations and distinct keys

| Operation | Configuration | ClickHouse expression | Java result |
| --- | --- | --- | --- |
| `COUNT` | Omit `field`. `distinct` is optional. | `count()` or `countIf(...)` for rows. `uniqExact(...)` or `uniqExactIf(...)` for keys. | Checked `Long` |
| `SUM` | Numeric `field`. | `sum(...)` or `sumIf(...)`, with an accumulator appropriate to the source type. | Checked `Long` for integral fields, `BigDecimal` for decimal fields, `Double` for floating-point fields |
| `MIN`, `MAX` | Comparable `field`. | `min(...)` or `max(...)` with conditional and empty-input handling. | Registered field type or `null` |
| `AVG` | Numeric `field`. | `avg(...)` with conditional and empty-input handling. | `Double` or `null` |

`distinct` is accepted only on `COUNT` features. When present, it is a non-empty array of unique, registered, non-nullable logical fields. The fields form one composite key. For example, `["source", "operationId"]` counts unique source and operation ID pairs when IDs are scoped to the producer. The operation enum contains `COUNT`, `SUM`, `MIN`, `MAX`, and `AVG`.

Account scope, time bounds, current-event exclusion, and group filters define the input rows for every feature in the group. A feature filter limits only that feature's inputs. For a filtered distinct count, a key contributes once if any eligible row with that key satisfies the feature filter. In the example, the EUR condition applies to the EUR count. The total count and amount still use all rows in the shared scope. Two qualifying legs for the same operation with amounts of 100 and 200 contribute one operation and an amount of 300.

### Publication validation

- Reject unknown configuration properties, duplicate JSON keys, duplicate group or feature IDs, unknown sources and fields, and unsupported operations. Group IDs and feature IDs are each unique within the version.
- Require at least one feature per group and a positive window with a supported unit (`MINUTE`, `HOUR`, or `DAY`).
- Resolve filters as typed equality predicates joined with `AND`. Accept logical enum names from the registry. Reject null filter values and values incompatible with the field type.
- Validate each operation's operand and result type. Reject `distinct` at group level, `distinct` on other operations, and empty, duplicate, unknown, nullable, or unsupported key fields.
- At bundle publication, verify that each rule's qualified feature reference resolves within its declared group and has a compatible result type. A failed validation leaves the active version in place.

## 4. Source registry

Spring Boot connection settings provide named ClickHouse data sources. Credentials come from the deployment environment or secret management. Java annotations register the logical source, database reference, physical table, account scope, event time, event ID, and field mappings. Enum mappings use the application's stored wire values. Startup builds an immutable registry and validates that sources, identifiers, required fields, and enum mappings are consistent. The registry supplies column types, nullability, and whether reads require `FINAL`.

The `account-asset-movement` source registers the `account_asset_movements` table with the V2 mappings below.

| Logical field | Physical column | Use |
| --- | --- | --- |
| `accountId` | `account_id` | Account scope |
| `eventTime` | `event_time` | UTC event time, `DateTime64(3)` |
| `eventId` | `event_id` | Upstream event ID as a string, unique within the producer source |
| `eventType` | `event_type` | Incoming business event type, such as `TRANSFER` |
| `activityId` | `activity_id` | Derived leg ID as a string, formed from the event ID and stable leg number |
| `activityType` | `activity_type` | Stored activity type, such as `TRANSFER_OUT` |
| `operationId` | `operation_id` | Business operation ID |
| `amountMinor` | `amount` | Integral minor units |
| `currency` | `currency` | Currency code |
| `source` | `source` | Event producer |

The source requires `FINAL` to collapse redeliveries. Its `ReplacingMergeTree` sorting key includes `source` and `activity_id`, and replay preserves all sorting-key values, including account and event time. This gives the aggregates one input row per logical leg. Feature-level `distinct` then counts keys across the qualifying legs. [Event and activity identity](Z_Anti_Fraud_Risk_Router_Design_V2.md#3-event-and-activity-identity)

## 5. SQL generation and execution

1. Use the immutable feature configuration pinned by the request's evaluation bundle for group selection, SQL generation, result mapping, and the reported configuration version. Publishing and rollback atomically replace the active bundle. In-flight evaluations continue on their captured bundle.
2. Select the groups that contain requested features and project those features. If the request has no matching IDs, return without querying ClickHouse.
3. For each selected group, build its shared `WHERE` clause and one aggregate expression per selected feature. Apply feature filters through conditional aggregate expressions. The generator uses registered identifiers and safely quoted aliases. Bind all request and filter values with JDBC parameters.
4. Execute one statement per selected group with bounded concurrency and the remaining deadline. Map its single result row into `values` and `errors`. An independent group can still return results if another group fails.

### Current request exclusion

The coordinator gets `(source, eventId)` from the decoded current event. For history sources that store the upstream identity, bind it to `AND NOT (source = ? AND event_id = ?)`. This excludes every stored leg of that event and preserves rows from other producers with the same local `eventId`. The Rule Engine receives the current event separately.

### Transfer example

For the three features in `transfer-out-24h`, the generated statement has this shape.

```sql
SELECT
    uniqExact(operation_id) AS `transfer-out-count-24h`,
    coalesce(sum(toInt128(amount)), toInt128(0)) AS `transfer-out-amount-24h`,
    uniqExactIf(operation_id, currency = ?) AS `transfer-out-eur-count-24h`
FROM account_asset_movements FINAL
WHERE account_id = ?
  AND activity_type = ?
  AND event_time >= fromUnixTimestamp64Milli(?, 'UTC')
  AND event_time < fromUnixTimestamp64Milli(?, 'UTC')
  AND NOT (source = ? AND event_id = ?)
```

Bind `EUR`, the account ID, the registered stored value for `TRANSFER_OUT`, the start and end instants in epoch milliseconds, the event producer, and the upstream event ID in that order. The existing transfer rules request the first two features and get their history from one statement.

## 6. Rule compatibility and freshness

- `transfer-out-count-24h` counts distinct `operationId` values among qualifying outgoing legs. Upstream supplies one qualifying submission per transaction, so the rule can add the current transaction once after excluding its activities from history. `transfer-out-amount-24h` sums the existing `amount` values among qualifying legs using a widened integer accumulator and checked conversion, preserving the current rule's inputs and currency behavior. Both features return `Long`, including zero on empty history.
- Nullable aggregate operands contribute only when they have a value. A conditional `MIN`, `MAX`, or `AVG` returns `null` when there are no qualifying non-null operands, including when the group itself has rows. Overflow and invalid result conversions surface as errors.
- Queries use the history available on the ClickHouse node that handles sync writes. Upstream can wait for a prior event's sync success before requesting a dependent evaluation. The Rule Engine and Decision Engine apply their established unavailable-feature policy to query failures. [Sync API and retries](Z_Anti_Fraud_End_to_End_Design_V2.md#22-write-acknowledgment-and-retries)

## 7. Delivery and acceptance

Implement the registry and publication validation first, followed by SQL generation and execution. Migrate the existing transfer path using its current feature IDs and compare results against the existing repository before cutover.

| Case | Expected result |
| --- | --- |
| Request the two existing 24-hour transfer features | One statement returns the same count and sum as V1 for equivalent history. |
| Request all five example features with both sources registered | Three statements execute. The 24-hour transfer statement returns three features. |
| Mix row counts, distinct counts, and sums in one group | All requested aggregates are calculated in one statement. A repeated operation key contributes once to its distinct count. Both qualifying legs contribute to the sum. |
| Use composite keys and different feature filters | Each count uses its own fields and filter. Multiple qualifying rows with the same composite key contribute once. |
| Exclude the current upstream event | All rows for the current `(source, eventId)` are excluded, including rows already present inside the window. Eligible rows from a different producer with the same local event ID remain in history. |
| Test time boundaries and redelivery | A row at the start is included, a row at `asOf` is excluded, and `FINAL` collapses duplicate deliveries of the same stored leg. |
| Publish an invalid definition | Unknown or nullable distinct keys, group-level `distinct`, an unsupported operation, or incompatible fields are rejected before activation. |
| Publish or roll back while a request is in flight | Every group and the returned version use the snapshot captured at request start. The next request can use the newly active version. |
| Run against empty history or a failing group | Empty history returns the operation's zero or `null` result. Errors identify failed features, and successful groups still return values. |

Measure latency and memory with representative windows and accounts, including `FINAL`, exact distinct keys, and several groups per request. Exact distinct aggregation uses memory proportional to the number of distinct keys.

## References

- [ClickHouse exact distinct counts](https://clickhouse.com/docs/reference/functions/aggregate-functions/uniqExact)
- [ClickHouse aggregate combinators](https://clickhouse.com/docs/reference/functions/aggregate-functions/combinators)
- [ClickHouse ReplacingMergeTree and query-time deduplication](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/replacingmergetree)
