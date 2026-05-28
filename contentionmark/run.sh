#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

# leader host for startup purposes only
# (once running, all nodes are the same -- no leaders)
STARTUPLEADERHOST="localhost"

# list of cluster nodes separated by commas in host[:port] format
SERVERS="localhost"

# remove binaries, logs, runtime artifacts, etc... but keep the jars
function clean() {
    rm -rf voltdbroot log *.class
}

# remove everything from "clean" as well as the jarfiles
function cleanall() {
    clean
    rm -rf contentionmark-client.jar
}

# compile the source code for procedures and the client into jarfiles
function jars() {
    # compile java source
    javac -classpath $CLIENTCLASSPATH ContentionMark.java
    # build procedure and client jars
    jar cf contentionmark-client.jar *.class
    # remove compiled .class files
    rm -rf *.class
}

# compile the procedure and client jarfiles if they don't exist
function jars-ifneeded() {
    if [ ! -e contentionmark-client.jar ]; then
        jars;
    fi
}

# Init to directory root
function voltinit-ifneeded() {
    voltdb init --force
}

# run the database server locally
function server() {
    voltinit-ifneeded
    voltdb start -H $STARTUPLEADERHOST
}

# load schema and procedures
function init() {
    jars-ifneeded
    sqlcmd --servers=$SERVERS < ddl.sql
}

# Use this target for argument help
function client-help() {
    jars-ifneeded
    java $JAVA_OPTS -classpath contentionmark-client.jar:$CLIENTCLASSPATH ContentionMark --help
}

# run the client that drives the example with some editable options
function client() {
    jars-ifneeded
    java $JAVA_OPTS -classpath contentionmark-client.jar:$CLIENTCLASSPATH ContentionMark \
        --duration=60 \
        --tuples=1 \
        --servers=$SERVERS
}

function help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|client-help}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
