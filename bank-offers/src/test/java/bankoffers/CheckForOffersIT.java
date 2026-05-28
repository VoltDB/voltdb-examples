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

package bankoffers;

import org.junit.Test;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.types.TimestampType;
import org.voltdbtest.testcontainer.VoltDBCluster;

import static org.junit.Assert.*;

/**
 * Integration tests for the CheckForOffers stored procedure.
 */
public class CheckForOffersIT extends BankOffersTestBase {

    @Test
    public void testCheckForOffersRecordsActivity() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Set up test data: customer and account
            client.callProcedure("@AdHoc",
                "INSERT INTO customer VALUES (1, 'John', 'Doe', 'New York', 'NY', '555-1234', NOW, 'M')");
            client.callProcedure("@AdHoc",
                "INSERT INTO account VALUES (1001, 1, 5000.00, 10000.00, NOW, 'Y')");

            // Call CheckForOffers procedure
            long txnId = 1L;
            long acctNo = 1001L;
            double txnAmt = 50.00;
            String txnState = "NY";
            String txnCity = "New York";
            TimestampType txnTimestamp = new TimestampType();
            int vendorId = 100;

            ClientResponse resp = client.callProcedure("CheckForOffers",
                txnId, acctNo, txnAmt, txnState, txnCity, txnTimestamp, vendorId);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            // Verify activity was recorded
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM bo_activity WHERE acc_no = 1001");
            assertEquals(ClientResponse.SUCCESS, queryResp.getStatus());
            assertEquals(1, queryResp.getResults()[0].asScalarLong());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testCheckForOffersWithQualifyingOffer() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Set up test data
            client.callProcedure("@AdHoc",
                "INSERT INTO customer VALUES (1, 'Jane', 'Smith', 'Boston', 'MA', '555-5678', NOW, 'F')");
            client.callProcedure("@AdHoc",
                "INSERT INTO account VALUES (2001, 1, 8000.00, 15000.00, NOW, 'Y')");

            // Create a vendor offer with low thresholds
            client.callProcedure("@AdHoc",
                "INSERT INTO vendor_offers VALUES (200, 1, 0, 0, 0, 0, '10% off your next purchase!')");

            // First transaction to create activity
            TimestampType now = new TimestampType();
            client.callProcedure("CheckForOffers",
                1L, 2001L, 100.00, "MA", "Boston", now, 200);

            // Verify the activity view was updated
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT total_visits, total_spend FROM acct_vendor_totals WHERE acc_no = 2001 AND vendor_id = 200");
            assertEquals(ClientResponse.SUCCESS, queryResp.getStatus());
            assertTrue(queryResp.getResults()[0].advanceRow());
            assertEquals(1, queryResp.getResults()[0].getLong("total_visits"));

        } finally {
            db.shutdown();
        }
    }
}
