#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

# leader host for startup purposes only
# (once running, all nodes are the same -- no leaders)
STARTUPLEADERHOST="localhost"

# list of cluster nodes separated by commas in host:[port] format
SERVERS="localhost"

# remove binaries, logs, runtime artifacts, etc... but keep the jars
clean() {
    rm -rf voltdbroot log *.log
    ../mvnw clean -f ../pom.xml -pl voter
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf voter-procs.jar voter-client.jar
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl voter -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/voter-1.0-SNAPSHOT.jar voter-procs.jar
    cp target/voter-1.0-SNAPSHOT.jar voter-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e voter-procs.jar ] || [ ! -e voter-client.jar ]; then
        jars
    fi
}

# Init to directory root
voltinit_ifneeded() {
    voltdb init --force
}

# run the database server locally
server() {
    jars_ifneeded
    voltinit_ifneeded
    voltdb start -H $STARTUPLEADERHOST
}

# load schema and procedures
init() {
    jars_ifneeded
    sqlcmd --servers=$SERVERS < src/main/resources/ddl.sql
}

# run the client that drives the example
client() {
    async_benchmark
}

# trivial client code for illustration purposes
simple_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voter-client.jar:$CLIENTCLASSPATH -Dlog4j.configuration=file://$LOG4J \
        voter.client.SimpleBenchmark $SERVERS
}

# Asynchronous benchmark sample
# Use this target for argument help
async_benchmark_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voter-client.jar:$CLIENTCLASSPATH voter.client.AsyncBenchmark --help
}

# latencyreport: default is OFF
# ratelimit: must be a reasonable value if lantencyreport is ON
async_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS  \
	-classpath voter-client.jar:$CLIENTCLASSPATH voter.client.AsyncBenchmark \
        --displayinterval=5 \
        --warmup=5 \
        --duration=120 \
        --servers=$SERVERS \
        --contestants=6 \
        --maxvotes=2
}

# Multi-threaded synchronous benchmark sample
# Use this target for argument help
sync_benchmark_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voter-client.jar:$CLIENTCLASSPATH voter.client.SyncBenchmark --help
}

sync_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS  \
	-classpath voter-client.jar:$CLIENTCLASSPATH -Dlog4j.configuration=file://$LOG4J \
        voter.client.SyncBenchmark \
        --displayinterval=5 \
        --warmup=5 \
        --duration=120 \
        --servers=$SERVERS \
        --contestants=6 \
        --maxvotes=2 \
        --threads=40
}

# JDBC benchmark sample
# Use this target for argument help
jdbc_benchmark_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voter-client.jar:$CLIENTCLASSPATH voter.client.JDBCBenchmark --help
}

jdbc_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS  \
	-classpath voter-client.jar:$CLIENTCLASSPATH -Dlog4j.configuration=file://$LOG4J \
        voter.client.JDBCBenchmark \
        --displayinterval=5 \
        --duration=120 \
        --servers=$SERVERS \
        --maxvotes=2 \
        --contestants=6 \
        --threads=40
}

help() {
    echo "
  Usage: ./run.sh [TARGET...]

  Targets:
     help | clean | cleanall | jars | init |
     server | client |
     async_benchmark | async_benchmark_help |
     sync_benchmark | sync_benchmark_help |
     jdbc_benchmark | jdbc_benchmark_help |
     simple_benchmark

  The default target is 'server'.
  The 'client' target is the same as 'async_benchmark'.

"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ];
then
    server
    exit
fi

for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
