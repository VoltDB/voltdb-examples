# fraud-tx-detection — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building and running the fraud-tx-detection example.

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker (running)
- VoltDB Developer license (free from <https://www.voltactivedata.com/build-with-volt/>)

## Build and Test

```bash
# 1. Ensure Docker is running
docker info

# 2. Set up license
export VOLTDB_LICENSE=/path/to/voltdb-license.xml

# 3. Build
mvn clean package -DskipTests

# 4. Run tests
mvn verify
```

## Running the Application

### Local Execution

```bash
# Run against a VoltDB instance (default: localhost:21211, 1000 TPS, 30 seconds)
java -cp "target/fraud-tx-detection-1.0.jar:target/lib/*" com.example.voltdb.FraudDetectionApp

# Specify host, port, TPS, and duration
java -cp "target/fraud-tx-detection-1.0.jar:target/lib/*" com.example.voltdb.FraudDetectionApp localhost 21211 2000 60
```

### Docker Compose

Run against a local VoltDB container with docker-compose:

```bash
# Set your VoltDB license path
export VOLTDB_LICENSE=/path/to/license.xml

# Build the fraud-tx-detection image
make -C docker build-fraud-tx-detection

# Run (initializes schema + runs application)
docker compose -f fraud-tx-detection/docker-compose.yml up

# Run with custom settings
DURATION=60 docker compose -f fraud-tx-detection/docker-compose.yml up

# Clean up
docker compose -f fraud-tx-detection/docker-compose.yml down -v
```

See [docker/README.md](../docker/README.md) for more details.

The application will:
1. Connect to VoltDB
2. Deploy schema if not already present (via VoltDBSetup)
3. Initialize 1000 accounts and 100 merchants
4. Run async fraud-detection benchmark at target TPS
5. Print periodic stats and final summary
