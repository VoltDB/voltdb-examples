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

package callcenter;

import org.junit.Test;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.types.TimestampType;
import org.voltdbtest.testcontainer.VoltDBCluster;

import static org.junit.Assert.*;

/**
 * Integration tests for the Call Center stored procedures.
 */
public class CallProceduresIT extends CallCenterTestBase {

    @Test
    public void testBeginCall() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "ddl.sql", "target/callcenter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Begin a call
            int agentId = 1;
            String phoneNo = "555-1234";
            long callId = 1001L;
            TimestampType startTs = new TimestampType();

            ClientResponse resp = client.callProcedure("BeginCall",
                agentId, phoneNo, callId, startTs);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());
            assertEquals(0L, resp.getResults()[0].asScalarLong());

            // Verify call is in opencalls table
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM opencalls WHERE call_id = 1001");
            assertEquals(1, queryResp.getResults()[0].asScalarLong());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testEndCall() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "ddl.sql", "target/callcenter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            int agentId = 2;
            String phoneNo = "555-5678";
            long callId = 2001L;

            // End a call (creates open call entry waiting for begin)
            TimestampType endTs = new TimestampType();
            ClientResponse resp = client.callProcedure("EndCall",
                agentId, phoneNo, callId, endTs);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            // Verify call is in opencalls table
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM opencalls WHERE call_id = 2001");
            assertEquals(1, queryResp.getResults()[0].asScalarLong());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testCompleteCall() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "ddl.sql", "target/callcenter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            int agentId = 3;
            String phoneNo = "555-9999";
            long callId = 3001L;

            // Begin call
            TimestampType startTs = new TimestampType();
            client.callProcedure("BeginCall", agentId, phoneNo, callId, startTs);

            // Wait a bit and end call
            Thread.sleep(100);
            TimestampType endTs = new TimestampType();
            client.callProcedure("EndCall", agentId, phoneNo, callId, endTs);

            // Verify call moved to completedcalls
            ClientResponse openResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM opencalls WHERE call_id = 3001");
            assertEquals(0, openResp.getResults()[0].asScalarLong());

            ClientResponse completedResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM completedcalls WHERE call_id = 3001");
            assertEquals(1, completedResp.getResults()[0].asScalarLong());

        } finally {
            db.shutdown();
        }
    }
}
