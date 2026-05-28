# VoltDB NBBO Example App

Use Case
--------
NBBO is the National Best Bid and Offer, defined as the lowest available ask price and highest available bid price across the participating markets for a given security.  Brokers should route trade orders to the market with the best price, and by law must guarantee customers the best available price.

This example app includes a VoltDB database schema that stores each market data tick and automatically inserts a new NBBO record whenever there is a change to the best available bid or ask price.  This can be used to serve the current NBBO or the history of NBBO changes on demand to consumers such as the dashboard or other applications.

The example includes a web dashboard that shows the real-time NBBO for a security and the latest available prices from each exchange.  It also includes a client benchmark application that generates synthetic market data ticks for all of the listed stocks from NYSE, AMEX, and NASDAQ.  The prices are simulated using a random walk algorithm that starts from the end of day closing price that is initially read from a CSV file.  It is not intended to be a realistic simulation of market data, but simply to generate simulated data for demonstration purposes.
