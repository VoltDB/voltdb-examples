# Unique Devices — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building, running, and customizing the uniquedevices example.

Building with Maven
---------------------------
The uniquedevices example can be built using Maven:

```bash
# From the voltdb-examples root directory
mvn clean package -pl uniquedevices -DskipTests

# Or from the uniquedevices directory
cd uniquedevices
../mvnw clean package -DskipTests
```

Running with Docker
---------------------------
The easiest way to run the benchmark is using Docker with the Maven benchmark profile:

```bash
# Prerequisites: Docker running, VoltDB Developer license at ~/license.xml or VOLTDB_LICENSE env var

# Run the benchmark (starts VoltDB in Docker, loads schema, runs client)
mvn verify -pl uniquedevices -Pbenchmark -DskipTests

# Use a different VoltDB version
VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb mvn verify -pl uniquedevices -Pbenchmark -DskipTests
```

This will:
1. Start VoltDB Developer Edition in a Docker container
2. Load the DDL schema and stored procedures
3. Run the UniqueDevicesClient for 60 seconds
4. Shut down cleanly

### Docker Compose

Run against a local VoltDB container with docker-compose:

```bash
# Set your VoltDB license path
export VOLTDB_LICENSE=/path/to/license.xml

# Build the uniquedevices image
make -C docker build-uniquedevices

# Run (initializes schema + runs benchmark)
docker compose -f uniquedevices/docker-compose.yml up

# Run with custom settings
DURATION=60 docker compose -f uniquedevices/docker-compose.yml up

# Clean up
docker compose -f uniquedevices/docker-compose.yml down -v
```

See [docker/README.md](../docker/README.md) for more details.

Local VoltDB
---------------------------
Make sure "bin" inside the VoltDB kit is in your PATH.  Then open a shell and go to the examples/uniquedevices directory, then execute the following commands to start the database:

    voltdb init
    voltdb start

Wait until you see "Server completed initialization."
Open a new shell in the same directory and run jar util to generate required jars:

    ./run.sh jars

Now run the following to load the schema:

    sqlcmd < ddl.sql

In the same shell, run the following script to preload some data and run the demo client application:

    ./run.sh client

Open up the index.html file the "web" directory to view the status dashboard.

If you're running the example on a VoltDB cluster, rather than your local desktop or laptop, run `./run.sh webserver` in a new shell on one of the machines in the cluster, then connect to your dashboard from your browser at [http://servername:8081](http://servername:8081).

You can stop the server, running client, or webserver at any time with `Ctrl-c` or `SIGINT`.  Of course VoltDB can also run in the background using the -B option, in which case you can stop it with the `voltadmin shutdown` command.

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
- *run.sh webserver* : serve the web directory over http on port 8081

If you change the client or procedure Java code, you must recompile the jars by deleting them in the shell or using `./run.sh jars`.

Client options
---------------------------
You can control various characteristics of the demo by modifying the parameters passed into the java application in the "client" function of the run.sh script.

**Speed & Duration:**

    --displayinterval=5           (seconds between status reports)
    --warmup=5                    (how long to warm up before measuring
                                   benchmark performance.)
    --duration=120                (benchmark duration in seconds)
    --ratelimit=20000             (run up to this rate of requests/second)

**Cluster Info:**

    --servers=$SERVERS            (host(s) client connect to, e.g.
                                   =localhost
                                   =localhost:21212
                                   =volt9a,volt9b,volt9c
                                   =foo.example.com:21212,bar.example.com:21212)

**Parameters Affecting Simulation:**

    --appcount=100                (number of distinct applications the data generator
                                   should generate. Valid values are between 1 and lots.)

Customizing this Example
---------------------------
See the "deployment-examples" directory within the "examples" directory for ways to alter the default single-node, no authorization deployment style of the examples. There are readme files and example deployment XML files for different clustering, authorization, export, logging and persistence settings.
