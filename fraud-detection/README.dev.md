# Fraud Detection — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building, running, and customizing the fraud-detection example.

> **Note:** This example requires Apache Kafka to run. Docker deployment is not available for this app.

Building with Maven
---------------------------
The fraud-detection example can be built using Maven:

```bash
# From the voltdb-examples root directory
mvn clean package -pl fraud-detection -DskipTests

# Or from the fraud-detection directory
cd fraud-detection
../mvnw clean package -DskipTests
```

Quickstart (Requires Kafka)
---------------------------
This example application uses VoltDB, node.js, and Kafka to emulate a distributed environment. To run the example, be sure you have node.js and Kafka (version 8) installed and running. Node.js must be installed on the local machine while Kakfa can be running either locally or on a separate server. (If Kafka is running remotely, edit the deployment*.xml file to point to the server  and edit the run.sh file to modify the train() function.)

Once node.js and Kafka (kafka 8) are ready, you can start the example application. Make sure "bin" inside the VoltDB kit is in your PATH. Also create topics for card_swipes and train_activity in your kafka instance.
Then open a shell, go to the examples/fraud-detection directory, and execute the following commands to set up the database:

  `./run.sh server` (start the  VoltDB server)  
  `./run.sh init` (load the schema)

Then run the demo application:

  `./run.sh train` (simulate card swipes and train activity)  
  `./run.sh npminstall` (setup node plugins)  
  `./run.sh nodeserver` (start the Fraud Detection Dashboard)

If you're running the example on a VoltDB cluster, rather than your local desktop or laptop, run `./run.sh nodeserver` in a new shell on one of the machines in the cluster, then connect to your dashboard from your browser at [http://servername:3000](http://servername:3000).

You can stop the server, running client, or webserver at any time with `Ctrl-C` or `SIGINT`.  Of course VoltDB can also run in the background using the -B option, in which case you can stop it with the `voltadmin shutdown` command.

Note: When you `./run.sh train` the data events to kafka are removed by your kafka retention policy, running this over and over can use disk space and make your machine/VoltDB run slow.

Note: that the downloaded VoltDB kits include pre-compiled stored procedures and client code as jarfiles. To run the example from a source build, it may be necessary to compile the Java source code by typing "run.sh jars" before. Note that this step requires a full Java JDK.

run.sh reference
---------------------------
VoltDB examples come with a run.sh shell script that simplifies compiling and running the example client application and other parts of the examples.
- *run.sh* : start the server
- *run.sh server* : start the server
- *run.sh init* : compile stored procedures and load the schema, stored procedures and sample data.
- *run.sh train* : Run the simulator application which runs train and card swipe activities
- *run.sh jars* : compile all Java clients and stored procedures into two Java jarfiles
- *run.sh clean* : remove compilation and runtime artifacts, compiled jarfiles *and* sampledata
- *run.sh npminstall* : install the necessary npm packages
- *run.sh nodeserver* : run the node server over http on port 3000

If you change the client or procedure Java code, you must recompile the jars by deleting them in the shell or using `./run.sh jars`.
