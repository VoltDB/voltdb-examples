# VoltDB Example App: MetroCard

> **Note:** `metrocard` is excluded from the default multi-module Maven build — its functionality is covered by [`fraud-detection`](../fraud-detection/README.md). You can still build and run it directly.

Use Case
--------
This application performs high velocity transaction processing for metro cards.  These transactions include:

- Card generation (during the initialization)
- Card Swipes (during the benchmark)

Optionally, the project can export data using the HTTP connector. There's a simple webserver included that acts as destination for the exported rows of data.
