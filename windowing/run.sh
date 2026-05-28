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
    rm -rf voltdbroot log *.log target
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf windowing-procs.jar windowing-client.jar
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl windowing -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/windowing-1.0-SNAPSHOT.jar windowing-procs.jar
    cp target/windowing-1.0-SNAPSHOT.jar windowing-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e windowing-procs.jar ] || [ ! -e windowing-client.jar ]; then
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

# run this target to see what command line options the client offers
client_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath windowing-client.jar:$CLIENTCLASSPATH windowing.WindowingApp --help
}

# run the client that drives the example with some editable options
client() {
    jars_ifneeded
    # Note that in the command below, maxrows and historyseconds can't both be non-zero.
    java $JAVA_OPTS -classpath windowing-client.jar:$CLIENTCLASSPATH windowing.WindowingApp \
        --displayinterval=5 \
        --warmup=5 \
        --duration=120 \
        --servers=$SERVERS \
        --maxrows=0 \
        --historyseconds=30 \
        --inline=false \
        --deletechunksize=100 \
        --deleteyieldtime=100 \
        --ratelimit=15000
}

help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|client_help}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
