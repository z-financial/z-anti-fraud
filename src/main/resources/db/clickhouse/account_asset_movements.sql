-- Apply explicitly to the selected ClickHouse database; not run at application startup.
-- One immutable submitted-transfer leg per derived event_id; retries preserve all values.
-- source_event_id retains the upstream submission identity shared by OUT and IN.
-- PRIMARY KEY is a sparse index, not a uniqueness constraint. ReplacingMergeTree
-- replaces by the full ORDER BY key. Exact feature queries must use FINAL.
CREATE TABLE IF NOT EXISTS account_asset_movements
(
    event_id        String,
    source_event_id String,
    operation_id    String,
    correlation_id  Nullable(String),

    source          LowCardinality(String),
    event_type      LowCardinality(String),
    schema_version  UInt32,

    account_id      String,

    amount          Int64,
    currency        LowCardinality(String),

    event_time      DateTime64(3, 'UTC'),
    received_at     DateTime64(3, 'UTC'),

    CONSTRAINT positive_amount CHECK amount > 0
)
ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMM(event_time)
PRIMARY KEY (account_id, event_time)
ORDER BY (account_id, event_time, event_id);
