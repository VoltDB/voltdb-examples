# VoltDB Examples

A collection of Java examples demonstrating VoltDB features, real-world use cases, and integration patterns. Each example is a complete, runnable application with DDL, stored procedures, a client, and integration tests.

These examples are a sample — not an exhaustive catalog — of what you can do with VoltDB. For feedback or requests, join us on Slack at <http://chat.voltactivedata.com>.


## Getting Started

Build everything with Maven:

```bash
# Build everything (skip tests)
mvn -DskipTests package

# Run a single benchmark against a Dockerized VoltDB
mvn -pl voter -Pbenchmark -DskipTests verify
```


## Prerequisites

| Tool     | Version       | Required for           |
|----------|---------------|------------------------|
| Java     | 17+           | Building and running   |
| Maven    | 3.6+          | Building               |
| Docker   | 20.10+        | Tests, benchmarks, containerized examples |
| VoltDB license | Developer edition | Running VoltDB server |

A free **VoltDB Developer License** is required to run the VoltDB server. Get one at <https://www.voltactivedata.com/build-with-volt/>.

Place your `license.xml` in any of these auto-detected locations, or set `VOLTDB_LICENSE`:

- `~/license.xml`
- `/tmp/voltdb-license/license.xml`
- `/opt/voltdb/voltdb-license/license.xml`


## Build, Test, Benchmark

```bash
mvn -DskipTests package                         # Build all modules
mvn -pl voter -DskipTests package               # Build one module
mvn verify                                      # Run integration tests
mvn -pl voter verify                            # Test one module
mvn -Pbenchmark -DskipTests verify              # Run all benchmarks
mvn -pl voter -Pbenchmark -DskipTests verify    # Run one benchmark
```

Override VoltDB images or library versions with environment variables:

| Variable                     | Default                                | Description                        |
|------------------------------|----------------------------------------|------------------------------------|
| `VOLTDB_IMAGE`               | `voltdb/voltdb-enterprise:15.1.0`      | VoltDB server image for tests      |
| `VMC_IMAGE`                  | `voltactivedata/volt-developer-edition:15.2.0_vmc` | VMC image for docker-compose |
| `VOLT_CLIENT_VERSION`        | `15.1.0`                               | VoltDB client library version      |
| `VOLT_PROCEDURE_API_VERSION` | `15.0.0`                               | Stored procedure API version       |
| `VOLT_TESTCONTAINER_VERSION` | `1.8.0`                                | Testcontainers helper version      |

Example:

```bash
VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb \
  mvn -pl voter -Pbenchmark -DskipTests verify
```


## Included Examples

| Module              | Domain                                                        | Benchmark |
|---------------------|---------------------------------------------------------------|-----------|
| `adperformance`     | Ad impression / click-through tracking                        | ✅        |
| `bank-offers`       | Real-time offer matching at point-of-sale                     | ✅        |
| `callcenter`        | Call-center event processing and analytics                    | ✅        |
| `client2`           | Client2 API (async with flow control)                         | —         |
| `ddos-detection`    | Real-time DDoS detection with TIME_WINDOW views               | —         |
| `fraud-tx-detection`| Transaction fraud detection with sliding windows              | —         |
| `geospatial`        | Location-based ad serving                                     | ✅        |
| `json-sessions`     | Flexible schema with JSON data                                | ✅        |
| `nbbo`              | National Best Bid / Offer (stock quotes)                      | ✅        |
| `positionkeeper`    | Trading position tracking                                     | ✅        |
| `simple`            | Basic VoltDB operations                                       | ✅        |
| `uniquedevices`     | Unique device counting with HyperLogLog                       | ✅        |
| `voltkv`            | Key-value store benchmark                                     | ✅        |
| `voter`             | Telephone voting simulation                                   | ✅        |
| `windowing`         | Time-series data with sliding windows                         | ✅        |

Excluded from the default build:

- `metrocard` — functionality covered by `fraud-detection`
- `compound-proc`, `user-hits` — use `VoltCompoundProcedure` (not in public API)
- `contentionmark` — covered by `voltkv` benchmark
- `fraud-detection` — requires Apache Kafka infrastructure


## Project Layout

Each module follows the same structure:

```
<example>/
├── ddl.sql              # Schema + stored procedure definitions
├── client/              # Client application code
├── procedures/          # Stored procedure code
├── src/test/java/       # Integration tests (*IT.java)
├── Dockerfile           # Container image (multi-stage, UBI 10 + Java 21)
├── docker-compose.yml   # Local test topology (VoltDB + VMC + client)
├── run.sh               # Traditional local runner (VoltDB on host)
└── pom.xml              # Module build
```

Parent `pom.xml` provides:
- Dependency versions via `dependencyManagement`
- Plugin configuration via `pluginManagement`
- A `benchmark` profile that runs `${benchmark.class}` in the `integration-test` phase


## Docker

Every example has a Dockerfile and a `docker-compose.yml`. See [`docker/README.md`](docker/README.md) for image build and push details.

```bash
make -C docker build-voter              # Build one image
make -C docker build-all                # Build all images
docker compose -f voter/docker-compose.yml up   # Run the whole voter stack
```


## Traditional Usage (`run.sh`)

Each module has a `run.sh` script for running against a **locally installed** VoltDB (with `bin/` on your `PATH`).

```bash
cd voter
./run.sh server          # Start VoltDB
./run.sh init            # Load DDL + procedures (in another shell)
./run.sh client          # Run the benchmark
./run.sh clean           # Remove runtime artifacts
./run.sh cleanall        # Also remove JARs
./run.sh help            # List commands
```

Source layout for `run.sh` compatibility: `client/` (or `client/src/`) and `procedures/` in addition to the standard `src/main/java/`. The Maven build compiles from all of them.


## CI/CD

Two Jenkins pipelines:

- **`Jenkinsfile`** — Maven build + integration tests + optional benchmarks
- **`Jenkinsfile.docker`** — Multi-arch Docker image builds + docker-compose tests + Docker Hub push

See the pipelines for parameters (`VOLTDB_IMAGE`, `TEST_APPS`, etc.).


## Troubleshooting

**Build fails:** Check `java -version` (needs 17+) and `mvn -version` (3.6+). Try `mvn clean`.

**Docker pull/auth errors:** The default `voltdb/voltdb-enterprise` image is gated. Switch to the public Developer Edition:
```bash
VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb mvn verify
```

**VoltDB container timeout:** Startup can take 30–60s. Increase the testcontainer timeout if needed.

**Integration tests hang:** Make sure Docker Desktop has ≥ 4 GB memory allocated.
