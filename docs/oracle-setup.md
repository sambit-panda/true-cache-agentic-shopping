# Oracle setup for the read-only demo

This guide prepares the sample against an existing Oracle primary database and True Cache. It follows the repository's two-connection design: catalog reads connect to the True Cache service, while exact stock reads connect to the primary service. Oracle documents this as the two-physical-connections application model ([connection methods](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/methods-connecting-true-cache.html)).

Creating and operating the True Cache database itself is a DBA task and is outside this sample's setup. Oracle provides [DBCA](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/configuring-true-cache-dbca.html) and [manual creation](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/creating-true-cache-manually.html) instructions. Ask your DBA for the primary and True Cache application service names for the same PDB.

True Cache is a read-only replica whose contents are maintained from the primary. It fetches blocks from the primary when needed and then maintains them using redo apply. You create the sample schema on the primary only; do not run the schema script on True Cache or manually copy rows there. See the [True Cache overview](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/overview-oracle-true-cache.html).

## Requirements

- An Oracle primary and True Cache configured for the same PDB.
- An active primary application service and a corresponding True Cache application service.
- A runner host or pod that can reach both Oracle services and the local model endpoint.
- JDK 21 and Maven.

## 1. Verify the True Cache configuration

Have a DBA run these checks. Connect to the True Cache instance for the first query:

```sql
SELECT database_role, open_mode FROM v$database;
```

The expected values are `TRUE CACHE` and `READ ONLY WITH APPLY`. Check the primary/True Cache relationship on either database:

```sql
SELECT true_cache_name, primary_name, status
FROM v$true_cache;
```

The status should be `HEALTHY`. Confirm the application's primary and True Cache services are active and refer to the same PDB. Oracle's [verification guide](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/verifying-true-cache-configuration.html) has additional service checks.

The service names are not necessarily the database unique names. Use the application service names in the JDBC URLs below.

## 2. Create an isolated demo schema

Use a disposable PDB or another isolated development environment. Connect as a database administrator to the target **PDB on the primary**, then create a schema owner and a separate read-only application user. Replace the passwords and tablespace name with values allowed by your environment.

```sql
CREATE USER AI_SHOP_OWNER IDENTIFIED BY "<owner-password>";
GRANT CREATE SESSION, CREATE TABLE TO AI_SHOP_OWNER;
ALTER USER AI_SHOP_OWNER QUOTA 100M ON USERS;

CREATE USER AI_SHOP_APP IDENTIFIED BY "<app-password>";
GRANT CREATE SESSION, CREATE SYNONYM TO AI_SHOP_APP;
```

Connect as `AI_SHOP_OWNER` to the **primary PDB service**, from the repository root, and run the sample schema once:

```text
sqlplus AI_SHOP_OWNER@//<primary-host>:1521/<primary-pdb-service>
SQL> @schema_oracle.sql
```

The script creates and seeds the sample `customers`, `products`, `orders`, and `idempotency_keys` tables. It is not idempotent: use a fresh schema rather than rerunning it over existing objects.

Grant the app user access to the product table, which is the only table used by the Oracle read-only demo, and create a synonym because the sample SQL refers to `products` without a schema prefix:

```sql
GRANT SELECT ON AI_SHOP_OWNER.PRODUCTS TO AI_SHOP_APP;
```

Then connect as `AI_SHOP_APP` to the primary PDB service:

```text
sqlplus AI_SHOP_APP@//<primary-host>:1521/<primary-pdb-service>
SQL> CREATE SYNONYM PRODUCTS FOR AI_SHOP_OWNER.PRODUCTS;
```

The Oracle demo exposes only read tools. `AI_SHOP_APP` has `SELECT` on `products`; it does not own the tables or have write access to them. The separate owner account is only for creating the sample schema.

## 3. Verify the schema through both services

The `CREATE`, `INSERT`, and `COMMIT` statements ran on the primary. Wait for redo apply, then connect as `AI_SHOP_APP` first through the primary service and then through the True Cache service. Run:

```sql
SELECT SYS_CONTEXT('USERENV', 'SERVICE_NAME') FROM dual;
SELECT COUNT(*) FROM products;
```

The service name should match the endpoint used for each connection, and the product count should be `6` through both services. If the True Cache result is not ready yet, check its health and redo-apply progress with your DBA.

## 4. Configure the demo

Set these values in the environment where the Java process will run. `ORA_USER` and `ORA_PASSWORD` are the **application user's** credentials. Keep the password in an approved secret store or local environment; do not commit it.

```sh
export ORA_USER=AI_SHOP_APP
export ORA_PASSWORD='<app-password>'
export ORA_PRIMARY_URL='jdbc:oracle:thin:@//<primary-host>:1521/<primary-pdb-service>'
export ORA_CACHE_URL='jdbc:oracle:thin:@//<true-cache-host>:1521/<true-cache-service>'

export LLM_RESPONSES_URL='http://<model-service>:8080/v1/responses'
export LLM_MODEL=qwen3-4b

mvn -Poracle -DskipTests package
./run-oracle-agent.sh --smoke-test
./run-oracle-agent.sh
```

The smoke test checks the catalog route through True Cache and the exact stock route through the primary. It does not call the model or Redis LangCache, so it can run before either service is configured. The regular demo requires the runner to reach both Oracle services and the model endpoint. Oracle listener redirects may require running inside the cluster or on a host with the right network access; a simple port-forward may not be sufficient.

The model is self-hosted, so this flow does not require an OpenAI API key. The optional Redis LangCache flow has separate endpoint, cache ID, and service-key requirements; see [Run the Redis LangCache + True Cache flow](../README.md#run-the-redis-langcache--true-cache-flow).

## 5. Confirm the routes

The default command runs the prompt twice with the application result cache bypassed. Its output should show `search_products` routed to True Cache and `check_stock` routed to the primary. The database route is selected by the application; LangCache and True Cache do not share entries or communicate directly.

For performance measurements, separate database timing from the model-loop elapsed time. Warm the True Cache with the workload, bypass LangCache and the application cache, and compare the same SQL workload against the primary and True Cache. Oracle Enterprise Manager reports True Cache hit ratios ([monitoring overview](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/monitoring-true-cache.html)). `V$TRUE_CACHE_STAT` can report apply/transport lag and block-fetch latency on supported releases; it is available starting with Oracle AI Database 26ai ([view details](https://docs.oracle.com/en/database/oracle/oracle-database/26/odbtc/monitoring-true-cache-vtrue_cache_stat-view.html)).
