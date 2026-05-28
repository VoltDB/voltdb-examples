# Client2 programming example


Use Case
---------------------------
This example demonstrates programming with the `Client2` VoltDB API (async-first client with explicit flow control).

There are three example Java programs:

- `SimpleSyncExample` — synchronous calls via the Client2 API
- `SimpleAsyncExample` — async callbacks
- `AsyncFlowControl` — async with backpressure / flow control

Read the source under `src/` to see what each does.

The `simple` examples each issue a couple of procedure calls; `AsyncFlowControl` runs longer, sending thousands of requests.
