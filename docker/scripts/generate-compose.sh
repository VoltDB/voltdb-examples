#!/bin/bash
# ============================================================================
# Generate docker-compose.yml for each VoltDB Example Application
# ============================================================================
# Creates per-app docker-compose files for isolated testing
# Each app gets its own VoltDB instance for parallel test execution
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# All apps with Dockerfiles (fraud-detection excluded - requires Kafka)
APPS="voter voltkv bank-offers metrocard ddos-detection fraud-tx-detection
      adperformance callcenter geospatial json-sessions nbbo
      positionkeeper simple uniquedevices windowing"

generate_compose() {
    local app_name="$1"
    local compose_file="$REPO_ROOT/$app_name/docker-compose.yml"

    echo "Generating docker-compose.yml for $app_name..."

    cat > "$compose_file" << 'EOF'
# ============================================================================
# Docker Compose for APP_NAME Example
# ============================================================================
# Usage:
#   # Set license path and run
#   docker-compose up
#
#   # Run with custom duration
#   DURATION=60 docker-compose up
#
#   # Init only (load schema)
#   MODE=init docker-compose up
# ============================================================================

services:
  voltdb:
    image: ${VOLTDB_IMAGE:-voltactivedata/volt-developer-edition:15.2.0_voltdb}
    container_name: APP_NAME-voltdb
    hostname: voltdb
    command: /opt/voltdb/tools/kubernetes/test-entrypoint.sh
    ports:
      - "${VOLTDB_CLIENT_PORT:-21212}:21212"
      - "${VOLTDB_ADMIN_PORT:-21211}:21211"
      - "${VOLTDB_HTTP_PORT:-8080}:8080"
    environment:
      VOLTDB_START_CONFIG: "--ignore=thp -c 1 -H voltdb"
      VOLTDB_LICENSE: "/etc/voltdb/license.xml"
      VOLTDB_DIR: /voltdb
      VOLTDB_INIT_FORCE: "true"
    volumes:
      - voltdb-data:/voltdb
      - voltdb-license-data:/etc/voltdb:ro
    healthcheck:
      test: ["CMD-SHELL", "sqlcmd --query='exec @Ping;' 2>/dev/null || exit 1"]
      interval: 10s
      timeout: 10s
      retries: 12
      start_period: 30s
    networks:
      - app-net

  app:
    image: voltdb-example-APP_NAME:latest
    container_name: APP_NAME-client
    depends_on:
      voltdb:
        condition: service_healthy
    environment:
      VOLTDB_SERVERS: voltdb:21212
      MODE: ${MODE:-init-run}
      DURATION: ${DURATION:-120}
      WARMUP: ${WARMUP:-5}
      DISPLAY_INTERVAL: ${DISPLAY_INTERVAL:-5}
      APP_NAME: APP_NAME
    networks:
      - app-net

  vmc:
    image: ${VMC_IMAGE:-voltactivedata/volt-developer-edition:15.2.0_vmc}
    platform: linux/amd64
    container_name: APP_NAME-vmc
    command: java -jar /opt/voltdb/volt-vmc-svc.jar --servers=voltdb --port=21212
    profiles:
      - vmc
    depends_on:
      voltdb:
        condition: service_healthy
    ports:
      - "${VMC_PORT:-8081}:8080"
    environment:
      # This is to trick vmc into running as a standalone as we provide CLI args to run.
      VOLTDB_CONTAINER: docker
    networks:
      - app-net

volumes:
  voltdb-data:
  voltdb-license-data:
    external: true

networks:
  app-net:
    driver: bridge
EOF

    # Replace APP_NAME placeholder with actual app name
    sed -i.bak "s/APP_NAME/$app_name/g" "$compose_file"
    rm -f "${compose_file}.bak"

    echo "  Created $compose_file"
}

main() {
    echo "Generating docker-compose.yml files for VoltDB examples..."
    echo ""

    for app in $APPS; do
        generate_compose "$app"
    done

    echo ""
    echo "Done! Generated docker-compose.yml for $(echo $APPS | wc -w | tr -d ' ') applications."
    echo ""
    echo "Usage:"
    echo "  cd <app-name>"
    echo "  VOLTDB_LICENSE=/path/to/license.xml docker-compose up"
}

main "$@"
