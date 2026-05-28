/* This file is part of VoltDB.
 * Copyright (C) 2026 Volt Active Data Inc.
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

package com.example.voltdb;

import org.voltdb.client.Client2;
import org.voltdb.client.ClientResponse;
import org.voltdb.VoltTable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * One-time schema deployment utility.
 * Checks whether schema is already deployed via @SystemCatalog.
 * If not, loads procedure classes and executes DDL.
 */
public class VoltDBSetup {

    private static final String JAR_PATH = "target/ddos-detection-1.0.jar";
    private static final String DDL_RESOURCE = "ddl.sql";

    private final Client2 client;

    public VoltDBSetup(Client2 client) {
        this.client = client;
    }

    /**
     * Deploy stored procedure classes and DDL schema to VoltDB,
     * but only if the schema has not already been deployed.
     */
    public void initSchemaIfNeeded() throws Exception {
        if (isSchemaDeployed()) {
            System.out.println("Schema already deployed — skipping.");
            return;
        }

        File jarFile = new File(JAR_PATH);
        if (!jarFile.exists()) {
            throw new RuntimeException(
                "Jar not found: " + JAR_PATH + ". Run 'mvn package -DskipTests' first.");
        }

        System.out.println("Loading classes from: " + jarFile);
        byte[] jarBytes = Files.readAllBytes(jarFile.toPath());
        ClientResponse response = client.callProcedureSync("@UpdateClasses", jarBytes, null);
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException("Failed to load classes: " + response.getStatusString());
        }
        System.out.println("Classes loaded successfully.");

        String ddl = loadResourceAsString(DDL_RESOURCE);
        if (ddl == null) {
            throw new RuntimeException("DDL resource not found: " + DDL_RESOURCE);
        }
        System.out.println("Loading schema from classpath: " + DDL_RESOURCE);
        response = client.callProcedureSync("@AdHoc", ddl);
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException("DDL failed: " + response.getStatusString());
        }
        System.out.println("Schema deployment complete.");
    }

    private boolean isSchemaDeployed() throws Exception {
        ClientResponse response = client.callProcedureSync("@SystemCatalog", "TABLES");
        VoltTable tables = response.getResults()[0];
        while (tables.advanceRow()) {
            String tableName = tables.getString("TABLE_NAME");
            if ("REQUESTS".equalsIgnoreCase(tableName)) {
                return true;
            }
        }
        return false;
    }

    private String loadResourceAsString(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.err.println("Resource not found: " + resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + resourcePath, e);
        }
    }
}
