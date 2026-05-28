# Client2 — Developer Guide

For the use-case overview, see [README.md](README.md).

This guide covers building and running the client2 example.

Running with Docker
---------------------------
Run `SimpleSyncExample` against a Dockerized VoltDB:

```bash
mvn -pl client2 -Pbenchmark -DskipTests verify
```

Local VoltDB
---------------------------
Make sure `bin/` inside the VoltDB kit is on your `PATH`. Then from the `client2` directory:

    voltdb init
    voltdb start

Wait for "Server completed initialization." In another shell, run one of:

    ./run.sh SimpleSyncExample
    ./run.sh SimpleAsyncExample
    ./run.sh AsyncFlowControl
