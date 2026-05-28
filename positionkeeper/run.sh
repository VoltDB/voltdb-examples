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
    rm -rf positionkeeper-procs.jar positionkeeper-client.jar
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl positionkeeper -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/positionkeeper-1.0-SNAPSHOT.jar positionkeeper-procs.jar
    cp target/positionkeeper-1.0-SNAPSHOT.jar positionkeeper-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e positionkeeper-procs.jar ] || [ ! -e positionkeeper-client.jar ]; then
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
    java $JAVA_OPTS -classpath positionkeeper-client.jar:$CLIENTCLASSPATH positionkeeper.PositionsBenchmark --help
}

# run the client that drives the example with some editable options
client() {
    jars_ifneeded
    java $JAVA_OPTS -classpath positionkeeper-client.jar:$CLIENTCLASSPATH positionkeeper.PositionsBenchmark \
        --displayinterval=5 \
        --warmup=5 \
        --duration=120 \
        --servers=$SERVERS \
        --ratelimit=30000 \
        --traders=2000 \
        --secpercnt=5
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
