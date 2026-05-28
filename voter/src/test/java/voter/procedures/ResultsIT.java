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

package voter.procedures;

import org.junit.Test;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdbtest.testcontainer.VoltDBCluster;
import voter.VoterTestBase;

import static org.junit.Assert.*;

/**
 * Integration tests for the Results stored procedure.
 */
public class ResultsIT extends VoterTestBase {

    @Override
    protected String getExtraLibDirectory() {
        return "target";
    }

    @Test
    public void testResultsOrdering() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize with 3 contestants
            client.callProcedure("Initialize", 3, "Alice,Bob,Charlie");

            // Cast votes: Alice=3, Bob=1, Charlie=2
            // Using different phone numbers for each vote
            for (int i = 0; i < 3; i++) {
                client.callProcedure("Vote", 2121000000L + i, 1, 10); // Alice
            }
            client.callProcedure("Vote", 2122000000L, 2, 10); // Bob
            for (int i = 0; i < 2; i++) {
                client.callProcedure("Vote", 2123000000L + i, 3, 10); // Charlie
            }

            // Get results - should be ordered by votes descending
            ClientResponse resp = client.callProcedure("Results");
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            VoltTable results = resp.getResults()[0];

            // First place: Alice with 3 votes
            assertTrue("Should have first row", results.advanceRow());
            assertEquals("Alice", results.getString(0));
            assertEquals(1, results.getLong(1)); // contestant_number
            assertEquals(3L, results.getLong(2)); // total_votes

            // Second place: Charlie with 2 votes
            assertTrue("Should have second row", results.advanceRow());
            assertEquals("Charlie", results.getString(0));
            assertEquals(3, results.getLong(1)); // contestant_number
            assertEquals(2L, results.getLong(2)); // total_votes

            // Third place: Bob with 1 vote
            assertTrue("Should have third row", results.advanceRow());
            assertEquals("Bob", results.getString(0));
            assertEquals(2, results.getLong(1)); // contestant_number
            assertEquals(1L, results.getLong(2)); // total_votes

            // No more rows
            assertFalse("Should have no more rows", results.advanceRow());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testEmptyResults() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize but don't cast any votes
            client.callProcedure("Initialize", 3, "Alice,Bob,Charlie");

            // Get results - should be empty (no votes cast)
            ClientResponse resp = client.callProcedure("Results");
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            VoltTable results = resp.getResults()[0];
            // No rows since no votes were cast
            assertFalse("Results should be empty when no votes cast", results.advanceRow());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testTiedResults() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize
            client.callProcedure("Initialize", 3, "Alice,Bob,Charlie");

            // Cast equal votes for all contestants (2 each)
            client.callProcedure("Vote", 2121000001L, 1, 10); // Alice
            client.callProcedure("Vote", 2121000002L, 1, 10); // Alice
            client.callProcedure("Vote", 2122000001L, 2, 10); // Bob
            client.callProcedure("Vote", 2122000002L, 2, 10); // Bob
            client.callProcedure("Vote", 2123000001L, 3, 10); // Charlie
            client.callProcedure("Vote", 2123000002L, 3, 10); // Charlie

            // Get results - all should have 2 votes, ordered by contestant_number ASC
            ClientResponse resp = client.callProcedure("Results");
            VoltTable results = resp.getResults()[0];

            // With ties, secondary ordering is by contestant_number ASC
            assertTrue(results.advanceRow());
            assertEquals(2L, results.getLong(2)); // 2 votes
            assertEquals(1, results.getLong(1));  // contestant 1 (Alice)

            assertTrue(results.advanceRow());
            assertEquals(2L, results.getLong(2)); // 2 votes
            assertEquals(2, results.getLong(1));  // contestant 2 (Bob)

            assertTrue(results.advanceRow());
            assertEquals(2L, results.getLong(2)); // 2 votes
            assertEquals(3, results.getLong(1));  // contestant 3 (Charlie)

        } finally {
            db.shutdown();
        }
    }
}
