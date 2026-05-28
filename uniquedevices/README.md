Unique Devices application
===========================
The Unique Devices application demonstrates real-time analytics on fast moving data.  It also can be considered a representative implementation of the speed layer in the Lambda Architecture.

This example application solves a specific problem. Assume you offer a service to mobile app developers. Every time someone starts a mobile app, a message is sent to your service containing the application identifier and a unique id representing the device. Your service gives developers a bounded estimate of how many unique devices have used their app on any given day.

This example app was developed in response to a presentation that a Twitter (formerly Crashlytics) engineer has given several times, including a [20 minute presentation](http://youtu.be/56wy_mGEnzQ) from October 2014 at the Boston Facebook @Scale Conference. The presentation describes their use of the [Lambda Architecture](http://en.wikipedia.org/wiki/Lambda_architecture) for their service. The engineer describes this problem and how they solved it at a scale of 800,000 messages per second.

A key point of this presentation and this sample app is that the volume of data is so large that counting unique devices is often impractical and a realistic approach is to use a cardinality estimation algorithm. In this case [HyperLogLog](http://en.wikipedia.org/wiki/HyperLogLog) is used. This example app shows that it's possible, and in fact, easy, to leverage a third party software library in VoltDB stored procedure code. In this example application, an open source HLL library was sourced [here](https://github.com/addthis/stream-lib) and modified slightly, then directly used in VoltDB to processes binary blobs and estimate cardinality at a high rate.

Alternate Versions
----------
The example can be run in three different modes by changing the name of the procedure called by the client to one of three provided choices:

* **CountDeviceEstimate** uses HyperLogLog to provide estimate values for the number of unique devices per app.
* **CountDeviceExact** uses traditional indexes to exactly count unique devices per app. It is slower and requires much more space when the number of unique devices is large.
* **CountDeviceHybrid** uses exact counting for values up to 1000 while providing HLL-based estimates for values larger than 1000.

Benefits of ACID Consistency
----------
In the default mode, where the app is using HyperLogLog to estimate counts, the system uses VoltDB's strong consistency to transactionally store the integer estimate value in the table, along with the blob representing the HLL data structure. This model of transactionally reading, processing and updating is something VoltDB excels at. Because the estimate value is always 100% current and easily accessible via SQL queries, using the data is easier, and the complexity of the HLL algorithm is limited to a single piece of stored logic. In fact, whether the processing is using HLL, exact counts or a hybrid mode can be abstracted away from any clients consuming the data.

ACID consistency is also key to the simplicity of the hybrid estimate code. Without a transactional handoff between the exact count and the estimated values, it's much harder to claim the exact values are actually exact under the conditions promised.

Finally, it is not a difficult exercise to add a history table to this example and keep daily history for each app in VoltDB. One would need to add some logic to the core processing to check for date rollover since the last call, then to store the current estimates in the history table, then reset the new day's data to zero. With ACID consistency, the code to do this is a handful of if-statements, a huge win over less consistent systems. This could replace the batch layer of a basic Lambda Architecture implementation.
