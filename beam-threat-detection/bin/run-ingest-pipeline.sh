#!/usr/bin/env bash
# Launch the PageHitsIngestPipeline (Beam streaming, Path 2) on Google Cloud Dataflow.
#
# Streams PubSub `threat-page-hits` events into VoltDB via RecordSubnetRequest SP.
# Unparseable messages go to the DLQ topic for inspection.
#
# Prerequisites (all one-time, see RUNBOOK §3, §4, §5):
#   - VoltDB cluster running on GKE (central1 / beam-threat ns, release mp-threat).
#   - Internal LB `mp-threat-voltdb-dataflow-ilb` with EXTERNAL-IP = $VOLTDB_ILB.
#   - PubSub topics `threat-page-hits` + `threat-page-hits-dlq`.
#   - gcloud application-default credentials: `gcloud auth application-default login`.
#
# Overridable via env vars (all have working defaults):
#   PROJECT, REGION, BUCKET, VOLTDB_ILB, INPUT_TOPIC, DLQ_TOPIC, NUM_WORKERS,
#   SUBNET_PREFIX_LENGTH

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

PROJECT="${PROJECT:-voltdb-operator}"
REGION="${REGION:-us-central1}"
BUCKET="${BUCKET:-mpopova_volt_central1}"
VOLTDB_ILB="${VOLTDB_ILB:-10.128.0.40}"
INPUT_TOPIC="${INPUT_TOPIC:-projects/${PROJECT}/topics/threat-page-hits}"
DLQ_TOPIC="${DLQ_TOPIC:-projects/${PROJECT}/topics/threat-page-hits-dlq}"
NUM_WORKERS="${NUM_WORKERS:-1}"
SUBNET_PREFIX_LENGTH="${SUBNET_PREFIX_LENGTH:-24}"

JOB_NAME="page-hits-ingest-$(date -u +%Y%m%d-%H%M%S)"

mvn -q compile exec:java -Pdataflow-runner \
    -Dexec.mainClass=org.voltdb.example.threat.pipelines.PageHitsIngestPipeline \
    -Dexec.args="\
        --runner=DataflowRunner \
        --project=${PROJECT} \
        --region=${REGION} \
        --tempLocation=gs://${BUCKET}/dataflow/temp \
        --stagingLocation=gs://${BUCKET}/dataflow/staging \
        --jobName=${JOB_NAME} \
        --streaming=true \
        --numWorkers=${NUM_WORKERS} \
        --maxNumWorkers=${NUM_WORKERS} \
        --voltdbHosts=${VOLTDB_ILB}:21212 \
        --inputTopic=${INPUT_TOPIC} \
        --deadLetterTopic=${DLQ_TOPIC} \
        --subnetPrefixLength=${SUBNET_PREFIX_LENGTH} \
        $*"