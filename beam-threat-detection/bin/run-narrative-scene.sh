#!/usr/bin/env bash
# Launch the coordinated narrative scenario (NarrativeScene) that drives both
# Chart 1 and Chart 2 end-to-end: 120s page-hit timeline (baseline → burst →
# tail) + 12 attacker-subnet transactions timed to the burst phases.
#
# Prerequisites:
#   - VoltDB cluster running on GKE (central1 / beam-threat ns, release mp-threat).
#   - PageHitsIngestPipeline Dataflow Running (consumes PubSub → VoltDB).
#   - `kubectl port-forward svc/mp-threat-voltdb-cluster-client 21212:21212` for
#     localhost:21212 reach to the VoltDB client port.
#   - gcloud application-default credentials.
#
# Env-var overrides:
#   HOST=localhost  PORT=21212  SUBNET=78.46.220  TOPIC=projects/voltdb-operator/topics/threat-page-hits

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

HOST="${HOST:-localhost}"
PORT="${PORT:-21212}"
SUBNET="${SUBNET:-78.46.220}"
TOPIC="${TOPIC:-projects/voltdb-operator/topics/threat-page-hits}"
ANCHOR_MS="${ANCHOR_MS:-$(python3 -c 'import time; print(int(time.time()*1000))')}"

echo "Narrative scene starting: subnet=$SUBNET anchorMs=$ANCHOR_MS (duration ~120s)"
mvn -q exec:java \
    -Dexec.mainClass=org.voltdb.example.threat.generator.NarrativeScene \
    -Dexec.args="--host=$HOST --port=$PORT --subnet=$SUBNET --topic=$TOPIC --anchorMs=$ANCHOR_MS"