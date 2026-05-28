#!/bin/bash
#
# Universal entrypoint for VoltDB example applications
# Supports three modes:
#   - init: Load schema and procedures into VoltDB cluster
#   - run: Run the benchmark/client application
#   - init-run: Do both (default)
#
set -e

# =============================================================================
# Configuration (set via environment variables)
# =============================================================================
MODE=${MODE:-init-run}                    # init, run, or init-run

# Build VOLTDB_SERVERS from RELEASE if not explicitly set
# For VoltDB Helm chart, service is: <release>-voltdb-cluster-client:21212
if [ -n "${RELEASE}" ] && [ -z "${VOLTDB_SERVERS}" ]; then
    VOLTDB_SERVERS="${RELEASE}-voltdb-cluster-client:21212"
fi
VOLTDB_SERVERS=${VOLTDB_SERVERS:-localhost:21212}
VOLTDB_ADMIN_PORT=${VOLTDB_ADMIN_PORT:-21211}

# App-specific (set by Dockerfile or override)
APP_NAME=${APP_NAME:-unknown}
MAIN_CLASS=${MAIN_CLASS:-}
DDL_FILE=${DDL_FILE:-/app/ddl.sql}
PROCS_JAR=${PROCS_JAR:-/app/procs.jar}
CLIENT_JAR=${CLIENT_JAR:-/app/client.jar}

# Benchmark defaults
DURATION=${DURATION:-120}
WARMUP=${WARMUP:-5}
DISPLAY_INTERVAL=${DISPLAY_INTERVAL:-5}
TARGET_TPS=${TARGET_TPS:-1000}

# Argument style: 'named' (default) or 'positional'
# Apps like ddos-detection, fraud-tx-detection, simple use positional args
ARG_STYLE=${ARG_STYLE:-named}

# CSV data files to load after DDL (space-separated list of table:file pairs)
# Example: CSV_LOADS="mc_stations:data/stations.csv"
CSV_LOADS=${CSV_LOADS:-}

# Connection retry settings
MAX_RETRIES=${MAX_RETRIES:-60}
RETRY_INTERVAL=${RETRY_INTERVAL:-5}

# =============================================================================
# Helper Functions
# =============================================================================

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

error() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" >&2
}

# Get first server host:port for sqlcmd
get_first_server() {
    echo "${VOLTDB_SERVERS}" | cut -d',' -f1
}

# Wait for VoltDB to be ready
wait_for_voltdb() {
    local server=$(get_first_server)
    local host=$(echo "$server" | cut -d':' -f1)
    local port=$(echo "$server" | cut -d':' -f2)
    port=${port:-21212}

    log "Waiting for VoltDB at $host:$port to be ready..."

    local retries=0
    while [ $retries -lt $MAX_RETRIES ]; do
        # Try TCP connection check using bash /dev/tcp or nc
        if (exec 3<>/dev/tcp/$host/$port) 2>/dev/null; then
            exec 3<&-
            exec 3>&-
            # Give VoltDB a moment to fully initialize after port is open
            sleep 3
            log "VoltDB is ready!"
            return 0
        elif command -v nc >/dev/null 2>&1; then
            if nc -z "$host" "$port" 2>/dev/null; then
                sleep 3
                log "VoltDB is ready!"
                return 0
            fi
        fi

        retries=$((retries + 1))
        log "VoltDB not ready yet (attempt $retries/$MAX_RETRIES), waiting ${RETRY_INTERVAL}s..."
        sleep $RETRY_INTERVAL
    done

    error "Timed out waiting for VoltDB to be ready"
    return 1
}

# =============================================================================
# Init Functions
# =============================================================================

load_classes() {
    local server=$(get_first_server)

    if [ ! -f "$PROCS_JAR" ]; then
        log "No procedures JAR found at $PROCS_JAR, skipping class loading"
        return 0
    fi

    log "Loading classes from $PROCS_JAR..."

    # LOAD CLASSES command
    echo "LOAD CLASSES $PROCS_JAR;" | sqlcmd --servers="$server"

    if [ $? -eq 0 ]; then
        log "Classes loaded successfully"
    else
        error "Failed to load classes"
        return 1
    fi
}

load_schema() {
    local server=$(get_first_server)

    if [ ! -f "$DDL_FILE" ]; then
        error "DDL file not found at $DDL_FILE"
        return 1
    fi

    log "Loading schema from $DDL_FILE..."

    # Load classes first if we have a procedures JAR
    # Retry logic for concurrent catalog updates
    if [ -n "$PROCS_JAR" ] && [ -f "$PROCS_JAR" ]; then
        log "Loading classes from $PROCS_JAR..."
        local load_retries=0
        local max_load_retries=30
        while [ $load_retries -lt $max_load_retries ]; do
            output=$(echo "LOAD CLASSES $PROCS_JAR;" | sqlcmd --servers="$server" 2>&1)
            if [ $? -eq 0 ]; then
                log "Classes loaded successfully"
                break
            elif echo "$output" | grep -q "another one is in progress"; then
                load_retries=$((load_retries + 1))
                log "Catalog update in progress, retrying ($load_retries/$max_load_retries)..."
                sleep $((2 + RANDOM % 5))
            else
                error "Failed to load classes: $output"
                return 1
            fi
        done
        if [ $load_retries -ge $max_load_retries ]; then
            error "Failed to load classes after $max_load_retries retries"
            return 1
        fi
    fi

    # Create modified DDL that removes LOAD CLASSES (already loaded above)
    local temp_ddl="/tmp/ddl_modified.sql"

    # Remove LOAD CLASSES lines (case-insensitive, we loaded them above)
    grep -vi "^load classes" "$DDL_FILE" | \
    sed "s|[Ll][Oo][Aa][Dd] [Cc][Ll][Aa][Ss][Ss][Ee][Ss] [^;]*;|-- classes already loaded|gi" > "$temp_ddl"

    # Retry logic for DDL loading (in case of concurrent catalog updates)
    local ddl_retries=0
    local max_ddl_retries=30
    while [ $ddl_retries -lt $max_ddl_retries ]; do
        output=$(sqlcmd --servers="$server" < "$temp_ddl" 2>&1)
        exit_code=$?
        if [ $exit_code -eq 0 ]; then
            log "Schema loaded successfully"
            rm -f "$temp_ddl"
            break
        elif echo "$output" | grep -q "another one is in progress"; then
            ddl_retries=$((ddl_retries + 1))
            log "Catalog update in progress, retrying DDL ($ddl_retries/$max_ddl_retries)..."
            sleep $((2 + RANDOM % 5))
        else
            error "Failed to load schema: $output"
            rm -f "$temp_ddl"
            return 1
        fi
    done
    if [ $ddl_retries -ge $max_ddl_retries ]; then
        error "Failed to load schema after $max_ddl_retries retries"
        rm -f "$temp_ddl"
        return 1
    fi
}

load_csv_data() {
    local server=$(get_first_server)

    if [ -z "$CSV_LOADS" ]; then
        return 0
    fi

    log "Loading CSV data files..."

    # CSV_LOADS format: "table1:file1 table2:file2"
    for entry in $CSV_LOADS; do
        local table=$(echo "$entry" | cut -d':' -f1)
        local file=$(echo "$entry" | cut -d':' -f2)

        if [ ! -f "/app/$file" ]; then
            error "CSV file not found: /app/$file"
            return 1
        fi

        log "Loading $file into $table..."
        csvloader --servers="$server" --file="/app/$file" --reportdir=/tmp "$table"

        if [ $? -ne 0 ]; then
            error "Failed to load CSV data from $file into $table"
            return 1
        fi
        log "Loaded $file into $table"
    done

    log "CSV data loading complete"
}

do_init() {
    log "=== Initializing VoltDB for $APP_NAME ==="

    wait_for_voltdb || return 1

    # Some apps have separate JAR loading, others embed it in DDL
    # We'll try both approaches

    # First, try loading schema (which may include LOAD CLASSES)
    load_schema || return 1

    # Load CSV data if specified
    load_csv_data || return 1

    log "=== Initialization complete ==="
}

# =============================================================================
# Run Functions
# =============================================================================

do_run() {
    log "=== Running $APP_NAME benchmark ==="

    if [ -z "$MAIN_CLASS" ]; then
        error "MAIN_CLASS not set, cannot run benchmark"
        return 1
    fi

    wait_for_voltdb || return 1

    # Build classpath (some apps use fat JARs, others have separate lib/)
    local classpath="/app/client.jar"
    if [ -d "/app/lib" ] && [ "$(ls -A /app/lib 2>/dev/null)" ]; then
        classpath="$classpath:/app/lib/*"
    fi

    # Build arguments based on ARG_STYLE
    local args=""

    if [ "$ARG_STYLE" = "positional" ]; then
        # Positional arguments for apps like ddos-detection, fraud-tx-detection, simple
        # Parse host and port from VOLTDB_SERVERS (first server only)
        local server=$(get_first_server)
        local host=$(echo "$server" | cut -d':' -f1)
        local port=$(echo "$server" | cut -d':' -f2)
        port=${port:-21212}

        # Build positional args based on app
        case "$APP_NAME" in
            ddos-detection)
                # DdosDetectionApp: host port
                args="$host $port"
                ;;
            fraud-tx-detection)
                # FraudDetectionApp: host port targetTPS duration
                args="$host $port $TARGET_TPS $DURATION"
                ;;
            simple)
                # Benchmark: servers transactions
                # Note: simple expects comma-separated servers, not just host
                local transactions=$((DURATION * TARGET_TPS))
                args="$VOLTDB_SERVERS $transactions"
                ;;
            *)
                # Default positional: host port
                args="$host $port"
                ;;
        esac
    else
        # Named arguments (default for most apps)
        args="$args --servers=$VOLTDB_SERVERS"
        args="$args --duration=$DURATION"

        # Add warmup if the app supports it
        if [ -n "$WARMUP" ]; then
            args="$args --warmup=$WARMUP"
        fi

        # Add display interval if the app supports it
        if [ -n "$DISPLAY_INTERVAL" ]; then
            args="$args --displayinterval=$DISPLAY_INTERVAL"
        fi
    fi

    # App-specific extra arguments (works for both styles)
    if [ -n "$EXTRA_ARGS" ]; then
        args="$args $EXTRA_ARGS"
    fi

    log "Running: java -cp $classpath $JAVA_OPTS $MAIN_CLASS $args"

    exec java -cp "$classpath" $JAVA_OPTS "$MAIN_CLASS" $args
}

# =============================================================================
# Main
# =============================================================================

main() {
    log "Starting VoltDB Example App: $APP_NAME"
    log "Mode: $MODE"
    log "VoltDB Servers: $VOLTDB_SERVERS"

    case "$MODE" in
        init)
            do_init
            ;;
        run)
            do_run
            ;;
        init-run)
            do_init && do_run
            ;;
        shell)
            # Debug mode - just start a shell
            exec /bin/bash
            ;;
        *)
            error "Unknown mode: $MODE (valid: init, run, init-run, shell)"
            exit 1
            ;;
    esac
}

# Allow running arbitrary commands (useful for debugging)
if [ "$1" = "--" ]; then
    shift
    exec "$@"
fi

main "$@"