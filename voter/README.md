# Voter Application

This example application simulates a phone based election process. Voters (based on phone numbers generated randomly by the client application) are allowed a limited number of votes.

Many attributes of the application are customizable, including:

- The number of contestants (between 1 and 12)
- How many votes are allowed per telephone number
- How long the sample client runs
- The maximum number of transactions the client will attempt per second
- When to start recording performance statistics
- How frequently to report those statistics

Measuring Performance
---------------------
The main goal of the voter application is to demonstrate the performance possibilities of VoltDB while still maintaining fully ACID transactions:

- Stored procedures are invoked asynchronously.
- The client application supports rate control (the number of transactions per second to send to the servers).
- The client also records and reports thoughput and latency.

As delivered, the example runs both client and server on a single node (like the other examples). However, it can easily be reconfigured to run any combination of clients and servers.

- The servers support multiple clients at one time
- Single node or multi-node clusters are supported

Interpreting the Results
------------------------
The default client configuration will allow the system to automatically tune itself for optimal throughput, regardless of your underlying hardware and cluster deployment.

The latency numbers reported by the default benchmark settings may be higher than is typical, as the cluster is near peak load. Rate-limiting your clients to a number that is less than the maximum throughput for your hardware should show lower and/or more consistent latency numbers.

The "Voter" application is specifically designed for benchmarking to give you a good feel for the type of performance VoltDB is capable of on your hardware.

For more on benchmarking and tips on application tuning please see the VoltDB Performance Guide at https://docs.voltdb.com/PerfGuide/
