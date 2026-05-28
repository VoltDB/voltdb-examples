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
    rm -f web/http.log web/http.pid
    rm -rf db/obj db/$APPNAME.jar db/nohup.log
    rm -rf client/obj client/log
}

# remove everything from "clean" as well as the jarfiles
cleanall() {
    clean
    rm -rf adperformance-procs.jar adperformance-client.jar
}

webserver() {
    cd web; python -m SimpleHTTPServer $WEB_PORT
}

# compile the source code for procedures and the client into jarfiles using Maven
jars() {
    ../mvnw package -f ../pom.xml -pl adperformance -DskipTests
    # copy jars to module root for compatibility with traditional run.sh usage
    cp target/adperformance-1.0-SNAPSHOT.jar adperformance-procs.jar
    cp target/adperformance-1.0-SNAPSHOT.jar adperformance-client.jar
}

# compile the procedure and client jarfiles if they don't exist
jars_ifneeded() {
    if [ ! -e adperformance-procs.jar ] || [ ! -e adperformance-client.jar ]; then
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

client() {
    jars_ifneeded
    java $JAVA_OPTS -classpath adperformance-client.jar:$CLIENTCLASSPATH \
         adperformance.AdTrackingBenchmark \
         --displayinterval=5 \
         --warmup=5 \
         --duration=120 \
         --servers=$SERVERS \
         --ratelimit=20000 \
         --sites=200 \
         --pagespersite=20 \
         --advertisers=40 \
         --campaignsperadvertiser=10 \
         --creativespercampaign=10
}

help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|webserver}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
