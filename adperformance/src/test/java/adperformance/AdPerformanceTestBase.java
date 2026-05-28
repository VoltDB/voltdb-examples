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

import org.voltdbtest.testcontainer.VoltDBCluster;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Base class for AdPerformance integration tests using VoltDB TestContainers.
 */
public abstract class AdPerformanceTestBase {

    protected static final String DEFAULT_DOCKER_IMAGE = "voltdb/voltdb-enterprise:15.1.0";
    protected static final String DOCKER_IMAGE = System.getenv("VOLTDB_IMAGE") != null
        ? System.getenv("VOLTDB_IMAGE") : DEFAULT_DOCKER_IMAGE;

    /**
     * Get the path to the VoltDB license file.
     */
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
        String tmpPath = "/tmp/license.xml";
        if (new File(tmpPath).exists()) {
            return tmpPath;
        }
        throw new RuntimeException(
            "VoltDB license not found. Set VOLTDB_LICENSE env var or place license.xml in home directory."
        );
    }

    /**
     * Create a new VoltDB cluster for testing.
     */
    protected VoltDBCluster createCluster() {
        return new VoltDBCluster(getLicensePath(), DOCKER_IMAGE, getExtraLibDirectory()).withCommandLogEnabled(false);
    }

    /**
     * Get the directory containing extra libraries (procedures JAR).
     */
    protected String getExtraLibDirectory() {
        return "target";
    }

    /**
     * Load DDL from file, filtering out LOAD CLASSES and file commands
     * that are not supported by the testcontainer runDDL method.
     */
    protected void loadDDL(VoltDBCluster db, String ddlPath) throws Exception {
        StringBuilder ddl = new StringBuilder();
        boolean inBatch = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(ddlPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toUpperCase();

                // Skip LOAD CLASSES statements
                if (trimmed.startsWith("LOAD CLASSES")) {
                    continue;
                }

                // Handle file -inlinebatch blocks
                if (trimmed.startsWith("FILE -INLINEBATCH")) {
                    inBatch = true;
                    continue;
                }
                if (inBatch && trimmed.startsWith("END_OF_BATCH")) {
                    inBatch = false;
                    continue;
                }

                ddl.append(line).append("\n");
            }
        }

        db.runDDL(ddl.toString());
    }
}
