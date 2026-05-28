#!/bin/bash
# ============================================================================
# CI Test Script for VoltDB Example Applications
# ============================================================================
# Runs docker-compose tests for specified applications
#
# Usage:
#   ./ci-test.sh [app1] [app2] ...
#   ./ci-test.sh                    # Default: voter voltkv
#   ./ci-test.sh --all              # Test all apps
#
# Environment variables:
#   VOLTDB_LICENSE  - Path to VoltDB license file (required)
#   VOLTDB_IMAGE    - VoltDB Docker image (default: voltactivedata/volt-developer-edition:15.2.0_voltdb)
#   DURATION        - Benchmark duration in seconds (default: 60)
#   MODE            - Run mode: init, run, init-run (default: init-run)
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Detect docker compose command (plugin vs standalone)
if docker compose version &>/dev/null; then
    DOCKER_COMPOSE="docker compose"
elif command -v docker-compose &>/dev/null; then
    DOCKER_COMPOSE="docker-compose"
else
    echo "ERROR: Neither 'docker compose' nor 'docker-compose' found"
    exit 1
fi
echo "Using: $DOCKER_COMPOSE"

# All available apps (fraud-detection excluded - requires Kafka)
ALL_APPS="voter voltkv bank-offers metrocard ddos-detection fraud-tx-detection
          adperformance callcenter geospatial json-sessions nbbo
          positionkeeper simple uniquedevices windowing"

# Default apps to test
DEFAULT_APPS="voter voltkv"

# Parse arguments
if [ "$1" = "--all" ]; then
    APPS="$ALL_APPS"
elif [ -n "$1" ]; then
    APPS="$@"
else
    APPS="$DEFAULT_APPS"
fi

# Verify license file
if [ -z "$VOLTDB_LICENSE" ]; then
    echo "ERROR: VOLTDB_LICENSE environment variable not set"
    echo "Usage: VOLTDB_LICENSE=/path/to/license.xml $0 [apps...]"
    exit 1
fi

if [ ! -f "$VOLTDB_LICENSE" ]; then
    echo "ERROR: License file not found: $VOLTDB_LICENSE"
    exit 1
fi

# Create the shared license volume and populate it
docker volume create voltdb-license-data 2>/dev/null || true
docker run --rm \
    -v voltdb-license-data:/target \
    -v "${VOLTDB_LICENSE}:/src/license.xml:ro" \
    alpine sh -c "cp /src/license.xml /target/license.xml"
echo "License copied to voltdb-license-data volume"

# Export for docker compose
export VOLTDB_LICENSE
export VOLTDB_IMAGE="${VOLTDB_IMAGE:-voltactivedata/volt-developer-edition:15.2.0_voltdb}"
export DURATION="${DURATION:-60}"
export MODE="${MODE:-init-run}"

echo "=============================================="
echo "VoltDB Examples CI Test"
echo "=============================================="
echo "VOLTDB_IMAGE:   $VOLTDB_IMAGE"
echo "VOLTDB_LICENSE: $VOLTDB_LICENSE"
echo "DURATION:       $DURATION seconds"
echo "MODE:           $MODE"
echo "APPS:           $APPS"
echo "=============================================="
echo ""

# Track results
PASSED=""
FAILED=""

test_app() {
    local app=$1
    local app_dir="$REPO_ROOT/$app"
    local log_dir="$app_dir/logs"

    echo ""
    echo "====== Testing $app ======"

    # Verify app directory exists
    if [ ! -d "$app_dir" ]; then
        echo "ERROR: App directory not found: $app_dir"
        return 1
    fi

    # Verify docker-compose.yml exists
    if [ ! -f "$app_dir/docker-compose.yml" ]; then
        echo "ERROR: docker-compose.yml not found in $app_dir"
        return 1
    fi

    # Create logs directory
    mkdir -p "$log_dir"

    # Change to app directory
    cd "$app_dir"

    # Use unique ports for parallel execution
    export VOLTDB_CLIENT_PORT=$((21212 + RANDOM % 1000))
    export VOLTDB_ADMIN_PORT=$((21211 + RANDOM % 1000))
    export VOLTDB_HTTP_PORT=$((8080 + RANDOM % 1000))

    echo "Using ports: client=$VOLTDB_CLIENT_PORT, admin=$VOLTDB_ADMIN_PORT, http=$VOLTDB_HTTP_PORT"

    # Cleanup any existing containers
    $DOCKER_COMPOSE down -v 2>/dev/null || true

    # Start VoltDB
    echo "Starting VoltDB..."
    $DOCKER_COMPOSE up -d voltdb

    # Wait for VoltDB to be healthy
    echo "Waiting for VoltDB to be ready..."
    local retries=0
    local max_retries=60
    while [ $retries -lt $max_retries ]; do
        if $DOCKER_COMPOSE ps voltdb | grep -q "healthy"; then
            echo "VoltDB is ready!"
            break
        fi
        retries=$((retries + 1))
        if [ $retries -eq $max_retries ]; then
            echo "ERROR: VoltDB failed to start within timeout"
            $DOCKER_COMPOSE logs voltdb > "$log_dir/voltdb-startup.log" 2>&1
            $DOCKER_COMPOSE down -v
            return 1
        fi
        sleep 5
    done

    # Run the app benchmark
    echo "Running $app benchmark (duration: ${DURATION}s)..."
    local exit_code=0
    $DOCKER_COMPOSE run --rm app 2>&1 | tee "$log_dir/benchmark.log" || exit_code=$?

    # Capture VoltDB logs
    $DOCKER_COMPOSE logs voltdb > "$log_dir/voltdb.log" 2>&1

    # Cleanup
    echo "Cleaning up..."
    $DOCKER_COMPOSE down -v

    cd "$REPO_ROOT"

    if [ $exit_code -eq 0 ]; then
        echo "====== $app PASSED ======"
        return 0
    else
        echo "====== $app FAILED (exit code: $exit_code) ======"
        return 1
    fi
}

# Run tests for each app
for app in $APPS; do
    if test_app "$app"; then
        PASSED="$PASSED $app"
    else
        FAILED="$FAILED $app"
    fi
done

# Summary
echo ""
echo "=============================================="
echo "Test Summary"
echo "=============================================="
if [ -n "$PASSED" ]; then
    echo "PASSED:$PASSED"
fi
if [ -n "$FAILED" ]; then
    echo "FAILED:$FAILED"
    echo ""
    echo "Check logs in <app>/logs/ for details"
    exit 1
fi

echo ""
echo "All tests passed!"
exit 0
