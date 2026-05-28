# JSON Sessions — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building, running, and customizing the json-sessions example.

Building with Maven
---------------------------
The json-sessions example can be built using Maven:

```bash
# From the voltdb-examples root directory
mvn clean package -pl json-sessions -DskipTests

# Or from the json-sessions directory
cd json-sessions
../mvnw clean package -DskipTests
```

Running with Docker
---------------------------
The easiest way to run the benchmark is using Docker with the Maven benchmark profile:

```bash
# Prerequisites: Docker running, VoltDB Developer license at ~/license.xml or VOLTDB_LICENSE env var

# Run the benchmark (starts VoltDB in Docker, loads schema, runs client)
mvn verify -pl json-sessions -Pbenchmark -DskipTests

# Use a different VoltDB version
VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb mvn verify -pl json-sessions -Pbenchmark -DskipTests
```

This will:
1. Start VoltDB Developer Edition in a Docker container
2. Load the DDL schema and stored procedures
3. Run the JSONClient for 60 seconds
4. Shut down cleanly

### Docker Compose

Run against a local VoltDB container with docker-compose:

```bash
# Set your VoltDB Developer license path
export VOLTDB_LICENSE=/path/to/license.xml

# Build the json-sessions image
make -C docker build-json-sessions

# Run (initializes schema + runs benchmark)
docker compose -f json-sessions/docker-compose.yml up

# Run with custom settings
DURATION=60 docker compose -f json-sessions/docker-compose.yml up

# Clean up
docker compose -f json-sessions/docker-compose.yml down -v
```

See [docker/README.md](../docker/README.md) for more details.

Local VoltDB
---------------------------
Make sure "bin" inside the VoltDB kit is in your PATH.  Then open a shell and go to the examples/json-sessions directory, then execute the following commands to start the database:

    voltdb init
    voltdb start

Wait until you see "Server completed initialization."
Open a new shell in the same directory and run jar util to generate required jars:

    ./run.sh jars

Now run the following to load the schema:

    sqlcmd < ddl.sql

In the same shell, run the following script to preload some data and run the demo client application:

    ./run.sh client

You can stop the server or running client at any time with `ctrl-c` or `SIGINT`.  Of course VoltDB can also run in the background using the -B option, in which case you can stop it with the `voltadmin shutdown` command.

Note that the downloaded VoltDB kits include pre-compiled stored procedures and client code as jarfiles. To run the example from a source build, it may be necessary to compile the Java source code by typing "run.sh jars" before step 3 above. Note that this step requires a full Java JDK.

run.sh reference
---------------------------
VoltDB examples come with a run.sh shell script that simplifies compiling and running the example client application and other parts of the examples.
- *run.sh* : start the server
- *run.sh server* : start the server
- *run.sh init* : compile stored procedures and load the schema and stored procedures
- *run.sh jars* : compile all Java clients and stored procedures into two Java jarfiles
- *run.sh client* : start the client, more than 1 client is permitted
- *run.sh clean* : remove compilation and runtime artifacts
- *run.sh cleanall* : remove compilation and runtime artifacts *and* the two included jarfiles

If you change the client or procedure Java code, you must recompile the jars by deleting them in the shell or using `./run.sh jars`.


Customizing this Example
---------------------------
See the "deployment-examples" directory within the "examples" directory for ways to alter the default single-node, no authorization deployment style of the examples. There are readme files and example deployment XML files for different clustering, authorization, export, logging and persistence settings.
