#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

# leader host for startup purposes only
STARTUPLEADERHOST="localhost"

# list of cluster nodes separated by commas in host[:port] format
SERVERS="localhost"

# remove binaries, logs, runtime artifacts, etc... but keep the jars
clean() {
    rm -rf voltdbroot log *.log
    ../mvnw clean -f ../pom.xml -pl ddos-detection
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf ddos-detection.jar
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl ddos-detection -DskipTests
    # copy jar to module root for compatibility with traditional run.sh usage
    cp target/ddos-detection-1.0-SNAPSHOT.jar ddos-detection.jar
}

# compile the jarfile if it doesn't exist
jars_ifneeded() {
    if [ ! -e ddos-detection.jar ]; then
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
    jars_ifneeded
    java $JAVA_OPTS -classpath ddos-detection.jar:target/lib/*:$CLIENTCLASSPATH \
        com.example.voltdb.DdosDetectionApp $SERVERS
}

help() {
    echo "
  Usage: ./run.sh [TARGET...]

  Targets:
     help | clean | cleanall | jars | init |
     server | client

  The default target is 'server'.
"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
