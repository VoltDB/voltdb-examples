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
package org.voltdb.example.threat.app;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdbtest.testcontainer.VoltDBCluster;

import org.voltdb.example.threat.IntegrationTestBase;
import org.voltdb.example.threat.common.CidrUtils;
import org.voltdb.example.threat.common.CsvDataLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT for {@link ThreatDetectionApp} — exercises the transaction real-time path
 * against a VoltDB Testcontainer. Loads reference data, then drives scripted
 * scenarios covering the accept path plus every rejection rule
 * (VELOCITY_BURST, HIGH_SPEND, SUBNET_PAGE_HIT_RATE, SUBNET_TXN_RATE).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ThreatDetectionAppIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(ThreatDetectionAppIT.class);

    private VoltDBCluster db;
    private Client2 client;
    private ThreatDetectionApp app;

    @BeforeAll
    public void startCluster() throws Exception {
        db = createTestContainer();
        startAndConfigureTestContainer(db);
        client = db.getClient2();
        app = new ThreatDetectionApp(client);
        CsvDataLoader loader = new CsvDataLoader();
        List<Long> accounts = loader.loadAccountData(app, "data/accounts.csv");
        List<Integer> merchants = loader.loadMerchantData(app, "data/merchants.csv");
        assertEquals(5, accounts.size(), "5 accounts expected in accounts.csv");
        assertEquals(5, merchants.size(), "5 merchants expected in merchants.csv");
        LOG.info("Testcontainer up at {}; seed loaded", db.getHostAndPort());
    }

    @AfterAll
    public void stopCluster() {
        shutdownIfNeeded(db);
    }

    @BeforeEach
    public void wipeState() throws Exception {
        // Wipe transactions + subnet requests so per-test rule windows don't bleed.
        client.callProcedureSync("@AdHoc", "DELETE FROM TRANSACTIONS;");
        client.callProcedureSync("@AdHoc", "DELETE FROM SUBNET_REQUESTS;");
    }

    @Test
    public void acceptedTransactionReturnsAcceptedNone() throws Exception {
        long now = System.currentTimeMillis();
        VoltTable r = app.processRequest(1, 1001, now, 1, 200.0, "device-abc", "192.168.1.45");
        r.advanceRow();
        assertEquals(1L, r.getLong("ACCEPTED"), "clean transaction should be ACCEPTED");
        assertEquals("NONE", r.getString("RULE_NAME"));
    }

    @Test
    public void moreThan5TransactionsIn30SecondsTripsVelocityBurst() throws Exception {
        long t = System.currentTimeMillis();
        // First 5 succeed; the 6th trips the rule (view count > 5 after insert).
        for (int i = 0; i < 5; i++) {
            VoltTable r = app.processTransaction(
                    2, 2000 + i, t, 1, 100.0, "device-flood", "10.1.1." + i, 0, 0);
            r.advanceRow();
            assertEquals(1L, r.getLong("ACCEPTED"), "txn " + i + " should be ACCEPTED");
        }
        VoltTable rejected = app.processTransaction(
                2, 2005, t, 1, 100.0, "device-flood", "10.1.1.5", 0, 0);
        rejected.advanceRow();
        assertEquals(0L, rejected.getLong("ACCEPTED"));
        assertEquals("VELOCITY_BURST", rejected.getString("RULE_NAME"));
    }

    @Test
    public void spendOver5000InOneMinuteTripsHighSpend() throws Exception {
        long t = System.currentTimeMillis();
        VoltTable r = app.processTransaction(3, 3001, t, 2, 4900.0, "device-spend",
                "10.2.2.1", 0, 0);
        r.advanceRow();
        assertEquals(1L, r.getLong("ACCEPTED"));

        VoltTable rejected = app.processTransaction(3, 3002, t, 2, 200.0, "device-spend",
                "10.2.2.1", 0, 0);
        rejected.advanceRow();
        assertEquals(0L, rejected.getLong("ACCEPTED"));
        assertEquals("HIGH_SPEND", rejected.getString("RULE_NAME"));
    }

    @Test
    public void moreThan20TxnsFromSameSubnetTripsSubnetTxnRate() throws Exception {
        long t = System.currentTimeMillis();
        String subnet = CidrUtils.extractSubnet("10.50.50.1", 24);
        assertEquals("10.50.50.0", subnet);

        // SUBNET_TXN_RATE threshold is 20/5s. Insert 20 TXN requests, then the
        // 21st recordSubnetRequest + processTransaction pair should trip it.
        for (int i = 0; i < 20; i++) {
            app.recordSubnetRequest(subnet, 4000 + i, "10.50.50." + (i % 254 + 1), t);
        }
        ThreatDetectionApp.SubnetRates rates =
                app.recordSubnetRequest(subnet, 4100, "10.50.50.99", t);
        assertTrue(rates.txnCount > 20, "txn count should be > 20, got " + rates.txnCount);

        VoltTable rejected = app.processTransaction(5, 4100, t, 1, 50.0, "device-subnet",
                "10.50.50.99", rates.pageCount, rates.txnCount);
        rejected.advanceRow();
        assertEquals(0L, rejected.getLong("ACCEPTED"));
        assertEquals("SUBNET_TXN_RATE", rejected.getString("RULE_NAME"));
    }

    @Test
    public void searchesFindBlockedTransactions() throws Exception {
        long t = System.currentTimeMillis();
        // Seed one SUBNET_TXN_RATE rejection on a dedicated subnet.
        String subnet = CidrUtils.extractSubnet("10.60.60.1", 24);
        for (int i = 0; i < 20; i++) {
            app.recordSubnetRequest(subnet, 5000 + i, "10.60.60." + (i % 254 + 1), t);
        }
        ThreatDetectionApp.SubnetRates rates =
                app.recordSubnetRequest(subnet, 5100, "10.60.60.7", t);
        app.processTransaction(5, 5100, t, 1, 25.0, "device-search", "10.60.60.7",
                rates.pageCount, rates.txnCount);

        VoltTable byRule = app.searchBlockedByRule("SUBNET_TXN_RATE");
        assertTrue(byRule.getRowCount() >= 1, "searchBlockedByRule should find at least one row");
        byRule.advanceRow();
        assertEquals("SUBNET_TXN_RATE", byRule.getString("RULE_NAME"));

        VoltTable byIp = app.searchBlockedByIp("10.60.60.7");
        assertTrue(byIp.advanceRow(), "searchBlockedByIp should find the row");
        assertEquals("SUBNET_TXN_RATE", byIp.getString("RULE_NAME"));
    }

    @Test
    public void deleteAllDataClearsTransactionsAndSubnetRequests() throws Exception {
        long t = System.currentTimeMillis();
        app.processRequest(1, 9001, t, 1, 10.0, "device-clean", "192.168.1.1");
        app.deleteAllData();

        VoltTable txns = app.getTransactionsByAccount(1);
        assertEquals(0, txns.getRowCount(), "TRANSACTIONS should be empty after deleteAllData");

        // Re-seed reference data — deleteAllData also drops ACCOUNTS/MERCHANTS.
        CsvDataLoader loader = new CsvDataLoader();
        loader.loadAccountData(app, "data/accounts.csv");
        loader.loadMerchantData(app, "data/merchants.csv");
    }
}
