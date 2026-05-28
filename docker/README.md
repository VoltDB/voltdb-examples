# Docker Support for VoltDB Examples

Each example ships a multi-stage `Dockerfile` and a `docker-compose.yml` that brings up VoltDB, loads the schema, and runs the client. This directory contains the shared build tooling: a `Makefile`, Dockerfile/compose generators, and the CI test script.


## Prerequisites

- Docker 20.10+
- Docker Compose v2 (plugin) or `docker-compose` (standalone)
- A VoltDB Developer license — free from <https://www.voltactivedata.com/build-with-volt/>


## Quick Start

```bash
# Build the voter image
make -C docker build-voter

# Run the voter stack (VoltDB + VMC + client)
export VOLTDB_LICENSE=/path/to/license.xml
docker compose -f voter/docker-compose.yml up
```


## Image Layout

All app Dockerfiles use the same base:

- **Builder stage:** `maven:3.9-eclipse-temurin-21-alpine`
- **Runtime stage:** `registry.access.redhat.com/ubi10-minimal:latest` with `java-21-openjdk-headless`
- Runs as non-root user `voltapp` (UID 10000)

Each app image contains:

```
/app/
├── client.jar      # Client application JAR
├── procs.jar       # Stored procedures JAR (if applicable)
├── lib/            # Runtime dependencies
├── ddl.sql         # Schema definition
├── data/           # Sample data (if applicable)
└── entrypoint.sh   # Shared entrypoint: waits for VoltDB, loads DDL, runs client
```

### Container Modes

The container supports three modes via the `MODE` environment variable:

| Mode       | Description                                                 |
|------------|-------------------------------------------------------------|
| `init`     | Load schema and procedures into VoltDB, then exit           |
| `run`      | Run the benchmark (assumes schema is already loaded)        |
| `init-run` | Load schema, then run the benchmark (default)               |

### Per-App Compose

Each app has its own `docker-compose.yml` so VoltDB runs in an isolated instance per example — no port conflicts when switching apps.


## Building Images

```bash
make -C docker build-voter          # Build one image
make -C docker build-all            # Build all 16 images
make -C docker clean                # Remove all voltdb-example-* images
make -C docker help                 # Full target list
```

Direct Docker invocation also works:

```bash
docker build -t voltdb-example-voter -f voter/Dockerfile .
```

Apps with Dockerfiles (16 total):

`voter`, `voltkv`, `bank-offers`, `client2`, `ddos-detection`, `fraud-tx-detection`,
`adperformance`, `callcenter`, `fraud-detection`, `geospatial`, `json-sessions`,
`nbbo`, `positionkeeper`, `simple`, `uniquedevices`, `windowing`.

> `fraud-detection` has a Dockerfile but requires external Apache Kafka, so it is excluded from the compose-based `ci-test.sh` harness.


## Pushing Images

Pushes go to a single Docker Hub repo, with each app tagged as `<app>-<version>`:

```bash
make -C docker push-voter           # Push one image
make -C docker push-all             # Push all images
```

By default `make push-all` pushes to `voltdb/voltdb-enterprise-dev`, producing tags like `voltdb/voltdb-enterprise-dev:voter-<version>`. Override the destination with the `DOCKERHUB_REPO` variable:

```bash
DOCKERHUB_REPO=myorg/voltdb-examples make -C docker push-all
```


## Running with Docker Compose

```bash
export VOLTDB_LICENSE=/path/to/license.xml

# Run (initializes schema + runs benchmark)
docker compose -f voter/docker-compose.yml up

# Customize duration and mode
DURATION=30 MODE=init-run docker compose -f voter/docker-compose.yml up

# Tear down
docker compose -f voter/docker-compose.yml down -v
```

### Environment Variables

Common variables (consumed by the shared entrypoint):

| Variable           | Default               | Description                                   |
|--------------------|-----------------------|-----------------------------------------------|
| `MODE`             | `init-run`            | `init`, `run`, or `init-run`                  |
| `VOLTDB_SERVERS`   | `voltdb:21212`        | VoltDB cluster connection string              |
| `DURATION`         | `120`                 | Benchmark duration (seconds)                  |
| `WARMUP`           | `5`                   | Warmup period (seconds)                       |
| `DISPLAY_INTERVAL` | `5`                   | Statistics display interval                   |
| `MAX_RETRIES`      | `60`                  | Max retries waiting for VoltDB                |
| `RETRY_INTERVAL`   | `5`                   | Seconds between retry attempts                |
| `EXTRA_ARGS`       | (empty)               | Additional benchmark arguments                |

VoltDB server variables (used by docker-compose):

| Variable         | Default                                              | Description                               |
|------------------|------------------------------------------------------|-------------------------------------------|
| `VOLTDB_LICENSE` | (required)                                           | Path to `license.xml` on the host         |
| `VOLTDB_IMAGE`   | `voltactivedata/volt-developer-edition:15.2.0_voltdb`| VoltDB server image                       |
| `VMC_IMAGE`      | `voltactivedata/volt-developer-edition:15.2.0_vmc`   | VMC console image                         |

App-specific variables:

| App       | Variable          | Default    | Description                          |
|-----------|-------------------|------------|--------------------------------------|
| `voter`   | `CONTESTANTS`     | `6`        | Number of contestants                |
| `voter`   | `MAX_VOTES`       | `2`        | Max votes per phone number           |
| `voltkv`  | `POOL_SIZE`       | `100000`   | Key pool size                        |
| `voltkv`  | `GET_PUT_RATIO`   | `0.90`     | Read/write ratio                     |


## CI/CD

Two Jenkins pipelines live at the repo root:

- **`Jenkinsfile`** — Maven build, integration tests, optional benchmarks. Uses `VOLTDB_IMAGE=voltdb/voltdb-enterprise:15.1.0` by default (the gated enterprise image).
- **`Jenkinsfile.docker`** — Multi-arch docker image builds, compose-based tests, Docker Hub push on master, and Docker Desktop Extension publish (opt-in).

Key `Jenkinsfile.docker` parameters:

| Parameter           | Default                                                       | Description                                               |
|---------------------|---------------------------------------------------------------|-----------------------------------------------------------|
| `VOLTDB_IMAGE`      | `voltdb/voltdb-enterprise:15.1.0`                             | VoltDB server image for tests                             |
| `TEST_APPS`         | all 15 apps except `client2`                                  | Comma-separated apps to run through `ci-test.sh`          |
| `BENCHMARK_DURATION`| `60`                                                          | Seconds per app                                           |
| `SKIP_TESTS`        | `false`                                                       | Build only, skip compose tests                            |
| `BUILD_EXTENSION`   | `true`                                                        | Build the Docker Desktop Extension image                  |
| `PUSH_EXTENSION`    | `false`                                                       | Push the extension (master branch only)                   |
| `EXTENSION_REPO`    | `voltdb/voltdb-enterprise-dev`                                | Extension repo; public `voltactivedata/*` is master-only  |
| `EXTENSION_TAG`     | `1.0.0-demo-docker-extension`                                 | Semver-compliant marketplace tag                          |

### Running the CI test script locally

```bash
VOLTDB_LICENSE=/path/to/license.xml ./docker/scripts/ci-test.sh              # voter + voltkv (default)
VOLTDB_LICENSE=/path/to/license.xml ./docker/scripts/ci-test.sh voter        # specific apps
VOLTDB_LICENSE=/path/to/license.xml ./docker/scripts/ci-test.sh --all        # every app
```

The script auto-detects `docker compose` vs `docker-compose`, uses random host ports, captures logs to `<app>/logs/`, and reports pass/fail per app.


## Generators

The Dockerfiles and compose files are generated from templates — don't edit them by hand unless you know what you're doing.

```bash
./docker/scripts/generate-dockerfiles.sh --force    # Regenerate all Dockerfiles
./docker/scripts/generate-compose.sh --force        # Regenerate all docker-compose.yml files
```


## Troubleshooting

| Symptom                       | Check                                                                                             |
|-------------------------------|---------------------------------------------------------------------------------------------------|
| VoltDB won't start            | `docker compose -f <app>/docker-compose.yml logs voltdb`                                          |
| Client can't connect          | `docker compose -f <app>/docker-compose.yml ps` — make sure `voltdb` is healthy                   |
| Build failures                | `make -C docker clean && make -C docker build-<app>`                                              |
| Compose plugin missing        | `docker compose version` — install V2: <https://docs.docker.com/compose/install/>                 |
| License not found             | Set `VOLTDB_LICENSE=/path/to/license.xml` before `docker compose up`                              |
| Gated image pull fails        | Switch to the public image: `VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb ...`|
