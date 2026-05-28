#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

: ${SERVERS:=localhost}

# remove binaries, logs, runtime artifacts, etc...
function clean {
    rm -rf voltdbroot host_crash* log
    rm -f order*.jar src/*.class
}

# compile the source code for procedures and the client into jarfiles
function jars {
    # compile java source
    javac -classpath $APPCLASSPATH src/OrderProc.java
    javac -classpath $CLIENTCLASSPATH src/OrderClient.java
    # build procedure and client jars
    jar cf orderproc.jar -C src OrderProc.class
    jar cf orderclient.jar -C src OrderClient.class
    # remove compiled .class files
    rm -f src/*.class
}

# compile the procedure and client jarfiles if they don't exist
function jars-ifneeded {
    if [ ! -e orderproc.jar ] || [ ! -e orderclient.jar ]; then
        jars;
    fi
}

# run the database server locally
function server {
    jars-ifneeded
    voltdb init -f -j orderproc.jar -s ddl.sql
    voltdb start
}

# init the customer/parts tables
function init {
    sqlcmd --servers=$SERVERS < populate.sql
    echo " "
}

# run the client that drives the example
function client {
    jars-ifneeded
    java $JAVA_OPTS \
        -classpath orderclient.jar:$CLIENTCLASSPATH OrderClient
}

function help {
    echo "
Usage:  ./run.sh target...

Targets:
        help | jars | init | clean
        server | client

tl;dr:
        ./run.sh server         in one terminal
        ./run.sh init client    in another terminal
"
}

# Run the targets passed on the command line

if [ $# -eq 0 ];
then
    help
    exit 0
fi

for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done
