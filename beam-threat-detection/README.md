# beam-threat-detection — end-to-end demo of the VoltDB Beam connector

**Status:** scaffolding only. Not yet buildable end-to-end. See `RUNBOOK.md` for phased delivery plan.

**Related tickets:** [ENG-29805](https://issuesvolt.atlassian.net/browse/ENG-29805) (V2 demo) · [ENG-29804](https://issuesvolt.atlassian.net/browse/ENG-29804) (connector productization parent).

**Design docs (external, in `~/Marina/Docs/Voltdb-Beam/`):**
- `threat-detection-v2-architecture-options.md` — every option considered and why we chose Option E
- `threat-detection-v2-outline.md` — this project's outline, blog post plan, phased delivery

---

## What this demo shows

An end-to-end threat detection system on Google Cloud that uses VoltDB as the real-time state store and the Apache Beam VoltDB connector (`voltdb-beam-io`) to move data in and out of Beam pipelines running on Dataflow.

**The scenario in one paragraph:** a bot scans your public website from a single subnet — product pages, login page, help pages — at 100 requests/second. If the system only tracks transactions, this scan is invisible; when the bot finally attempts fraud, its transaction is the FIRST from that subnet, so a per-transaction rate rule sees a count of 1 and lets it through. In this architecture, page hits are also written into VoltDB, into a separate per-subnet counter that the transaction-processing stored procedure reads atomically alongside its own per-account rules. The scan pushes the subnet's page-hit count past threshold within seconds, so when the fraudulent transaction arrives, the SP sees a hot subnet signal and rejects it atomically — before the charge is executed. A real-time fraud/threat system that reads signals across sources with per-request latency, in ~500 lines of Java.

## Architecture — three real-time paths

```
── Path 1: Transaction real-time (unchanged from V1) ────────────
User request → ThreatDetectionMicroservice → VoltDB SPs
                                              (RecordSubnetRequest → subnet count,
                                               ProcessTransaction → ACCEPT/REJECT)
                     SP results drive accept/reject + UI feedback

── Path 2: Page-hits real-time (NEW — Beam + connector) ─────────
PubSub topic (page-hit events)
    → PageHitIngestPipeline (Beam streaming, Dataflow)
       → VoltDbIO.write("RecordSubnetRequest")
             fire-and-forget: return value ignored
             async pipelined: high-volume page hits saturate the write path

── Path 3: Reporting (NEW — Beam + connector + Iceberg + BQ) ────
Cloud Scheduler (every 2-5 min)
    → ReportingPipeline (Beam batch, Dataflow)
       1. Read watermark: SELECT MAX(TXN_TIME) FROM analytics.transactions
       2. VoltDbIO.read().withProcedure("ReadTxnsSince", watermark)
             server-side JOIN with ACCOUNTS + MERCHANTS
       3. GeoIP enrichment via MaxMind side input (GeoIpEnrichFn)
       4. Parallel sinks:
             ├─ IcebergIO.writeRows → analytics.transactions_iceberg
             └─ BigQueryIO.write    → analytics.transactions
```

Full architecture diagram + rationale: `docs/architecture.mmd` (once rendered).

## Repo layout

```
beam-threat-detection/
├── pom.xml                                  Maven single-module project
├── README.md                                this file
├── RUNBOOK.md                               phased delivery + day-2 ops
├── docs/                                    architecture diagram (Mermaid)
├── bin/                                     launcher scripts for each component
├── src/main/java/org/voltdb/example/threat/
│   ├── common/                              CidrUtils, PageHitEvent
│   ├── procedures/                          VoltDB stored procedures (ProcessTransaction, RecordSubnetRequest)
│   ├── microservice/                        Path 1: transaction real-time (ThreatDetectionMicroservice, VoltDBSetup)
│   ├── generator/                           PageHitGenerator (feeds PubSub)
│   └── pipelines/                           Path 2 + Path 3 Beam pipelines
├── src/main/resources/
│   ├── voltdb-ddl.sql                       V1 schema + V2 SOURCE_TYPE addition
│   ├── bigquery-ddl.sql                     Destination table + Iceberg schema
│   ├── data/                                Reference data CSVs
│   └── application.properties.template      Config template
└── src/test/java/org/voltdb/example/threat/ IT tests (Testcontainers-based)
```

## Prerequisites

- JDK 11+
- Maven 3.6+
- Docker (for Testcontainers IT tests)
- VoltDB Enterprise license (Developer Edition license works too — see the
  `beam-basic-io` example's README for how to swap the test image)
- Google Cloud project with PubSub, Dataflow, BigQuery, and (optionally) GCS +
  Iceberg catalog — only needed for GCP runs, not for local IT

## Quick start (local, Testcontainers)

*Not yet implemented — see `RUNBOOK.md` Phase 0.*

## Quick start (GCP Dataflow)

*Not yet implemented — see `RUNBOOK.md` Phases 1-3.*

## VoltDB schema — V2 delta vs V1

The `SUBNET_REQUESTS` table gains a `SOURCE_TYPE varchar(4) DEFAULT 'TXN'` column so it can capture entries from both real-time paths. The `REQUESTS_PER_SUBNET` TIME_WINDOW materialized view aggregates across BOTH source types — no filter on `SOURCE_TYPE` — so the subnet-rate rule sees the combined count. See `src/main/resources/voltdb-ddl.sql` for the full DDL with inline commentary.

## License

MIT (matches the rest of `voltdb-examples`).