# V1 synthetic transfer fixtures

Run from the repository root with Python 3.10 or newer. No packages, running app,
or database are needed to generate the files:

```powershell
python scripts/generate_v1_test_data.py
```

The default output is `target/synthetic-v1` (already ignored by Git). Generation
checks every scenario's declared history totals and matched rules before writing.
Identical arguments produce identical files. Use `--overwrite` to replace an
existing output, `--run-id run-002` to namespace another run, or `--as-of` to change
the fixed evaluation clock. The script does not connect to any service.

| File | Purpose |
| --- | --- |
| `schema.sql` | Copy of the application's ClickHouse table definition. |
| `seed.sql` | Insert historical OUT and IN legs, including intentional redeliveries. |
| `history.jsonl` | The same seed rows in ClickHouse JSONEachRow format. |
| `requests.jsonl` | One native `/api/v1/risk-evaluations` request per line. |
| `expected.jsonl` | Offline case descriptions, features, actions and matched rule IDs. |
| `manifest.json` | Fixed clock, thresholds, units, history semantics and prerequisites. |

There are 22 independent scenarios: below/equal/above each threshold, all four
possible combinations of multiple violations, empty history, exact lower cutoff,
just before the lower cutoff, same-time/future exclusions, duplicate amount/count
history, incoming exclusion and other-account exclusion. Each has its own sender
account, so the entire seed can be loaded together and requests sent in any order.

The limits are strict `>` comparisons: rolling outgoing amount `500000`, rolling
distinct operation count `50`, single amount `100000`. Rolling totals include the
current submission once, with historical events in `[eventTime - 24h, eventTime)`.
Only submitted outgoing transfers count, including ones subsequently blocked or
unsuccessful. Identical delivery retries deduplicate by event identity; distinct
operations count once each. All transfers use EUR and raw integer units without
currency conversion. If upstream units are cents, `100000` means EUR 1000.

Load `schema.sql` then **either** `seed.sql` **or** `history.jsonl` into an initially
empty dedicated test database selected by your ClickHouse client. For example,
using clickhouse-client in a shell that supports input redirection:

```sh
clickhouse-client --database v1_test --multiquery < target/synthetic-v1/schema.sql
clickhouse-client --database v1_test --multiquery < target/synthetic-v1/seed.sql
```

The client commands above use shell redirection; PowerShell users can invoke
them through WSL or pipe `Get-Content -Raw <file>` to their installed client.
The database must already exist. There are no database deletes or drops in the
generated SQL. Point the application at that same test database, then POST each
line of `requests.jsonl` as JSON. Match responses to `expected.jsonl` by request ID;
compare HTTP status, status, action, and the set of `reasons[].ruleId` values.
The expected feature values are available for direct database-query verification;
the current HTTP response does not expose them. Keep expectations out of API requests.

The app implements all three rules as rule set `rules-v2`, with defaults matching
this suite. Configuration overrides must use `TRANSFER_OUT_AMOUNT_24H_LIMIT=500000`,
`TRANSFER_OUT_COUNT_24H_LIMIT=50`, and `TRANSFER_SINGLE_AMOUNT_LIMIT=100000`.
API persistence is currently a placeholder, which is why the seed is loaded
externally. A BLOCK is a policy expectation, not a confirmed-fraud annotation.

This case pack tests correctness rather than throughput or real fraud-detection
accuracy. Database outages and missing-feature fallback require separate live
fault-injection tests; they cannot be encoded as ordinary seed rows.

Run the generator's focused checks with:

```powershell
python -m unittest discover -s scripts -p "test_*.py"
```

To exercise the existing local Docker API and print a per-case comparison:

```powershell
python scripts/test_v1_docker_api.py
```

Docker Compose services `app` and `clickhouse` must already be running. This
runner inserts fixtures through the ClickHouse container's client, using its
configured database and credentials, then POSTs all 22 requests to localhost:8080.
Each run has uniquely namespaced synthetic accounts; existing account histories
and application settings are unchanged. Seed rows remain in that database.
It first verifies the real deduplicated ClickHouse totals for every case, then
checks response HTTP status, evaluation status, action, rule IDs and request/event
identity. A mismatch returns exit code 1; setup failure returns 2.

The runner writes `target/docker-v1-test/test.log` and `results.json`, plus a fixture
subdirectory for each run. The latest report files are replaced on the next run;
use `--output` for a separate report directory. `--api-url` accepts an alternate
local HTTP endpoint. Rebuild the app after changing rules or configuration with
`docker compose up --build -d app`. The suite expects all three `rules-v2` rules
and the documented limits. The runner reports differences without changing the
deployment.
