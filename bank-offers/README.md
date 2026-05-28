# VoltDB Bank Offers Example App

Use Case
--------
Ingest generated consumer purchase transaction data and find the best matching offer to present to the consumer at the point of sale.

Matching offers are found using a query that joins a summary view of the account activity with this vendor (merchant) against the available offers from that vendor.  The best match is determined by the priority for the offers that was set by the vendor.

The result is inserted into a persistent table, where it could drive a live web interaction, such as serving an online ad that describes the offer, retrieved from the inputs of the account and vendor_id.  It is also shown in the web dashboard as recent examples of offers.  This result is also inserted into a stream table so that it can be pushed to Hadoop using the HTTP export connector.

The web dashboard shows recent offers as well as a moving chart of offers/sec and overall transactions/sec.
