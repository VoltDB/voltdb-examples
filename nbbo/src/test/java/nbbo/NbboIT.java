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

package nbbo;

import org.junit.Test;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdbtest.testcontainer.VoltDBCluster;

import org.voltdb.types.TimestampType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import static org.junit.Assert.*;

/**
 * Integration tests for the NBBO stored procedures.
 */
public class NbboIT {

    protected static final String DEFAULT_DOCKER_IMAGE = "voltdb/voltdb-enterprise:14.3.3";

    protected String getDockerImage() {
        String image = System.getenv("VOLTDB_IMAGE");
        if (image != null && !image.isEmpty()) {
            return image;
        }
        return DEFAULT_DOCKER_IMAGE;
    }

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
        return new VoltDBCluster(getLicensePath(), getDockerImage(), "target").withCommandLogEnabled(false);
    }

    protected void loadDDL(VoltDBCluster db, String ddlPath) throws Exception {
        StringBuilder ddl = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(ddlPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toUpperCase();
                // Skip sqlcmd-specific directives
                if (trimmed.startsWith("LOAD CLASSES")) continue;
                if (trimmed.startsWith("FILE ")) continue;
                if (trimmed.startsWith("END_OF_")) continue;
                if (trimmed.equals("END_BATCH")) continue;
                ddl.append(line).append("\n");
            }
        }
        db.runDDL(ddl.toString());
    }

    @Test
    public void testProcessTick() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            // Load procedure classes first, then DDL
            db.loadClasses("target/nbbo-1.0-SNAPSHOT.jar");
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Process a tick
            // ProcessTick(symbol, time, seq_number, exchange, bidPrice, bidSize, askPrice, askSize)
            String symbol = "AAPL";
            TimestampType time = new TimestampType();
            long seqNumber = 1L;
            String exchange = "NY";  // max 2 chars per DDL
            int bidPrice = 15000;    // prices in cents
            int bidSize = 100;
            int askPrice = 15010;
            int askSize = 200;

            ClientResponse resp = client.callProcedure("ProcessTick",
                symbol, time, seqNumber, exchange, bidPrice, bidSize, askPrice, askSize);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

            // Verify tick was recorded in last_ticks (ticks is a STREAM)
            ClientResponse queryResp = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM last_ticks WHERE symbol = 'AAPL'");
            assertEquals(1, queryResp.getResults()[0].asScalarLong());

        } finally {
            db.shutdown();
        }
    }
}
