#!/usr/bin/env bash

mydir=$(dirname $0)
source $mydir/../../voltsetup

SCRIPTDIR=$( cd "$(dirname "$0")" ; pwd -P )
cd $SCRIPTDIR

SRC=`find src -name "*.java"`

if [ ! -z "$SRC" ]; then
    mkdir -p obj
    javac -classpath $APPCLASSPATH -d obj $SRC
    # stop if compilation fails
    if [ $? != 0 ]; then exit; fi

    jar cf procedures.jar -C obj .
    rm -rf obj
fi
