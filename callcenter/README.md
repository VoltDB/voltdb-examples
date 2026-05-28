# Call Center Example Application

Use Case
---------------------------
Process begin and end call events from a call center. Pair/join events in VoltDB to create a definitive record of completed calls.

Use VoltDB's strong consistency and stored procedure logic to compute a running standard deviation on call length by agent. This is not a trivial thing to compute without strong consistency. The provided HTML dashboard shows a top-N list of agents by standard deviation. It can be found in the "web" folder.

Note that in order for the simulation to be interesting, this app uses unrealistic call data. The average call time is 5s by default to make the stats interesting in a two-minute example run.

This app doesn't really show off the extraordinary throughput of VoltDB, though it will get a lot faster if you set the average call time lower and/or the number of agents higher.


VoltDB Features and Patterns
---------------------------

- **Streaming Joins**: Joining begin and end events for the same call using a table to hold unpaired state. Note the example supports out-of-order pairing.
- **Idempotent Processing**: Some of the input messages are duplicated, simulating real-life *at-least-once* delivery guarantees. This app uses VoltDB strong consistency to ignore redundant processing.
- **Complex Calculations**: Computing standard deviation is reasonably complex math, and is enabled using strong consistency in VoltDB.
- **Procedure Class Hierarchies**: The begin and end procedures inherit from a common base class that computes standard deviation for completed calls.
