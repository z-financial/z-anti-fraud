# z-anti-fraud

## Run locally with Docker

Start Docker Desktop with Linux containers, then run from the project root:

```powershell
docker compose up --build -d
docker compose logs -f app
```

The build compiles and tests the application using Maven and Java 17 inside Docker;
you do not need Java or Maven installed locally. The runtime runs as a non-root
user. Research datasets are excluded from the image.

The API is available at `http://localhost:8080/api/v1/risk-evaluations` (POST).
See [the API documentation](doc/risk-api-v1.md) for the request format.
There is no home page at `/`.

Compose also starts ClickHouse at `http://localhost:8123`, creates the
`account_asset_movements` table on the first start, and waits for that table
before starting the app. Its data is stored in the `clickhouse-data` Docker
volume and survives `docker compose down`. The initialization SQL follows the
[official ClickHouse image initialization mechanism](https://github.com/ClickHouse/ClickHouse/blob/master/docker/server/README.md#how-to-extend-this-image).
Existing tables are not migrated automatically.

Local ClickHouse credentials are `default` / `local-dev-password`. To customize
them or the host ports, create a `.env` file before starting:

```dotenv
APP_PORT=8080
CLICKHOUSE_HTTP_PORT=8123
CLICKHOUSE_PASSWORD=local-dev-password
TRANSFER_OUT_AMOUNT_24H_LIMIT=500000
TRANSFER_OUT_COUNT_24H_LIMIT=50
TRANSFER_SINGLE_AMOUNT_LIMIT=100000
```

Stop the services while keeping database data:

```powershell
docker compose down
```

To also delete the local database data, use `docker compose down --volumes`.

## Run the app image against an existing ClickHouse

If ClickHouse already runs on your host, apply
`src/main/resources/db/clickhouse/account_asset_movements.sql` to its selected
database, then use the following PowerShell commands with Docker Desktop:

```powershell
docker build -t z-anti-fraud:local .
docker run --rm --name z-anti-fraud -p 127.0.0.1:8080:8080 `
  -e CLICKHOUSE_JDBC_URL=jdbc:clickhouse:http://host.docker.internal:8123/default `
  -e CLICKHOUSE_USERNAME=default `
  -e CLICKHOUSE_PASSWORD=your-password `
  z-anti-fraud:local
```

`host.docker.internal` reaches the host from Docker Desktop. For ClickHouse in
another container, attach both containers to the same Docker network and use
its service/container name instead. `localhost` inside the app container refers
to the app container itself.

The current application queries transfer history but does not persist submitted
events yet. A new local database therefore has empty history until populated
through an external ingestion path.
