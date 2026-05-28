# JSON application

This sample demonstrates how to use VoltDB JSON functions to implement a flexible schema-type of application.  The application demonstrated here is a single sign-on session tracking application.  Logins from multiple sites (URLs) are tracked in a user session table in VoltDB.  Each login has common fields such as username and global session id.  Further, each login has site-specific data stored in a varchar column as JSON data.

The main goal of the JSON application is to demonstrate flexible schema and JSON support in VoltDB:

* This sample application uses the VoltDB synchronous API to load the database.
* The sample first creates 10 threads and loads up as many random logins as possible in 10 seconds.  The insertion rate (tx/second) and latency is calculated and displayed.
* Once the data is loaded, the sample application executes a series of AdHoc SQL queries, demonstrating various SQL queries on the JSON data.
