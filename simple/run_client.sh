#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../voltsetup

SRC=`find client/src -name "*.java"`
if [ -n "$SRC" ]; then
    mkdir -p client/obj
    javac -classpath $CLIENTCLASSPATH -d client/obj $SRC
    # stop if compilation fails
    if [ $? != 0 ]; then exit; fi

    jar cf client/client.jar -C client/obj .
    rm -rf client/obj

    java $JAVA_OPTS -classpath "client/client.jar:$CLIENTCLASSPATH" org.voltdb.example.Benchmark $*
    rm client/client.jar
else
    echo "Could not find java sources under client/src directory"
fi
