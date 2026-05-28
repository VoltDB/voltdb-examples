# Voltkv — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building, running, and customizing the voltkv example.

Building with Maven
-----------
The voltkv example can be built using Maven:

```bash
# From the voltdb-examples root directory
mvn clean package -pl voltkv -DskipTests

# Or from the voltkv directory
cd voltkv
../mvnw clean package -DskipTests
```

Running with Docker
-----------
The easiest way to run the benchmark is using Docker with the Maven benchmark profile:

```bash
# Prerequisites: Docker running, VoltDB Developer license at ~/license.xml or VOLTDB_LICENSE env var

# Run the benchmark (starts VoltDB in Docker, loads schema, runs client)
mvn verify -pl voltkv -Pbenchmark -DskipTests

# Use a different VoltDB version
VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb mvn verify -pl voltkv -Pbenchmark -DskipTests
```

This will:
1. Start VoltDB Developer Edition in a Docker container
2. Load the DDL schema
3. Run the AsyncBenchmark client for 60 seconds
4. Shut down cleanly

### Docker Compose

Run against a local VoltDB container with docker-compose:

```bash
# Set your VoltDB Developer license path
export VOLTDB_LICENSE=/path/to/license.xml

# Build the voltkv image
make -C docker build-voltkv

# Run (initializes schema + runs benchmark)
docker compose -f voltkv/docker-compose.yml up

# Run with custom settings
DURATION=60 docker compose -f voltkv/docker-compose.yml up

# Clean up
docker compose -f voltkv/docker-compose.yml down -v
```

See [docker/README.md](../docker/README.md) for more details.

Local VoltDB
-----------
Make sure "bin" inside the VoltDB kit is in your PATH.  Then open a shell and go to the examples/voltkv directory, then execute the following commands to start the database:

    voltdb init
    voltdb start

Wait until you see "Server completed initialization."
Open a new shell in the same directory and run jar util to generate required jars:

    ./run.sh jars

Now run the following to load the schema:

    sqlcmd < ddl.sql

In the same shell, run the following script to preload some data and run the demo client application:

    ./run.sh client

You can stop the server or running client at any time with `Ctrl-c` or `SIGINT`.  Of course VoltDB can also run in the background using the -B option, in which case you can stop it with the `voltadmin shutdown` command.

run.sh reference
---------------------------
VoltDB examples come with a run.sh shell script that simplifies compiling and running the example client application and other parts of the examples.
- *run.sh* : start the server
- *run.sh server* : start the server
- *run.sh init* : compile stored procedures and load the schema and stored procedures
- *run.sh jars* : compile all Java clients into a Java jarfile
- *run.sh client* : start the async client benchmark, initialize the given number of key-value pairs (puts) if needed, and begin normal client processing (gets and puts)
- *run.sh async-benchmark* : same as run.sh client
- *run.sh sync-benchmark* : start the multi-threaded sync client,  initialize the given number of key-value pairs (puts) if needed, and begin normal client processing (gets and puts)
- *run.sh jdbc-benchmark* : start the JDBC client benchmark
- *run.sh clean* : remove compiled and other runtime artifacts
- *run.sh cleanall* : remove compilation and runtime artifacts *and* the included client jarfile
