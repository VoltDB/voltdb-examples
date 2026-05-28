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

import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

/**
 * DDoS Detection VoltDB client application.
 * Demonstrates real-time DDoS detection using partitioned stored procedures
 * and TIME_WINDOW materialized views.
 */
public class DdosDetectionApp {

    private final Client2 client;

    public DdosDetectionApp(Client2 client) {
        this.client = client;
    }

    // ========================================
    // Core: Multi-step atomic DDoS detection
    // ========================================

    /**
     * Process an incoming web request for DDoS detection.
     * Returns a VoltTable with columns: BLOCKED (tinyint), REASON (string), RULE_NAME (string).
     */
    public VoltTable processRequest(String sourceIp, long requestId, String domain, long requestTimeMs)
            throws Exception {
        return client.callProcedureAsync("ProcessRequest", sourceIp, requestId, domain, requestTimeMs)
            .thenApply(response -> checkResponse("ProcessRequest", response).getResults()[0])
            .get();
    }

    // ========================================
    // Single-partition reads
    // ========================================

    public VoltTable getBlockedIps(String sourceIp) throws Exception {
        return client.callProcedureAsync("GetBlockedIps", sourceIp)
            .thenApply(response -> checkResponse("GetBlockedIps", response).getResults()[0])
            .get();
    }

    /**
     * Returns two tables: [0] = REQUESTS, [1] = BLOCKED_IPS for the given IP.
     */
    public VoltTable[] getRequestsByIp(String sourceIp) throws Exception {
        return client.callProcedureAsync("GetRequestsByIp", sourceIp)
            .thenApply(response -> checkResponse("GetRequestsByIp", response).getResults())
            .get();
    }

    // ========================================
    // Multi-partition operations
    // ========================================

    public void upsertDomain(String domain, String owner, int maxRequestsPerIp) throws Exception {
        client.callProcedureAsync("UpsertDomain", domain, owner, maxRequestsPerIp)
            .thenApply(response -> checkResponse("UpsertDomain", response))
            .get();
    }

    public VoltTable searchBlockedIps(String ruleName) throws Exception {
        return client.callProcedureAsync("SearchBlockedIps", ruleName)
            .thenApply(response -> checkResponse("SearchBlockedIps", response).getResults()[0])
            .get();
    }

    // ========================================
    // Cleanup
    // ========================================

    public void deleteAllData() throws Exception {
        client.callProcedureAsync("@AdHoc", "DELETE FROM REQUESTS;")
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM BLOCKED_IPS;"))
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM DOMAINS;"))
            .get();
        System.out.println("All data deleted.");
    }

    // ========================================
    // Internal
    // ========================================

    private static ClientResponse checkResponse(String procName, ClientResponse response) {
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException(procName + " failed: " + response.getStatusString());
        }
        return response;
    }

    // ========================================
    // Utility
    // ========================================

    public static void printTable(String label, VoltTable table) {
        System.out.println("\n--- " + label + " ---");
        System.out.println(table.toFormattedString());
    }

    // ========================================
    // Main entry point
    // ========================================

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21211;

        Client2Config config = new Client2Config();
        Client2 client = ClientFactory.createClient(config);
        client.connectSync(host, port);
        System.out.println("Connected to VoltDB at " + host + ":" + port);

        try {
            new VoltDBSetup(client).initSchemaIfNeeded();

            DdosDetectionApp app = new DdosDetectionApp(client);
            CsvDataLoader loader = new CsvDataLoader();

            app.deleteAllData();

            // Load domain configuration (sets per-domain thresholds)
            loader.loadDomainsData(app, "data/domains.csv");

            // Process an incoming request through the multi-step DDoS detection procedure.
            // ProcessRequest atomically: checks if IP is blocked, reads domain config,
            // inserts the request, evaluates rules via materialized views, and blocks if triggered.
            long now = System.currentTimeMillis();
            VoltTable result = app.processRequest("192.168.1.1", 1, "example.com", now);
            result.advanceRow();
            System.out.printf("ProcessRequest result: BLOCKED=%d, RULE=%s, REASON=%s%n",
                result.getLong("BLOCKED"),
                result.getString("RULE_NAME"),
                result.getString("REASON"));

            // Query request history and block status for an IP (co-located single-partition access)
            VoltTable[] ipData = app.getRequestsByIp("192.168.1.1");
            printTable("Requests", ipData[0]);
            printTable("Blocked IPs", ipData[1]);

            // Multi-partition search across all blocked IPs by rule name
            printTable("Blocked by RULE1_PER_DOMAIN", app.searchBlockedIps("RULE1_PER_DOMAIN"));

            app.deleteAllData();

        } finally {
            client.close();
        }
    }
}
