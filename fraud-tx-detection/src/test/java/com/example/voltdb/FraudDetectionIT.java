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

public class FraudDetectionIT extends IntegrationTestBase {

    @Test
    public void testFraudDetection() {
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

            FraudDetectionApp app = new FraudDetectionApp(client);
            CsvDataLoader loader = new CsvDataLoader();

            // ============================================
            // Load test data from CSV files
            // ============================================
            List<Long> accountIds = loader.loadAccountData(app, "data/accounts.csv");
            List<Integer> merchantIds = loader.loadMerchantData(app, "data/merchants.csv");

            assertEquals(5, accountIds.size(), "All accounts should be loaded");
            assertEquals(5, merchantIds.size(), "All merchants should be loaded");

            // ============================================
            // Verify single-partition: GetAccount
            // ============================================
            VoltTable account = app.getAccount(1L);
            assertTrue(account.advanceRow(), "Should find account 1");
            assertEquals("Alice Johnson", account.getString("NAME"));

            // ============================================
            // Verify single-partition: UpsertAccount (update existing)
            // ============================================
            app.upsertAccount(1L, (byte) 1, 100.0, 15000.0, "Alice Updated", "alice@example.com");
            account = app.getAccount(1L);
            assertTrue(account.advanceRow(), "Should find updated account");
            assertEquals("Alice Updated", account.getString("NAME"));

            // ============================================
            // Test: Normal transaction is ACCEPTED
            // ============================================
            VoltTable result = app.processTransaction(
                1L, 1001L, System.currentTimeMillis(), 1, 200.0, "DEV01");
            assertTrue(result.advanceRow(), "Should return a result");
            assertEquals(1, result.getLong("ACCEPTED"), "Normal transaction should be accepted");
            assertTrue(result.getString("REASON").contains("Accepted"));

            // ============================================
            // Test: Large transaction (>$5000) is REJECTED
            // ============================================
            result = app.processTransaction(
                2L, 1002L, System.currentTimeMillis(), 1, 6000.0, "DEV02");
            assertTrue(result.advanceRow());
            assertEquals(0, result.getLong("ACCEPTED"), "Large transaction should be rejected");
            assertTrue(result.getString("REASON").contains("Large Transaction"));

            // ============================================
            // Test: Disabled account is REJECTED
            // ============================================
            result = app.processTransaction(
                4L, 1003L, System.currentTimeMillis(), 1, 100.0, "DEV03");
            assertTrue(result.advanceRow());
            assertEquals(0, result.getLong("ACCEPTED"), "Disabled account should be rejected");
            assertTrue(result.getString("REASON").contains("Disabled"));

            // ============================================
            // Test: Invalid merchant is REJECTED
            // ============================================
            result = app.processTransaction(
                1L, 1004L, System.currentTimeMillis(), 9999, 100.0, "DEV04");
            assertTrue(result.advanceRow());
            assertEquals(0, result.getLong("ACCEPTED"), "Invalid merchant should be rejected");
            assertTrue(result.getString("REASON").contains("Invalid Merchant"));

            // ============================================
            // Test: Non-existent account returns empty
            // ============================================
            VoltTable emptyResult = app.getAccount(999999L);
            assertFalse(emptyResult.advanceRow(),
                "Should return empty result for non-existent account");

            // ============================================
            // Test: Velocity fraud — >5 txns in 30 seconds
            // ============================================
            // Send 6 rapid transactions for account 5 — the 7th should be rejected
            for (int i = 0; i < 6; i++) {
                app.processTransaction(
                    5L, 2000L + i, System.currentTimeMillis(), 1, 100.0, "DEV05");
            }
            result = app.processTransaction(
                5L, 2006L, System.currentTimeMillis(), 1, 100.0, "DEV05");
            assertTrue(result.advanceRow());
            assertEquals(0, result.getLong("ACCEPTED"),
                "Should reject after >5 transactions in 30 seconds");
            assertTrue(result.getString("REASON").contains("30 Seconds"));

            // ============================================
            // Cleanup
            // ============================================
            app.deleteAllData();

            VoltTable afterCleanup = app.getAccount(1L);
            assertFalse(afterCleanup.advanceRow(), "No data should remain after cleanup");

            System.out.println("\n*** ALL TESTS PASSED ***\n");

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            shutdownIfNeeded(db);
        }
    }
}
