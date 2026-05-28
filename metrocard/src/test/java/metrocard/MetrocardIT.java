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

package metrocard;

import org.junit.Test;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdbtest.testcontainer.VoltDBCluster;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Integration tests for the Metrocard stored procedures.
 */
public class MetrocardIT {

    protected static final String DEFAULT_DOCKER_IMAGE = "voltdb/voltdb-enterprise:15.1.0";
    protected static final String DOCKER_IMAGE = System.getenv("VOLTDB_IMAGE") != null
        ? System.getenv("VOLTDB_IMAGE") : DEFAULT_DOCKER_IMAGE;

    protected String getLicensePath() {
        String license = System.getenv("VOLTDB_LICENSE");
        if (license != null && new File(license).exists()) {
            return license;
        }
        String home = System.getProperty("user.home");
        String defaultPath = home + "/license.xml";
        if (new File(defaultPath).exists()) {
            return defaultPath;
        }
        throw new RuntimeException("VoltDB license not found.");
    }

    protected VoltDBCluster createCluster() {
        return new VoltDBCluster(getLicensePath(), DOCKER_IMAGE, "target").withCommandLogEnabled(false);
    }

    protected void loadDDL(VoltDBCluster db, String ddlPath) throws Exception {
        StringBuilder ddl = new StringBuilder();
        boolean inBatch = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(ddlPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toUpperCase();
                if (trimmed.startsWith("LOAD CLASSES")) continue;
                // Skip DROP statements (fresh database does not need them)
                if (trimmed.startsWith("DROP ")) continue;
                if (trimmed.startsWith("FILE -INLINEBATCH")) { inBatch = true; continue; }
                // Handle both END_OF_BATCH and END_BATCH markers
                if (inBatch && (trimmed.startsWith("END_OF_BATCH") || trimmed.equals("END_BATCH"))) {
                    inBatch = false;
                    continue;
                }
                ddl.append(line).append("\n");
            }
        }
        db.runDDL(ddl.toString());
    }

    @Test
    public void testCardSwipe() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Insert a station
            client.callProcedure("@AdHoc",
                "INSERT INTO mc_stations (station_id, name, fare, weight) VALUES (1, 'Central', 250, 100)");

            // Insert a card with sufficient balance
            client.callProcedure("@AdHoc",
                "INSERT INTO mc_cards (card_id, enabled, card_type, balance, name, phone, email, notify) " +
                "VALUES (12345, 1, 0, 10000, 'Test User', '5551234567', 'test@test.com', 0)");

            // Perform a card swipe - McCardSwipe(cardId, stationId)
            ClientResponse resp = client.callProcedure("McCardSwipe", 12345, 1);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            // Verify activity was recorded
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM mc_activity WHERE card_id = 12345");
            assertEquals(1, queryResp.getResults()[0].asScalarLong());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testGetSwipesPerSecond() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Call procedure with seconds parameter - should work even with empty data
            ClientResponse resp = client.callProcedure("GetSwipesPerSecond", 60);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

        } finally {
            db.shutdown();
        }
    }
}
