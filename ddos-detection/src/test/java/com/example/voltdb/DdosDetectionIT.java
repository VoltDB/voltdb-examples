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

import org.junit.jupiter.api.Test;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdbtest.testcontainer.VoltDBCluster;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DdosDetectionIT extends IntegrationTestBase {

    @Test
    public void testDdosDetection() {
        VoltDBCluster db = null;
        try {
            // ============================================
            // Setup: start VoltDB and deploy schema
            // ============================================
            Client2 client;
            if (isTestContainerMode()) {
                db = createTestContainer();
                startAndConfigureTestContainer(db);
                client = db.getClient2();
            } else {
                client = createExternalClient();
                configureExternalInstance(client);
            }

            DdosDetectionApp app = new DdosDetectionApp(client);
            CsvDataLoader loader = new CsvDataLoader();

            // ============================================
            // Load domain configuration
            // ============================================
            List<String> domains = loader.loadDomainsData(app, "data/domains.csv");
            assertEquals(5, domains.size(), "All domains should be loaded");

            // ============================================
            // Test 1: Normal request — should be ALLOWED
            // ============================================
            long now = System.currentTimeMillis();
            VoltTable result = app.processRequest("192.168.1.1", 1001, "example.com", now);
            assertTrue(result.advanceRow(), "Should return a result row");
            assertEquals(0L, result.getLong("BLOCKED"),
                "First request should be ALLOWED (BLOCKED=0)");
            assertEquals("NONE", result.getString("RULE_NAME"));

            // ============================================
            // Test 2: Rule 1 — per-IP per-domain threshold
            // Domain example.com has max 100 requests per IP.
            // Send 101 requests from same IP to same domain within the same second.
            // ============================================
            String attackerIp = "10.99.99.1";
            for (int i = 0; i < 100; i++) {
                app.processRequest(attackerIp, 2000 + i, "example.com", now);
            }
            // The 101st request should trigger Rule 1 (count > 100)
            result = app.processRequest(attackerIp, 2100, "example.com", now);
            assertTrue(result.advanceRow(), "Should return a result row");
            assertEquals(1L, result.getLong("BLOCKED"),
                "Request 101 should be BLOCKED");
            assertEquals("RULE1_PER_DOMAIN", result.getString("RULE_NAME"));

            // Verify IP appears in BLOCKED_IPS
            VoltTable blocked = app.getBlockedIps(attackerIp);
            assertTrue(blocked.advanceRow(), "Attacker IP should be in BLOCKED_IPS");
            assertEquals("RULE1_PER_DOMAIN", blocked.getString("RULE_NAME"));

            // ============================================
            // Test 3: Already-blocked IP — early exit
            // ============================================
            result = app.processRequest(attackerIp, 2200, "example.com", now);
            assertTrue(result.advanceRow(), "Should return a result row");
            assertEquals(1L, result.getLong("BLOCKED"),
                "Already-blocked IP should still be BLOCKED");
            String reason = result.getString("REASON");
            assertTrue(reason.contains("already blocked"),
                "Reason should mention already blocked: " + reason);

            // ============================================
            // Test 4: GetRequestsByIp — co-located access
            // ============================================
            VoltTable[] ipResults = app.getRequestsByIp(attackerIp);
            assertEquals(2, ipResults.length, "Should return 2 result tables");
            int requestCount = 0;
            while (ipResults[0].advanceRow()) {
                requestCount++;
            }
            assertTrue(requestCount > 100, "Should have >100 request records for attacker IP");
            assertTrue(ipResults[1].advanceRow(), "Should have blocked record");

            // ============================================
            // Test 5: SearchBlockedIps — multi-partition
            // ============================================
            VoltTable searchResults = app.searchBlockedIps("RULE1_PER_DOMAIN");
            int searchCount = 0;
            while (searchResults.advanceRow()) {
                searchCount++;
            }
            assertTrue(searchCount >= 1,
                "Should find at least 1 blocked IP by RULE1_PER_DOMAIN");

            // ============================================
            // Test 6: Rule 2 — per-IP total threshold (>1000 across all domains)
            // Use a high-threshold domain so Rule 1 doesn't trigger first.
            // ============================================
            String floodIp = "10.88.88.1";
            app.upsertDomain("highcap.example.com", "Test", 2000);
            long t = System.currentTimeMillis();
            long reqId = 50000;

            // Send 1000 requests to highcap.example.com within the same second
            // (well under its 2000 per-domain limit)
            for (int i = 0; i < 1000; i++) {
                app.processRequest(floodIp, reqId++, "highcap.example.com", t);
            }
            // The 1001st request should trigger Rule 2 (total > 1000)
            result = app.processRequest(floodIp, reqId++, "highcap.example.com", t);
            assertTrue(result.advanceRow(), "Should return a result row");
            assertEquals(1L, result.getLong("BLOCKED"),
                "Request >1000 total should be BLOCKED");
            assertEquals("RULE2_TOTAL", result.getString("RULE_NAME"));

            // ============================================
            // Test 7: Non-existent IP — empty results
            // ============================================
            VoltTable emptyResult = app.getBlockedIps("1.2.3.4");
            assertFalse(emptyResult.advanceRow(),
                "Should return empty result for non-existent IP");

            // ============================================
            // Cleanup
            // ============================================
            app.deleteAllData();

            VoltTable afterCleanup = app.getBlockedIps(attackerIp);
            assertFalse(afterCleanup.advanceRow(), "No data should remain after cleanup");

            System.out.println("\n*** ALL TESTS PASSED ***\n");

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            shutdownIfNeeded(db);
        }
    }
}
