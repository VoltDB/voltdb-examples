#!/usr/bin/env bash
# Launch the ReportingPipeline (Beam batch, Path 3) on Google Cloud Dataflow.
#
# Reads new rows from VoltDB TRANSACTIONS since the watermark, enriches with
# GeoIP, writes to BigQuery `transactions` + Iceberg mirror on GCS.
#
# Prerequisites (all one-time, see RUNBOOK §4, §5, §7):
#   - VoltDB cluster running on GKE (central1 / beam-threat ns, release mp-threat).
#   - Internal LB `mp-threat-voltdb-dataflow-ilb` with EXTERNAL-IP = $VOLTDB_ILB.
#   - BQ dataset `beam_threat_detection` in us-central1 with `transactions` table.
#   - GeoLite2-City.mmdb uploaded to gs://$BUCKET/geoip/.
#   - gcloud application-default credentials: `gcloud auth application-default login`.
#
# Overridable via env vars (all have working defaults):
#   PROJECT, REGION, BUCKET, VOLTDB_ILB, INITIAL_WATERMARK_MS, WRITE_ICEBERG,
#   BQ_DATASET, BQ_TABLE, ICEBERG_WAREHOUSE, ICEBERG_TABLE, GEOIP_MMDB
#
# Common run shapes:
#   # fresh full-pull backfill, both sinks:
#   ./bin/run-reporting-pipeline.sh
#   # BQ-only run (skip Iceberg mirror):
#   WRITE_ICEBERG=false ./bin/run-reporting-pipeline.sh
#   # resume from BQ MAX(TXN_TIME) (production cadence):
#   INITIAL_WATERMARK_MS=-1 ./bin/run-reporting-pipeline.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

PROJECT="${PROJECT:-voltdb-operator}"
REGION="${REGION:-us-central1}"
BUCKET="${BUCKET:-mpopova_volt_central1}"
VOLTDB_ILB="${VOLTDB_ILB:-10.128.0.40}"
INITIAL_WATERMARK_MS="${INITIAL_WATERMARK_MS:-0}"
WRITE_ICEBERG="${WRITE_ICEBERG:-true}"
BQ_DATASET="${BQ_DATASET:-beam_threat_detection}"
BQ_TABLE="${BQ_TABLE:-transactions}"
ICEBERG_WAREHOUSE="${ICEBERG_WAREHOUSE:-gs://${BUCKET}/iceberg/}"
ICEBERG_TABLE="${ICEBERG_TABLE:-hadoop.beam_threat_detection.transactions}"
GEOIP_MMDB="${GEOIP_MMDB:-gs://${BUCKET}/geoip/GeoLite2-City.mmdb}"

JOB_NAME="reporting-$(date -u +%Y%m%d-%H%M%S)"

mvn -q compile exec:java -Pdataflow-runner \
    -Dexec.mainClass=org.voltdb.example.threat.pipelines.ReportingPipeline \
    -Dexec.args="\
        --runner=DataflowRunner \
        --project=${PROJECT} \
        --region=${REGION} \
        --tempLocation=gs://${BUCKET}/dataflow/temp \
        --stagingLocation=gs://${BUCKET}/dataflow/staging \
        --jobName=${JOB_NAME} \
        --voltdbHosts=${VOLTDB_ILB}:21212 \
        --initialWatermarkMs=${INITIAL_WATERMARK_MS} \
        --bqDataset=${BQ_DATASET} \
        --bqTable=${BQ_TABLE} \
        --writeIceberg=${WRITE_ICEBERG} \
        --icebergWarehouse=${ICEBERG_WAREHOUSE} \
        --icebergTable=${ICEBERG_TABLE} \
        --geoipDbGcsUri=${GEOIP_MMDB} \
        $*"
