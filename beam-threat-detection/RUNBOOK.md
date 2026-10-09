# beam-threat-detection — RUNBOOK

Complete set of commands to provision the demo on GCP, run both Beam pipelines,
and tear everything down. Every section inherits env vars from `runbook.env`:

```bash
cp runbook.env.template runbook.env
# edit runbook.env with your project / bucket / cluster values
source runbook.env
```

The vars referenced below (`$PROJECT`, `$REGION`, `$BUCKET`, `$NS`, `$REL`,
`$CTX`, `$LIC`, `$CHART`) are all defined in `runbook.env.template`.

**Conventions used throughout** (not env vars — fixed names):

- BigQuery dataset: `beam_threat_detection`
- BigQuery connection: `beam-threat-detection-iceberg`
- PubSub topics: `threat-page-hits`, `threat-page-hits-dlq`, `threat-page-hits-raw-dlq`

**Run from the repo root** — the ConfigMap sources in §4 use relative paths.

**Create commands are not idempotent** — re-running §2–§7 against an
already-provisioned project errors on existing resources. If something goes
wrong partway through, use the teardown section before retrying.

Sections:

- [Prerequisites](#prerequisites)
- [GCP setup (§1–§8)](#gcp-setup-one-time-provisioning)
- [Operations and teardown](#operations-and-teardown)
- [Followup: harden REQUEST_ID](#followup-harden-request_id-for-at-least-once-retries)

## Prerequisites

CLI tools on your `$PATH`:

- `gcloud`, `bq`, `gsutil` (Google Cloud SDK)
- `kubectl`, `helm` 3.x
- `mvn` 3.6+, JDK 11+
- `jq`

Auth + project context:

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project $PROJECT
```

Google APIs enabled on the project (one-time):

```bash
gcloud services enable \
  pubsub.googleapis.com \
  dataflow.googleapis.com \
  bigquery.googleapis.com \
  bigqueryconnection.googleapis.com \
  storage.googleapis.com \
  container.googleapis.com \
  --project=$PROJECT
```

Also required:

- Billing enabled on the project.
- VoltDB enterprise license file at `$LIC`.
- A GKE cluster already provisioned in `$REGION` and reachable via `$CTX`.
- `voltdb-operator` Helm chart checked out at `$CHART`, with its CRDs already
  installed in the cluster (one-time; see the chart's own install docs). §4
  uses `--skip-crds`, so the CRDs must exist before you run it.

The `transactions` BigQuery table referenced in operations/teardown is created
on first write by the reporting pipeline (`BigQueryIO.write` with
`CREATE_IF_NEEDED`); there is no explicit CREATE TABLE step for it.

## GCP setup (one-time provisioning)

### 1. GCS — Dataflow staging + GeoIP asset

```bash
gsutil mb -l $REGION gs://$BUCKET   # if not already present
# Upload MaxMind GeoLite2-City .mmdb (free signup at https://www.maxmind.com/).
# File lives at gs://$BUCKET/geoip/GeoLite2-City.mmdb (~60 MiB).
```

### 2. BigQuery dataset

```bash
bq --project_id=$PROJECT --location=$REGION mk --dataset ${PROJECT}:beam_threat_detection
```

### 3. PubSub topics

Three topics on the project (default message retention 7 days):

- `threat-page-hits` — the inbound page-browsing event topic. Produced by
  `PageHitsGenerator` (or a real web tier); consumed by two independent
  subscribers (§4 `PageHitsIngestPipeline` + §7 `threat-page-hits-to-bq`
  native BQ subscription).
- `threat-page-hits-dlq` — dead-letter target for parse failures inside
  `PageHitsIngestPipeline` (bad JSON, missing required fields). Messages
  arrive here with the original payload preserved.
- `threat-page-hits-raw-dlq` — dead-letter target for the §7 native
  PubSub-to-BigQuery subscription. The native subscription fails rows that
  don't match the BigQuery table schema; those messages go here instead of
  being silently dropped.

```bash
gcloud pubsub topics create threat-page-hits          --project=$PROJECT
gcloud pubsub topics create threat-page-hits-dlq      --project=$PROJECT
gcloud pubsub topics create threat-page-hits-raw-dlq  --project=$PROJECT
```

### 4. VoltDB cluster on GKE

Chart + ConfigMaps + helm install. Matches `src/main/resources/voltdb-ddl.sql`
as source of truth.

```bash
# Namespace + Docker Hub pull-secret (voltdb images are private).
# Replace <source-ns> with the namespace that already holds a dockerio-registry
# secret you can copy from.
kubectl --context=$CTX create ns $NS
kubectl --context=$CTX get secret dockerio-registry -n <source-ns> -o yaml \
  | sed "s/namespace: <source-ns>/namespace: $NS/" | kubectl --context=$CTX apply -f -
kubectl --context=$CTX patch sa default -n $NS \
  -p '{"imagePullSecrets":[{"name":"dockerio-registry"}]}'

# ConfigMaps carry the DDL and the compiled procedures jar.
# IMPORTANT: these feed the chart's clusterInit.*ConfigMapRefName values,
# which are read ONLY on first boot. Re-running them on an already-provisioned
# cluster does nothing until the PVC is wiped (helm uninstall + delete pvc).
kubectl --context=$CTX create configmap ${REL}-schema -n $NS \
  --from-file=schema.sql=src/main/resources/voltdb-ddl.sql
mvn package -DskipTests
kubectl --context=$CTX create configmap ${REL}-classes -n $NS \
  --from-file=procedures.jar=target/voltdb-beam-threat-detection-example-1.0.0-SNAPSHOT-procedures.jar

# Helm install — plain, no SSL, no security, 1-node, kfactor=0.
# NOTE values path: clusterInit CM refs are under cluster.clusterSpec.clusterInit.*,
# NOT cluster.config.clusterInit.* — the latter is silently accepted and ignored.
helm install $REL $CHART --kube-context $CTX -n $NS --skip-crds --wait --timeout 10m \
  --set global.voltdbVersion=15.3.0 \
  --set-file cluster.config.licenseXMLFile=$LIC \
  --set cluster.clusterSpec.image.repository=voltdb/voltdb-enterprise \
  --set cluster.clusterSpec.image.tag=15.3.0 \
  --set cluster.clusterSpec.replicas=1 \
  --set cluster.config.deployment.cluster.kfactor=0 \
  --set cluster.config.deployment.cluster.sitesperhost=8 \
  --set cluster.config.deployment.commandlog.enabled=false \
  --set cluster.clusterSpec.clusterInit.schemaConfigMapRefName=${REL}-schema \
  --set cluster.clusterSpec.clusterInit.classesConfigMapRefName=${REL}-classes
```

### 5. Internal LoadBalancer for Dataflow → VoltDB

Dataflow workers run in the project's default VPC; so does the GKE cluster.
Internal LB gives Dataflow a reachable IP without exposing VoltDB publicly.

The selector below assumes the voltdb-operator chart defaults the cluster
name to `${REL}-voltdb-cluster`. If `cluster.clusterSpec.name` was overridden,
check the actual label with `kubectl get pod -n $NS --show-labels` and update
the selector before applying.

```bash
kubectl --context=$CTX apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${REL}-voltdb-dataflow-ilb
  namespace: ${NS}
  annotations:
    networking.gke.io/load-balancer-type: "Internal"
spec:
  type: LoadBalancer
  ports: [{ name: client, port: 21212, targetPort: 21212, protocol: TCP }]
  selector:
    voltdb-cluster-name: ${REL}-voltdb-cluster
EOF

kubectl --context=$CTX get svc ${REL}-voltdb-dataflow-ilb -n $NS -w
# Wait for LoadBalancer-type EXTERNAL-IP (in VPC it's an RFC1918 address,
# e.g. 10.128.0.114).
```

### 6. Local-dev reach to VoltDB (kubectl port-forward)

For running the Java `main()` tools (`TransactionsGenerator`, `ThreatDetectionApp`)
locally against the GKE cluster. Dies on any transient network blip — just
restart.

```bash
kubectl --context=$CTX port-forward svc/${REL}-voltdb-cluster-client 21212:21212 -n $NS
```

### 7. BigQuery sink for raw page-hits (PubSub-BQ subscription)

Fan-out architecture: `PageHitsIngestPipeline` consumes PubSub → VoltDB for
operational rule state; a separate PubSub-BQ subscription consumes the SAME
topic → BigQuery warehouse for analytics. Zero Beam involvement in the
warehouse write path; failure of one path doesn't affect the other.

**Region prerequisite — all three must match (bucket, BigQuery dataset,
BigQuery connection):** BigQuery managed Iceberg tables require that the
GCS bucket backing the table, the BigQuery dataset holding the table, and
the BigQuery connection used to reach the bucket all live in the same
single region. A dataset in multi-region `US` with a connection in
`us-central1` fails on CREATE TABLE with "Not found: Dataset ... was not
found in location us-central1". The commands below assume all three are in
`$REGION`; if the dataset was previously created in `US` multi-region it
must be dropped and recreated with `--location=$REGION` first.

```bash
# 7a. Raw table — Iceberg-formatted BigQuery table. Storage format is Apache
# Iceberg Parquet files in GCS with proper Iceberg metadata, managed by
# BigQuery; data is queryable natively from BigQuery AND from any Iceberg-aware
# engine (Spark, Trino, Flink, DuckDB) pointed at the GCS location. One write
# by the PubSub subscription puts the data in both the warehouse and the
# historical store — no separate export pipeline needed.
#
# A BigQuery connection to GCS is required (one-time). See:
#   https://cloud.google.com/bigquery/docs/iceberg-tables
CONN_ID=beam-threat-detection-iceberg
bq --project_id=$PROJECT mk --connection \
  --location=$REGION \
  --connection_type=CLOUD_RESOURCE \
  $CONN_ID
# Grant the connection's service account access to the bucket.
#   objectUser         — read/create/overwrite/delete DATA objects (needed for Iceberg Parquet writes)
#   legacyBucketReader — includes storage.buckets.get at the BUCKET level, which the Pub/Sub
#                        streaming write path to an Iceberg-formatted BQ table requires. Granting
#                        only objectUser/objectAdmin is NOT enough — PubSub writes fail silently
#                        with "Streaming is not available since connection ... does not have
#                        permissions storage.buckets.get". The failure surfaces only in Pub/Sub
#                        Monitoring metrics (push_request_count code=invalid_argument / permission_denied)
#                        and in the DLQ's CloudPubSubDeadLetterSourceDeliveryErrorMessage attribute —
#                        NOT in Cloud Logging warnings, NOT in the subscription state.
CONN_SA=$(bq --project_id=$PROJECT --format=json show --connection --location=$REGION $CONN_ID \
  | jq -r '.cloudResource.serviceAccountId')
gsutil iam ch serviceAccount:${CONN_SA}:objectUser gs://$BUCKET
gsutil iam ch serviceAccount:${CONN_SA}:legacyBucketReader gs://$BUCKET

# NB: for BigQuery managed Iceberg tables, PARTITION BY and CLUSTER BY go
# BEFORE WITH CONNECTION (opposite of the native-BQ DDL order). Putting them
# after OPTIONS gives a confusing "Expected end of input but got keyword
# PARTITION" error.
bq --project_id=$PROJECT query --use_legacy_sql=false "
CREATE TABLE \`$PROJECT.beam_threat_detection.page_hits_raw\` (
  source_ip    STRING NOT NULL,
  page_url     STRING,
  timestamp    TIMESTAMP NOT NULL,
  publish_time TIMESTAMP NOT NULL
)
PARTITION BY DATE(timestamp)
CLUSTER BY source_ip
WITH CONNECTION \`$PROJECT.$REGION.$CONN_ID\`
OPTIONS (
  file_format = 'PARQUET',
  table_format = 'ICEBERG',
  storage_uri = 'gs://$BUCKET/beam_threat_detection/page_hits_raw'
);"

# 7b. Derived view — adds a `subnet` column via NET.IP_TRUNC. Free, computed
# per scanned row; after partition pruning on a short time window the per-row
# cost is negligible. The view is transparent to the underlying storage
# format, so it works the same against the Iceberg-backed base table.
bq --project_id=$PROJECT query --use_legacy_sql=false "
CREATE VIEW \`$PROJECT.beam_threat_detection.page_hits\` AS
SELECT *,
  NET.IP_TO_STRING(
    NET.IP_TRUNC(NET.SAFE_IP_FROM_STRING(source_ip), 24)
  ) AS subnet
FROM \`$PROJECT.beam_threat_detection.page_hits_raw\`;"

# 7c. Grant the managed PubSub service identity write access to the dataset.
# The identity is deterministic from the project NUMBER (not ID).
# Note: `bq add-iam-policy-binding` for dataset-scoped grants is behind an
# allowlist on some projects, so we grant project-level roles via gcloud
# instead — slightly broader scope, acceptable for a demo.
PROJECT_NUMBER=$(gcloud projects describe $PROJECT --format='value(projectNumber)')
PUBSUB_SA=service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com
gcloud projects add-iam-policy-binding $PROJECT --condition=None \
  --member="serviceAccount:$PUBSUB_SA" \
  --role=roles/bigquery.dataEditor
gcloud projects add-iam-policy-binding $PROJECT --condition=None \
  --member="serviceAccount:$PUBSUB_SA" \
  --role=roles/bigquery.metadataViewer

# 7d. Subscription with --use-table-schema. The subscription parses each
# PubSub message body as JSON and maps top-level fields by name to BQ columns
# (CASE-SENSITIVE match — PageHitEvent JSON uses snake_case field names to
# match the BQ schema). --drop-unknown-fields tolerates extra JSON fields we
# don't model as columns. We skip --write-metadata because it requires 4
# specific metadata columns (publish_time, subscription_name, message_id,
# attributes) and we don't need them for Chart 1 — the event timestamp alone
# is enough. --dead-letter-topic routes messages that fail the schema check
# (missing required fields, bad JSON) to a DLQ instead of silently dropping
# them; the --max-delivery-attempts bound stops the subscription from
# retrying a broken message forever.
gcloud pubsub subscriptions create threat-page-hits-to-bq \
  --project=$PROJECT \
  --topic=threat-page-hits \
  --topic-project=$PROJECT \
  --bigquery-table=${PROJECT}:beam_threat_detection.page_hits_raw \
  --use-table-schema \
  --drop-unknown-fields \
  --dead-letter-topic=threat-page-hits-raw-dlq \
  --max-delivery-attempts=5
```

**Checking the DLQ:**

```bash
# Pull any messages that arrived in the DLQ (ad-hoc inspection).
gcloud pubsub subscriptions create threat-page-hits-raw-dlq-pull \
  --project=$PROJECT --topic=threat-page-hits-raw-dlq --topic-project=$PROJECT
gcloud pubsub subscriptions pull threat-page-hits-raw-dlq-pull \
  --project=$PROJECT --limit=10 --auto-ack --format=json
```

### 8. Launching the pipelines on Dataflow

Both pipelines are launched via `mvn exec:java -Pdataflow-runner`; the
submission wrappers live under `bin/` and record every argument so the
exact invocation is reproducible.

```bash
# Streaming ingest (PubSub → VoltDB). One-time per demo cycle; cancel the
# running job before re-launching.
./bin/run-ingest-pipeline.sh

# Batch reporting (VoltDB → BigQuery + Iceberg).
#   Fresh full-pull backfill from epoch 0 (default):
./bin/run-reporting-pipeline.sh
#   Resume from the current BQ MAX(TXN_TIME) (production cadence):
INITIAL_WATERMARK_MS=-1 ./bin/run-reporting-pipeline.sh
#   BQ-only run (skip Iceberg mirror):
WRITE_ICEBERG=false ./bin/run-reporting-pipeline.sh
```

All env-var overrides and the full `--runner / --project / --region /
--tempLocation / --voltdbHosts / --initialWatermarkMs / ...` list are in the
scripts themselves. Treat the scripts as source of truth; prefer editing them
over inlining long `mvn` commands here.

### 8a. Smoke-test the full ingest chain

```bash
# Publish a short burst to real PubSub.
mvn exec:java -Dexec.mainClass=org.voltdb.example.threat.generator.PageHitsGenerator \
  -Dexec.args="--mode=burst --subnet=203.0.113 --rate=100 --duration=5"

# Verify rows arrived in BQ (~1-2 min lag typical for PubSub-BQ):
bq --project_id=$PROJECT query --use_legacy_sql=false \
  "SELECT COUNT(*) FROM \`$PROJECT.beam_threat_detection.page_hits_raw\`
   WHERE DATE(timestamp) = CURRENT_DATE();"
```

### 8b. Chart-ready scenario data (NarrativeScene)

Fires a single coordinated scenario that produces data for both charts:
a 120s page-hit timeline (baseline → burst → tail) + 12 attacker-subnet
transactions timed to the burst phases.

```bash
# Pre-reqs: streaming ingest Dataflow job already running
#   (./bin/run-ingest-pipeline.sh), plus a port-forward for the
#   TransactionsGenerator half of the scenario.
kubectl --context=$CTX -n $NS port-forward svc/${REL}-voltdb-cluster-client 21212:21212 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null' EXIT

./bin/run-narrative-scene.sh
# Env-var overrides: HOST, PORT, SUBNET, TOPIC, ANCHOR_MS — see the script header.

# Fire the batch reporting pipeline to pull the new txns into BQ + Iceberg:
./bin/run-reporting-pipeline.sh
```

## Operations and teardown

### Watermark reset (Path 3 — reporting pipeline)

The reporting pipeline's watermark is sink-driven: at the top of each run it
reads `SELECT MAX(txn_time) FROM $PROJECT.beam_threat_detection.transactions`.
There is no dedicated watermark store — advancing or rewinding the "cursor"
is a BigQuery operation.

```bash
# Rewind the pipeline so the NEXT run re-reads from a specific point:
#   DELETE everything written after a given cutoff. Replace <CUTOFF-TIMESTAMP>
#   with the ISO-8601 instant you want the next run to re-read from.
bq --project_id=$PROJECT query --use_legacy_sql=false \
  "DELETE FROM \`$PROJECT.beam_threat_detection.transactions\`
   WHERE txn_time >= TIMESTAMP('<CUTOFF-TIMESTAMP>');"

# Rewind to zero (next run reads all transactions in VoltDB from the beginning):
bq --project_id=$PROJECT query --use_legacy_sql=false \
  "TRUNCATE TABLE \`$PROJECT.beam_threat_detection.transactions\`;"
```

Because the Iceberg mirror is written by the same pipeline in parallel with
the BigQuery sink, a BigQuery-side rewind only "un-advances" the watermark;
Iceberg rows already written for the rewound range will be re-written on the
next run (same `TXN_ID` keys) — append-only duplicates until a `MERGE`-based
dedupe is added.

### Teardown

Order matters — pipelines first, then VoltDB, then (optionally) the shared
BQ/PubSub artifacts.

```bash
# 1. Cancel Dataflow jobs (both streaming and batch).
gcloud dataflow jobs list --project=$PROJECT --region=$REGION --status=active \
  --format='value(id)' | while read JOB_ID; do
    gcloud dataflow jobs cancel $JOB_ID --project=$PROJECT --region=$REGION
  done

# 2. Delete any lingering ephemeral PubSub subscriptions Dataflow created
#    for its own use (they are safe to delete; they re-create on next run).
gcloud pubsub subscriptions list --project=$PROJECT \
  --filter='name:threat-page-hits_beam_' --format='value(name)' | while read SUB; do
    gcloud pubsub subscriptions delete $SUB --project=$PROJECT
  done

# 3. Tear down the VoltDB cluster — namespace-scoped. If the GKE cluster is
#    shared with other workloads, do NOT delete the cluster itself.
helm uninstall $REL -n $NS --kube-context $CTX
kubectl --context=$CTX delete pvc -n $NS --all --wait=false
kubectl --context=$CTX delete ns $NS

# 4. (Optional) wipe BQ demo data without dropping the tables:
bq --project_id=$PROJECT query --use_legacy_sql=false \
  "TRUNCATE TABLE \`$PROJECT.beam_threat_detection.transactions\`;
   TRUNCATE TABLE \`$PROJECT.beam_threat_detection.page_hits_raw\`;"

# 5. (Optional, rarely needed) drop the entire dataset. Deletes the Iceberg
#    files on GCS as well via the connection reference.
bq --project_id=$PROJECT rm -r -f -d $PROJECT:beam_threat_detection
```

The BigLake connection, GCS bucket, PubSub topics, and IAM grants persist
across teardowns — they're near-zero cost at rest and reusable for the next
demo cycle.

### Cost controls

Dominant cost line items when the demo is running:

- **GKE VoltDB cluster** — a 1-node VoltDB pod draws roughly one n2-standard-2
  worth of resource on whatever GKE cluster hosts it. Low cost but not zero.
  **Teardown lever:** `helm uninstall` the release (above).
- **Dataflow streaming job** (`PageHitsIngestPipeline`) — autoscales with
  event rate; at demo volumes stays near its minimum worker count (1-2
  n1-standard-2 workers). **Teardown lever:** cancel the job.
- **Dataflow batch job** (`ReportingPipeline`) — if wired to Cloud Scheduler
  on a cadence (e.g. every 5 min), each run is 1-2 min on 1 worker.
  **Teardown lever:** pause/delete the Cloud Scheduler job.
- **BigQuery storage + queries** — pennies per month at demo volumes.
- **GCS storage** — Iceberg files for `page_hits_raw` and for the
  `transactions` mirror written by the reporting pipeline, plus Dataflow
  staging. Pennies per month at demo volumes.

Between demo runs: keep PubSub topics, BQ tables, and the GCS bucket (all
near-zero cost); tear down the VoltDB release and both Dataflow jobs.
Reprovisioning takes ~10 min end-to-end via the §4 and §7 commands above.

## Followup: harden REQUEST_ID for at-least-once retries

`PageHitsIngestPipeline.EventToRowFn` currently generates `REQUEST_ID` via
`ThreadLocalRandom.nextLong()` per element. Under Beam's at-least-once delivery
guarantee (PubSub source + `VoltDbIO.write` async pipelined) a bundle can be
retried; a re-processed message gets a NEW random `REQUEST_ID` and is inserted
as a duplicate row in `SUBNET_REQUESTS`. That's acceptable for a rate-rule
counter (the `REQUESTS_PER_SUBNET` view over a 5s window is best-effort by
design; a slight over-count only makes detection more sensitive), but if
exactly-once semantics on the counter are needed, switch to a deterministic ID.

Options:
- Hash the PubSub message ID (available on `PubsubMessage.getMessageId()`
  after `PubsubIO.readMessages()` — note: the ID may only be populated for
  the Dataflow runner, not the DirectRunner used by the IT).
- Hash the payload bytes (deterministic but larger risk of collision at high
  duplicate volume).
- Have the generator stamp a UUID/monotonic ID into the JSON payload and use
  that.