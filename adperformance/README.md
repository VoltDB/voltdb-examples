# Ad Performance Application

Use Case
---------------------------
This application simulates a high velocity stream of events (impressions, clickthroughs, conversions) that are enriched and ingested.  These events are randomly generated in the client, but represent a stream of events that would be received from web traffic.

The "TrackEvent" stored procedure processes these events.  It looks up the corresponding advertiser and campaign based on the creative ID which represents which ad was shown.  It also retrieves the corresponding web site and page based on the inventory ID from the event.  The timestamp and event type fields are converted to aid in aggregation, and all of this data is then inserted into the impression_data table.

Several views maintain real-time aggregations on this table to provide a minutely summary for each advertiser, plus drill-down reports grouped by campaign and creative to show detail-level metrics, costs and rates with real-time accuracy.

Several SQL features in VoltDB are demonstrated in this application, including:
  - VIEW group by expressions
  - [TRUNCATE](http://voltdb.com/docs/UsingVoltDB/sqlfunctruncate.php) Timestamp function
  - [DECODE](http://voltdb.com/docs/UsingVoltDB/sqlfuncdecode.php) function
  - TTL processing for data expiration

Time to Live (TTL) helps the application operate on a constant stream of incoming events by setting an expiration timestamp to automatically delete old data. Call [@Statistics TABLE](https://docs.voltdb.com/v7docs/UsingVoltDB/sysprocstatistics.php#sysprocstattable) for aggregation statistics to watch the deletion of data on the "event_data" table beginning after the default TTL = 1 minute.
