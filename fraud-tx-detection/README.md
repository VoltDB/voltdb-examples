# fraud-tx-detection - VoltDB Fraud Detection Demo

A VoltDB client demonstrating **real-time fraud detection** using table partitioning, co-location, and TIME_WINDOW() materialized views.

## What This Demonstrates

VoltDB's strengths for fast transactional fraud detection:
- **Single-partition stored procedure** — validates account, checks fraud rules, inserts transaction, and updates balance in one atomic operation
- **TIME_WINDOW() materialized views** — real-time sliding-window aggregates (30s, 1min, 5min) maintained automatically by VoltDB
- **Co-located tables** — ACCOUNTS and TRANSACTIONS on the same partition key for zero-network-hop joins
- **Replicated lookup table** — MERCHANTS replicated to all nodes, accessible from any partition
- **Async high-throughput benchmark** — demonstrates thousands of fraud-checked transactions per second

## Partitioning Strategy

**Partition Key:** `ACCOUNT_ID`

| Table | Partition Column | Strategy |
|-------|------------------|----------|
| ACCOUNTS | ACCOUNT_ID | Primary entity |
| TRANSACTIONS | ACCOUNT_ID | Co-located with ACCOUNTS |
| MERCHANTS | *(replicated)* | Small lookup table on all nodes |

### Materialized Views

| View | Window | Fraud Rule |
|------|--------|------------|
| TXN_SUMMARY_30SEC | 30 seconds | >5 transactions = reject |
| TXN_SUMMARY_1MIN | 1 minute | >$5,000 total spending = reject |
| TXN_SUMMARY_5MIN | 5 minutes | >10 txns or >$10,000 = reject |

### Why ACCOUNT_ID?
- High cardinality (unique per account)
- Co-locates transactions with their account for single-partition fraud checks
- All fraud detection queries are account-centric

## Procedures

| Procedure | Type | Defined In | Description |
|-----------|------|------------|-------------|
| ProcessTransaction | Single-partition (multi-step) | Java class | Full fraud check + insert + balance update |
| UpsertAccount | Single-partition | DDL | Create or update an account |
| GetAccount | Single-partition | DDL | Retrieve account by ID |
| UpsertMerchant | Multi-partition | DDL | Create or update a merchant (replicated table) |

Simple single-statement procedures (UpsertAccount, GetAccount, UpsertMerchant) are defined directly in DDL using `CREATE PROCEDURE ... AS sql-statement` — no Java class needed. Only ProcessTransaction (multi-step atomic transaction) requires a Java class.

### ProcessTransaction — Deep Dive

The `ProcessTransaction` stored procedure is the core of this demo. Its entire `run()` method executes as a **single ACID transaction** on one partition. VoltDB guarantees that either all of it commits or none of it does. There are no locks — VoltDB uses single-threaded partition execution, so this procedure has exclusive access to all data on its partition while it runs.

The procedure performs 5–7 SQL statements across two batched execution phases, with Java fraud-decision logic in between:

**Phase 1 — Validation** (two queries batched together):
- `SELECT` from `ACCOUNTS` — check if account exists, is enabled, get balance and daily limit
- `SELECT` from `MERCHANTS` (replicated table) — check if merchant exists

Java logic then evaluates: account missing? disabled? amount > $5,000? balance + amount > daily limit?

**Phase 2 — Fraud rules** (three view queries batched together):
- `TXN_SUMMARY_5MIN` — reject if >10 txns or >$10,000 spent in 5 minutes
- `TXN_SUMMARY_1MIN` — reject if >$5,000 spent in 1 minute
- `TXN_SUMMARY_30SEC` — reject if >5 txns in 30 seconds

These views are pre-aggregated by VoltDB (maintained incrementally on each insert), so each is a single O(1) lookup, not a table scan.

**Phase 3 — Write** (batched in the final execution call):
- If accepted: `UPDATE ACCOUNTS SET BALANCE = BALANCE + amount` **+** `INSERT INTO TRANSACTIONS`
- If rejected: just `INSERT INTO TRANSACTIONS` (with `accepted=0` and the rejection reason)

**Why this matters:** All SQL statements, the fraud decision logic, the balance update, and the transaction record are one atomic unit. There is no window where the balance is updated but the transaction isn't recorded, or where a fraud check passes but another transaction sneaks in before the insert. What would require distributed locks or eventual consistency in other systems is a single-partition procedure call in VoltDB.

## Project Structure

```
fraud-tx-detection/
├── pom.xml
├── README.md
├── src/main/java/com/example/voltdb/
│   ├── FraudDetectionApp.java          # Benchmark client with async tx processing
│   ├── VoltDBSetup.java                # Idempotent schema deployment
│   ├── CsvDataLoader.java              # CSV data loading utility
│   └── procedures/
│       └── ProcessTransaction.java     # Multi-step atomic fraud check (Java class)
├── src/main/resources/
│   ├── ddl.sql                         # Tables, views, partitions, DDL + Java procedures
│   ├── remove_db.sql                   # Drop everything (dependency order)
│   └── data/
│       ├── accounts.csv                # Sample account data
│       └── merchants.csv               # Sample merchant data
└── src/test/java/com/example/voltdb/
    ├── IntegrationTestBase.java        # Testcontainer/external mode base
    └── FraudDetectionIT.java           # Integration tests for fraud scenarios
```

For instructions on building, running, and customizing this example, see [README.dev.md](README.dev.md).
