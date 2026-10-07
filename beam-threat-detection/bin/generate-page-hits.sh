#!/usr/bin/env bash
# Launch the PageHitGenerator. TODO(Phase 1): fill in real args (rate, mode,
# duration, subnet). Current form is a placeholder.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

mvn -q compile exec:java \
    -Dexec.mainClass=org.voltdb.example.threat.generator.PageHitGenerator \
    -Dexec.args="$*"