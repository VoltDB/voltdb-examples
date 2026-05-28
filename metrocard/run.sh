#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

# leader host for startup purposes only
# (once running, all nodes are the same -- no leaders)
STARTUPLEADERHOST="localhost"

# list of cluster nodes separated by commas in host:[port] format
SERVERS="localhost"

# WEB SERVER variables
WEB_PORT=8081

# remove binaries, logs, runtime artifacts, etc... but keep the jars
clean() {
    rm -rf voltdbroot log *.log target
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf metrocard-procs.jar metrocard-client.jar
}

webserver() {
    cd web; python -m SimpleHTTPServer $WEB_PORT
}

start_export_web() {
    cd exportWebServer; python exportServer.py
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl metrocard -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/metrocard-1.0-SNAPSHOT.jar metrocard-procs.jar
    cp target/metrocard-1.0-SNAPSHOT.jar metrocard-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e metrocard-procs.jar ] || [ ! -e metrocard-client.jar ]; then
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
    echo "----Loading Stations----"
    csvloader --servers $SERVERS --file data/stations.csv --reportdir log mc_stations
}

# run this target to see what command line options the client offers
client_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath metrocard-client.jar:$CLIENTCLASSPATH metrocard.MetroBenchmark --help
}

# run the client that drives the example with some editable options
client() {
    jars_ifneeded
    java $JAVA_OPTS -classpath metrocard-client.jar:$CLIENTCLASSPATH metrocard.MetroBenchmark \
        --displayinterval=5 \
        --warmup=5 \
        --duration=120 \
        --servers=$SERVERS \
        --ratelimit=250000 \
        --cardcount=50000
}

help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|client_help|webserver}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
