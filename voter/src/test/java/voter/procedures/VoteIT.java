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
 * Integration tests for the Vote stored procedure.
 */
public class VoteIT extends VoterTestBase {

    // Return codes from Vote procedure
    private static final long VOTE_SUCCESSFUL = 0;
    private static final long ERR_INVALID_CONTESTANT = 1;
    private static final long ERR_VOTER_OVER_VOTE_LIMIT = 2;

    private static final String CONTESTANT_NAMES =
            "Edwina Burnam,Tabatha Gehling,Kelly Clauss,Jessie Alloway," +
                    "Alana Bregman,Jessie Eichman";

    @Override
    protected String getExtraLibDirectory() {
        // Point to the compiled procedures JAR
        return "target";
    }

    @Test
    public void testVoteSuccessful() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize contestants
            ClientResponse initResp = client.callProcedure("Initialize", 6, CONTESTANT_NAMES);
            assertEquals(ClientResponse.SUCCESS, initResp.getStatus());

            // Cast a vote - use valid area code for NY (212)
            long phoneNumber = 2125551234L;
            int contestantNumber = 1;
            int maxVotes = 2;

            ClientResponse voteResp = client.callProcedure("Vote", phoneNumber, contestantNumber, maxVotes);
            assertEquals(ClientResponse.SUCCESS, voteResp.getStatus());

            // Check result code (0 = success)
            long resultCode = voteResp.getResults()[0].asScalarLong();
            assertEquals(VOTE_SUCCESSFUL, resultCode);

            // Verify vote was recorded via Results procedure
            ClientResponse resultsResp = client.callProcedure("Results");
            assertEquals(ClientResponse.SUCCESS, resultsResp.getStatus());

            VoltTable results = resultsResp.getResults()[0];
            assertTrue("Results should have at least one row", results.advanceRow());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testVoteOverLimit() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize
            client.callProcedure("Initialize", 6, CONTESTANT_NAMES);

            long phoneNumber = 2125559999L;
            int contestantNumber = 1;
            int maxVotes = 2;

            // Vote twice (should succeed)
            ClientResponse resp1 = client.callProcedure("Vote", phoneNumber, contestantNumber, maxVotes);
            assertEquals(VOTE_SUCCESSFUL, resp1.getResults()[0].asScalarLong());

            ClientResponse resp2 = client.callProcedure("Vote", phoneNumber, contestantNumber, maxVotes);
            assertEquals(VOTE_SUCCESSFUL, resp2.getResults()[0].asScalarLong());

            // Third vote should fail (over limit)
            ClientResponse resp3 = client.callProcedure("Vote", phoneNumber, contestantNumber, maxVotes);
            long resultCode = resp3.getResults()[0].asScalarLong();
            assertEquals(ERR_VOTER_OVER_VOTE_LIMIT, resultCode);

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testInvalidContestant() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize with 6 contestants
            client.callProcedure("Initialize", 6, CONTESTANT_NAMES);

            // Vote for invalid contestant (7 when only 6 exist)
            ClientResponse resp = client.callProcedure("Vote", 2125551111L, 7, 2);
            long resultCode = resp.getResults()[0].asScalarLong();
            assertEquals(ERR_INVALID_CONTESTANT, resultCode);

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testMultipleVoters() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadSchemaAndClasses(db, "src/main/resources/ddl.sql", "target/voter-1.0-SNAPSHOT.jar");
            Client client = db.getClient();

            // Initialize
            client.callProcedure("Initialize", 3, "Alice,Bob,Charlie");

            // Multiple voters voting for different contestants
            client.callProcedure("Vote", 2125550001L, 1, 10); // Voter 1 -> Alice
            client.callProcedure("Vote", 2125550002L, 1, 10); // Voter 2 -> Alice
            client.callProcedure("Vote", 2125550003L, 2, 10); // Voter 3 -> Bob
            client.callProcedure("Vote", 2125550004L, 3, 10); // Voter 4 -> Charlie
            client.callProcedure("Vote", 2125550005L, 1, 10); // Voter 5 -> Alice

            // Get results - Alice should be winning with 3 votes
            ClientResponse resultsResp = client.callProcedure("Results");
            VoltTable results = resultsResp.getResults()[0];

            assertTrue(results.advanceRow());
            assertEquals("Alice", results.getString(0));
            assertEquals(3L, results.getLong(2)); // 3 votes

        } finally {
            db.shutdown();
        }
    }
}
