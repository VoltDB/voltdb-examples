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
    rm -rf voltdbroot log *.log target web-node/fraud-detection-web/node_modules data/cards.csv
}

cleanall() {
    clean
    rm -rf frauddetection-procs.jar frauddetection-client.jar
}

webserver() {
    cd web; python -m SimpleHTTPServer $WEB_PORT
}

npminstall() {
    cd web-node/fraud-detection-web
    npm install
}

# Start the node server
nodeserver() {
    cd web-node/fraud-detection-web
    npm start
}

start_export_web() {
    cd exportWebServer; python exportServer.py
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl fraud-detection -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/fraud-detection-1.0-SNAPSHOT.jar frauddetection-procs.jar
    cp target/fraud-detection-1.0-SNAPSHOT.jar frauddetection-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e frauddetection-procs.jar ] || [ ! -e frauddetection-client.jar ]; then
        jars
    fi
}

# Init to directory root
init_ifneeded() {
    jars_ifneeded
    voltdb init -C deployment.xml --force
}

# run the database server locally
server() {
    init_ifneeded
    voltdb start -H $STARTUPLEADERHOST
}

# load schema and procedures
init() {
    jars_ifneeded
    sqlcmd --servers=$SERVERS < ddl.sql
    echo "----Loading Redline Stations----"
    csvloader --servers $SERVERS --port 21211 --file data/redline.csv --reportdir log fd_stations
    csvloader --servers $SERVERS --port 21211 --file data/trains.csv --reportdir log fd_trains
    rm -f data/cards.csv
    echo "----Loading Cards----"
    java $JAVA_OPTS -classpath frauddetection-client.jar:$APPCLASSPATH frauddetection.CardGenerator \
        --output=data/cards.csv
    csvloader --servers $SERVERS --port 21211 --file data/cards.csv --reportdir log fd_cards
    # Now enable the importers to start fetching the data.
    "voltadmin" update deployment-enable.xml
}

# run the client that drives the example with some editable options
train() {
    jars_ifneeded
    java $JAVA_OPTS -classpath frauddetection-client.jar:$APPCLASSPATH frauddetection.FraudSimulation \
        --broker=localhost:9092 --count=0
}

# generate metro cards
generate_cards() {
    jars_ifneeded
    java $JAVA_OPTS -classpath frauddetection-client.jar:$APPCLASSPATH frauddetection.CardGenerator \
        --output=data/cards.csv
}

help() {
    echo "Usage: ./run.sh {cleanall|clean|jars|server|init|train|generate_cards}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
