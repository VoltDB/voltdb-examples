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
    ../mvnw clean -f ../pom.xml -pl client2
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf example.jar client2.jar
}

# compile the source code using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl client2 -DskipTests
    # copy jar to module root for compatibility with traditional run.sh usage
    cp target/client2-1.0-SNAPSHOT.jar client2.jar
}

# compile the jarfile if it doesn't exist
jars_ifneeded() {
    if [ ! -e client2.jar ]; then
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

# run one of the Client2 examples
client() {
    if [ -z "$1" ]; then
        echo "Usage: ./run.sh client <NAME>"
        echo "  where NAME is one of:"
        echo "    SimpleSyncExample | SimpleAsyncExample | AsyncFlowControl"
        echo ""
        echo "  Defaulting to SimpleSyncExample"
        client_run SimpleSyncExample
    else
        client_run "$1"
    fi
}

client_run() {
    local NAME="$1"
    shift 2>/dev/null || true
    jars_ifneeded
    java $JAVA_OPTS -classpath client2.jar:$CLIENTCLASSPATH \
        org.voltdb.example.$NAME --servers=$SERVERS "$@"
}

# Convenience targets for each example
sync() {
    client_run SimpleSyncExample "$@"
}

async() {
    client_run SimpleAsyncExample "$@"
}

flow() {
    client_run AsyncFlowControl "$@"
}

help() {
    echo "
  Usage: ./run.sh [TARGET...]

  Targets:
     help | clean | cleanall | jars |
     server | client [NAME] |
     sync | async | flow

  The default target is 'server'.
  'client' runs SimpleSyncExample by default.
  'sync', 'async', 'flow' are shortcuts for the three examples.
"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi

arg="$1"
shift
case "$arg" in
    clean|cleanall|jars|server|help)
        "$arg"
        ;;
    client|sync|async|flow)
        "$arg" "$@"
        ;;
    *)
        echo "${0}: Performing $arg..."
        "$arg" "$@"
        ;;
esac
