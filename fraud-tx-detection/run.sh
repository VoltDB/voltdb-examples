#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

# leader host for startup purposes only
STARTUPLEADERHOST="localhost"

# list of cluster nodes separated by commas in host[:port] format
SERVERS="localhost"

# remove binaries, logs, runtime artifacts, etc... but keep the jars
clean() {
    rm -rf voltdbroot log *.log target
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf fraud-tx-detection.jar
}

# compile the source code using Maven (standalone pom.xml)
jars() {
    mvn package -DskipTests
    # copy jar to module root for compatibility with traditional run.sh usage
    cp target/fraud-tx-detection-1.0.jar fraud-tx-detection.jar
}

# compile the jarfile if it doesn't exist
jars_ifneeded() {
    if [ ! -e fraud-tx-detection.jar ]; then
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
    sqlcmd --servers=$SERVERS < src/main/resources/ddl.sql
}

# run the client that drives the example
# Args: [host] [port] [targetTPS] [durationSeconds]
client() {
    jars_ifneeded
    java $JAVA_OPTS -classpath fraud-tx-detection.jar:target/lib/*:$CLIENTCLASSPATH \
        com.example.voltdb.FraudDetectionApp $SERVERS
}

help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|help}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
