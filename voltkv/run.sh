#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

# leader host for startup purposes only
# (once running, all nodes are the same -- no leaders)
STARTUPLEADERHOST="localhost"

# list of cluster nodes separated by commas in host:[port] format
SERVERS="localhost"

# remove binaries, logs, runtime artifacts, etc... but keep the client jar
clean() {
    rm -rf voltdbroot log *.log target
}

# remove everything from "clean" as well as the jarfile
cleanall() {
    clean
    rm -rf voltkv-client.jar
}

# compile the source code for the client into a jarfile using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl voltkv -DskipTests
    # copy jar to module root for compatibility with traditional run.sh usage
    cp target/voltkv-1.0-SNAPSHOT.jar voltkv-client.jar
}

# compile the client jarfile if it doesn't exist
jars_ifneeded() {
    if [ ! -e voltkv-client.jar ]; then
        jars
    fi
}

# Init to directory root
voltinit_ifneeded() {
    voltdb init --force
}

# run the database server locally
server() {
    voltinit_ifneeded
    voltdb start -H $STARTUPLEADERHOST
}

# load schema and procedures
init() {
    jars_ifneeded
    sqlcmd --servers=$SERVERS < ddl.sql
}

# run the client that drives the example
client() {
    async_benchmark
}

# Asynchronous benchmark sample
# Use this target for argument help
async_benchmark_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.AsyncBenchmark --help
}

# latencyreport: default is OFF
# ratelimit: must be a reasonable value if lantencyreport is ON
# Disable the comments (and add a preceding slash) to get latency report
async_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.AsyncBenchmark \
        --displayinterval=5 \
        --duration=120 \
        --servers=$SERVERS \
        --poolsize=100000 \
        --preload=true \
        --getputratio=0.90 \
        --keysize=32 \
        --minvaluesize=1024 \
        --maxvaluesize=1024 \
        --entropy=127 \
        --usecompression=false
#        --multisingleratio=0.0
#        --latencyreport=true \
#        --ratelimit=100000 \
}

# Multi-threaded synchronous benchmark sample
# Use this target for argument help
sync_benchmark_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.SyncBenchmark --help
}

sync_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.SyncBenchmark \
        --displayinterval=5 \
        --duration=120 \
        --servers=$SERVERS \
        --poolsize=100000 \
        --preload=true \
        --getputratio=0.90 \
        --keysize=32 \
        --minvaluesize=1024 \
        --maxvaluesize=1024 \
        --usecompression=false \
        --threads=40
#        --multisingleratio=0.0
}

# JDBC benchmark sample
# Use this target for argument help
jdbc_benchmark_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.JDBCBenchmark --help
}

jdbc_benchmark() {
    jars_ifneeded
    java $JAVA_OPTS -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.JDBCBenchmark \
        --displayinterval=5 \
        --duration=120 \
        --servers=$SERVERS \
        --poolsize=100000 \
        --preload=true \
        --getputratio=0.90 \
        --keysize=32 \
        --minvaluesize=1024 \
        --maxvaluesize=1024 \
        --usecompression=false \
        --threads=40
}

help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|async_benchmark|async_benchmark_help|...}"
    echo "       {...|sync_benchmark|sync_benchmark_help|jdbc_benchmark|jdbc_benchmark_help}"
}

# Run the targets passed on the command line
# If no first arg, run server

if [ $# -eq 0 ]; then
    echo "${0}: Performing server..."
    server
    exit
fi

for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
