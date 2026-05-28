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
    rm -rf uniquedevices-procs.jar uniquedevices-client.jar
}

webserver() {
    cd web; python -m SimpleHTTPServer $WEB_PORT
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl uniquedevices -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/uniquedevices-1.0-SNAPSHOT.jar uniquedevices-procs.jar
    cp target/uniquedevices-1.0-SNAPSHOT.jar uniquedevices-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e uniquedevices-procs.jar ] || [ ! -e uniquedevices-client.jar ]; then
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
    jars_ifneeded
    java $JAVA_OPTS -classpath uniquedevices-client.jar:$CLIENTCLASSPATH uniquedevices.UniqueDevicesClient \
        --displayinterval=5 \
        --duration=120 \
        --servers=localhost:21212 \
        --appcount=100
}

# Use this target for argument help
client_help() {
    jars_ifneeded
    java $JAVA_OPTS -classpath uniquedevices-client.jar:$CLIENTCLASSPATH uniquedevices.UniqueDevicesClient --help
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
