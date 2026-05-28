# Fraud Detection Example Application

> **Note:** This example requires an external Apache Kafka cluster and is excluded from the default Maven build and Docker Compose test harness. If you want a pure VoltDB fraud-detection demo with no external dependencies, see [`fraud-tx-detection`](../fraud-tx-detection/README.md).

Use Case
--------
This application demonstrates how VoltDB can ingest a stream of data making real time decisions such as fraud detection simply by using the power of SQL.
This application performs ingestion of metro card swipes and train activity from 2 different Apache Kafka topics.
The ingestion from Kafka is tied to java stored procedures that detect anomalies and compute various VIEWS on data such as:

1. Busiest station
2. Average wait time for passengers per station
3. Acceptance rate (Fraud Prevention)

A simple javascript-driven dashboard then displays this data.
