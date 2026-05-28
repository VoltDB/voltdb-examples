# ddos-detection - VoltDB Partitioned Client

A VoltDB client demonstrating **real-time DDoS detection** using partitioned tables, co-located stored procedures, and TIME_WINDOW materialized views.

## What This Demonstrates

VoltDB's strengths for real-time DDoS detection:
- **Single-partition stored procedure** — checks blocked status, reads domain config, inserts request, evaluates rules, and blocks if triggered — all in one atomic operation
- **TIME_WINDOW() materialized views** — real-time sliding-window aggregates (1-second windows) maintained automatically by VoltDB
- **Co-located tables** — REQUESTS and BLOCKED_IPS on the same partition key for zero-network-hop joins
- **Replicated lookup table** — DOMAINS replicated to all nodes, accessible from any partition for per-domain threshold configuration
- **Configurable detection rules** — per-domain request thresholds (Rule 1) and global per-IP rate limits (Rule 2)

## Partitioning Strategy

**Partition Key:** `SOURCE_IP`

| Table | Partition Column | Strategy |
|-------|------------------|----------|
| REQUESTS | SOURCE_IP | Primary entity |
| BLOCKED_IPS | SOURCE_IP | Co-located with REQUESTS |
| DOMAINS | _(replicated)_ | Small reference table |

### Why SOURCE_IP?
- High cardinality (unique per client)
- Enables co-location of REQUESTS and BLOCKED_IPS
- All DDoS detection rules are per-IP — single-partition execution

### Materialized Views (TIME_WINDOW)
- **REQUESTS_PER_IP_DOMAIN_1SEC** — per-IP, per-domain request count in 1-second window (Rule 1)
- **REQUESTS_PER_IP_1SEC** — per-IP total request count in 1-second window (Rule 2)

## Procedures

| Procedure | Type | Defined In | Description |
|-----------|------|------------|-------------|
| ProcessRequest | Single-partition (multi-step) | Java class | Atomic DDoS detection: check blocked → read domain config → insert request → check rules → block if triggered |
| GetRequestsByIp | Single-partition (co-located) | Java class | Get requests + blocked records for an IP (2 co-located queries) |
| GetBlockedIps | Single-partition | DDL | Get blocked records for an IP |
| UpsertDomain | Multi-partition | DDL | Add/update monitored domain configuration |
| SearchBlockedIps | Multi-partition | DDL | Search blocked IPs by rule name |

Simple single-statement procedures (GetBlockedIps, UpsertDomain, SearchBlockedIps) are defined directly in DDL using `CREATE PROCEDURE ... AS sql-statement` — no Java class needed. Only ProcessRequest (multi-step atomic transaction) and GetRequestsByIp (co-located multi-table access) require Java classes.

### ProcessRequest — Deep Dive

The `ProcessRequest` stored procedure is the core of this demo. Its entire `run()` method executes as a **single ACID transaction** on one partition. VoltDB guarantees that either all of it commits or none of it does. There are no locks — VoltDB uses single-threaded partition execution, so this procedure has exclusive access to all data on its partition while it runs.

The procedure performs 4–7 SQL statements across four phases, with Java detection logic in between:

**Phase 1 — Check & Config** (two queries batched together):
- `SELECT` from `BLOCKED_IPS` — check if IP is already blocked (early exit)
- `SELECT` from `DOMAINS` (replicated table) — read per-domain threshold config

If the IP is already blocked, the request is recorded as blocked and the procedure returns immediately.

**Phase 2 — Record** (one statement):
- `INSERT INTO REQUESTS` — record the incoming request (initially as allowed)

This insert updates the TIME_WINDOW materialized views incrementally — no table scan required.

**Phase 3 — Rule Evaluation** (two view queries batched together):
- `REQUESTS_PER_IP_DOMAIN_1SEC` — reject if >N requests from this IP to this domain in 1 second (N is per-domain configurable)
- `REQUESTS_PER_IP_1SEC` — reject if >1000 total requests from this IP in 1 second

These views are pre-aggregated by VoltDB (maintained incrementally on each insert), so each is a single O(1) lookup, not a table scan.

**Phase 4 — Block** (conditional):
- If a rule triggered: `INSERT INTO BLOCKED_IPS` with the rule name and reason

**Why this matters:** The request insert, the view lookups, and the block decision are one atomic unit. There is no window where a request is inserted but the rule check hasn't run, or where two concurrent requests from the same IP both pass the threshold check. What would require distributed locks or rate-limiting middleware in other systems is a single-partition procedure call in VoltDB.

## DDoS Detection Rules

- **Rule 1 (RULE1_PER_DOMAIN):** Block IP if >N requests to the same domain in 1 second (N is per-domain configurable)
- **Rule 2 (RULE2_TOTAL):** Block IP if >1000 total requests across all domains in 1 second

## Project Structure

```
ddos-detection/
├── pom.xml
├── README.md
├── src/main/java/com/example/voltdb/
│   ├── DdosDetectionApp.java          # Main client app
│   ├── VoltDBSetup.java               # Idempotent schema deployment
│   ├── CsvDataLoader.java             # CSV data loading utility
│   └── procedures/
│       ├── ProcessRequest.java        # Multi-step atomic DDoS detection (Java class)
│       └── GetRequestsByIp.java       # Co-located access (Java class)
├── src/main/resources/
│   ├── ddl.sql                        # Tables, views, partitions, DDL + Java procedures
│   ├── remove_db.sql                  # Drop everything (dependency order)
│   └── data/
│       ├── domains.csv                # Monitored domain configuration
│       └── requests.csv               # Sample request data
└── src/test/java/com/example/voltdb/
    ├── IntegrationTestBase.java
    └── DdosDetectionIT.java
```

For instructions on building, running, and customizing this example, see [README.dev.md](README.dev.md).
