# Simple — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building, running, and customizing the simple example.

Building with Maven
---------------------------
The simple example can be built using Maven:

```bash
# From the voltdb-examples root directory
mvn clean package -pl simple -DskipTests

# Or from the simple directory
cd simple
../mvnw clean package -DskipTests
```

Running with Docker
---------------------------
The easiest way to run the benchmark is using Docker with the Maven benchmark profile:

```bash
# Prerequisites: Docker running, VoltDB Developer license at ~/license.xml or VOLTDB_LICENSE env var

# Run the benchmark (starts VoltDB in Docker, loads schema, runs client)
mvn verify -pl simple -Pbenchmark -DskipTests

# Use a different VoltDB version
VOLTDB_IMAGE=voltactivedata/volt-developer-edition:15.2.0_voltdb mvn verify -pl simple -Pbenchmark -DskipTests
```

This will:
1. Start VoltDB Developer Edition in a Docker container
2. Load the DDL schema and stored procedures
3. Run the SimpleBenchmark client for 60 seconds
4. Shut down cleanly

### Docker Compose

Run against a local VoltDB container with docker-compose:

```bash
# Set your VoltDB Developer license path
export VOLTDB_LICENSE=/path/to/license.xml

# Build the simple image
make -C docker build-simple

# Run (initializes schema + runs benchmark)
docker compose -f simple/docker-compose.yml up

# Run with custom settings
DURATION=60 docker compose -f simple/docker-compose.yml up

# Clean up
docker compose -f simple/docker-compose.yml down -v
```

See [docker/README.md](../docker/README.md) for more details.

Pre-requisites (Local VoltDB Installation)
--------------

Install a recent VoltDB kit and add its `bin/` directory to your `PATH`:

    export PATH="$PATH:$HOME/voltdb/bin"

Verify:

    voltdb --version

Getting Started
---------------

Start the database. In the directory of your choice, run the following commands:

    voltdb init
    voltdb start

Load the schema:

    sqlcmd < ddl.sql


Running the Benchmark
---------------------

Run the client:

    ./run_client.sh

Optional parameters for running the client

    ./run_client.sh {hostname} {number of procedure calls}

For example

    ./run_client.sh localhost 1000000

Stop the database:

    voltadmin shutdown


Modifying this benchmark
------------------------

The simplest way to make this your own benchmark is to simply:

1. Add a table to the ddl.sql file
2. Modify the Benchmark.benchmarkItem method to call the default (generated) TABLENAME.insert procedure.

For example, you might add the following to the ddl.sql file:

    CREATE TABLE my_table (
      id BIGINT NOT NULL,
      val BIGINT
    );
    PARTITION TABLE my_table ON COLUMN id;
    CREATE INDEX my_table_idx1 ON my_table (id);

Then in the client/src/Benchmark.java file, modify the benchmarkItem() method like this:

    public void benchmarkItem() throws Exception {

        // To make an asynchronous procedure call, you need a callback object
        // BenchmarkCallback is a generic callback that keeps track of the transaction results
        // for any given procedure name, which should match the procedure called below.
        ProcedureCallback callback = new BenchmarkCallback("MY_TABLE.insert");

        // generate some random parameter values
        int id = rand.nextInt(1000);
        int val = rand.nextInt(1000000);

        // call the procedure asynchronously, passing in the callback and the procedure name,
        // followed by the input parameters
        client.callProcedure(callback,
                             "MY_TABLE.insert",
                             id,
                             val
                             );
    }


If you want to modify or copy the example java stored procedure to make your own, you will need to compile it into a plain jar file. A script is provided to do this:

    cd procedures
    ./compile_procs.sh

This will generate a new procedures.jar file in the procedures subfolder. This is loaded by the ddl.sql script. If you have made changes and wish to update the procedure code running in the database, you can reload the procedures using the same command:

    sqlcmd
    1> load classes procedures/procedures.jar;

If you modify or add objects to the ddl.sql, keep in mind you would need to drop all of the existing objects first before re-running the script, or you can shutdown and repeate the steps in Getting Started above.

When you run the un_client.sh script, it will first re-compile Benchmark.java and all of the other Java code in the client/src folder and its subdirectories.
