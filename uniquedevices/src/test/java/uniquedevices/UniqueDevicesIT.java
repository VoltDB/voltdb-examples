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

package uniquedevices;

import org.junit.Test;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdbtest.testcontainer.VoltDBCluster;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Integration tests for the Unique Devices stored procedures.
 */
public class UniqueDevicesIT {

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
                // Skip DROP statements (fresh database doesn't need them)
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
    public void testCountDeviceEstimate() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Call CountDeviceEstimate(appId, hashedDeviceId)
            long appId = 1L;
            long hashedDeviceId = 123456789L;
            ClientResponse resp = client.callProcedure("CountDeviceEstimate", appId, hashedDeviceId);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

        } finally {
            db.shutdown();
        }
    }

    @Test
    public void testCountDeviceExact() throws Exception {
        VoltDBCluster db = createCluster();
        try {
            db.start();
            loadDDL(db, "ddl.sql");
            Client client = db.getClient();

            // Call CountDeviceExact(appId, hashedDeviceId)
            long appId = 1L;
            long hashedDeviceId = 987654321L;
            ClientResponse resp = client.callProcedure("CountDeviceExact", appId, hashedDeviceId);
            assertEquals(ClientResponse.SUCCESS, resp.getStatus());

        } finally {
            db.shutdown();
        }
    }
}
