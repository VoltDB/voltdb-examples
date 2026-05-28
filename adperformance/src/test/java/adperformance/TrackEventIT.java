/* This file is part of VoltDB.
 * Copyright (C) 2008-2024 Volt Active Data Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package adperformance;

import org.junit.Test;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.types.TimestampType;
import org.voltdbtest.testcontainer.VoltDBCluster;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * Integration tests for the AdPerformance stored procedures.
 */
public class TrackEventIT extends AdPerformanceTestBase {

    @Test
    public void testInitializeCreatives() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Initialize creatives: starting id=0, advertiser=1, 2 campaigns, 3 creatives each
            ClientResponse resp = client.callProcedure("InitializeCreatives", 0L, 1, 2, 3);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            // Verify creatives were created - query the creatives table
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM creatives WHERE advertiser_id = 1");
            assertEquals(ClientResponse.SUCCESS, queryResp.getStatus());

            long count = queryResp.getResults()[0].asScalarLong();
            assertEquals(6, count); // 2 campaigns * 3 creatives = 6

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testTrackEvent() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Set up test data: create a creative and inventory record
            client.callProcedure("@AdHoc",
                "INSERT INTO creatives VALUES (1, 1, 1, 'http://example.com/ad.jpg', 'Test Ad', 'Test Description')");
            client.callProcedure("@AdHoc",
                "INSERT INTO inventory VALUES (1, 1, 1, '/sports', 'http://example.com/sports/page1')");

            // Track an impression event
            TimestampType now = new TimestampType();
            long ipAddress = 12345678L;
            long cookieUid = 9876543L;
            int creativeId = 1;
            int inventoryId = 1;
            int typeId = 0; // impression
            BigDecimal cost = new BigDecimal("0.01");

            ClientResponse resp = client.callProcedure("TrackEvent",
                now, ipAddress, cookieUid, creativeId, inventoryId, typeId, cost);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            // Verify event was recorded
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM event_data WHERE creative_id = 1");
            assertEquals(ClientResponse.SUCCESS, queryResp.getStatus());
            assertEquals(1, queryResp.getResults()[0].asScalarLong());

            // Verify the view was updated
            ClientResponse viewResp = client.callProcedure("@AdHoc",
                "SELECT impressions FROM campaign_rates WHERE campaign_id = 1");
            assertEquals(ClientResponse.SUCCESS, viewResp.getStatus());

            VoltTable results = viewResp.getResults()[0];
            assertTrue(results.advanceRow());
            assertEquals(1, results.getLong("impressions"));

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testMultipleEventTypes() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Set up test data
            client.callProcedure("@AdHoc",
                "INSERT INTO creatives VALUES (1, 1, 1, 'http://example.com/ad.jpg', 'Test Ad', 'Test Description')");
            client.callProcedure("@AdHoc",
                "INSERT INTO inventory VALUES (1, 1, 1, '/sports', 'http://example.com/sports/page1')");

            TimestampType now = new TimestampType();
            long ipAddress = 12345678L;
            int creativeId = 1;
            int inventoryId = 1;

            // Track impression (type 0)
            client.callProcedure("TrackEvent",
                now, ipAddress, 1001L, creativeId, inventoryId, 0, new BigDecimal("0.01"));

            // Track clickthrough (type 1)
            client.callProcedure("TrackEvent",
                now, ipAddress, 1002L, creativeId, inventoryId, 1, new BigDecimal("0.10"));

            // Track conversion (type 2)
            client.callProcedure("TrackEvent",
                now, ipAddress, 1003L, creativeId, inventoryId, 2, new BigDecimal("1.00"));

            // Verify campaign_rates view
            ClientResponse viewResp = client.callProcedure("@AdHoc",
                "SELECT impressions, clicks, conversions FROM campaign_rates WHERE campaign_id = 1");
            assertEquals(ClientResponse.SUCCESS, viewResp.getStatus());

            VoltTable results = viewResp.getResults()[0];
            assertTrue(results.advanceRow());
            assertEquals(1, results.getLong("impressions"));
            assertEquals(1, results.getLong("clicks"));
            assertEquals(1, results.getLong("conversions"));

        } finally {
            db.shutdown();
        }
    }
}
