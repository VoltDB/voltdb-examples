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
package org.voltdb.example.threat;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageWithAttributesCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdbtest.testcontainer.VoltDBCluster;

import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;
import org.voltdb.example.threat.app.ThreatDetectionApp;
import org.voltdb.example.threat.common.CidrUtils;
import org.voltdb.example.threat.common.CsvDataLoader;
import org.voltdb.example.threat.common.PageHitEvent;
import org.voltdb.example.threat.pipelines.PageHitsIngestPipeline;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that a page-hit burst on a subnet blocks the next
 * transaction attempt from that subnet.
 *
 * <p>Scenario: a bot hits the site's public pages from random IPs within
 * subnet 203.0.113.0/24. The page-hit activity alone pushes
 * {@code PAGES_PER_SUBNET} past the 500/5s threshold. When a transaction
 * subsequently arrives from any IP in that subnet, {@code ProcessTransaction}
 * reads the subnet's page-hit count via the pre-fetched value from
 * {@code RecordSubnetRequest} and rejects the transaction atomically with
 * {@code RULE_NAME='SUBNET_PAGE_HIT_RATE'} — before any fraudulent charge
 * is executed.
 *
 * <p>Wiring:
 * <ol>
 *   <li>Testcontainer boots VoltDB with the DDL + SPs.</li>
 *   <li>Reference data loaded via {@link CsvDataLoader} (5 accounts, 5 merchants).</li>
 *   <li>Baseline: submit a transaction from subnet 203.0.113.0 BEFORE any page
 *       hits — assert it's accepted. Confirms the subnet is otherwise clean.</li>
 *   <li>Page-hit burst: feed 600 events from that subnet through
 *       {@link PageHitsIngestPipeline#applyIngest} against the live cluster.</li>
 *   <li>Follow-up transaction from a different IP in the same subnet — assert
 *       {@code RULE_NAME='SUBNET_PAGE_HIT_RATE'}.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PageHitBurstBlocksNextTxnIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(PageHitBurstBlocksNextTxnIT.class);

    private static final String BOT_SUBNET_PREFIX = "203.0.113";
    private static final String BOT_SUBNET_KEY = "203.0.113.0";
    private static final int SUBNET_PREFIX_LENGTH = 24;
    // SUBNET_PAGE_HIT_RATE threshold is 500/5s. 600 page hits in one window
    // puts the counter at 600 > 500 → the next transaction from this subnet
    // trips the rule.
    private static final int BURST_SIZE = 600;

    private VoltDBCluster db;
    private Client2 client;
    private ThreatDetectionApp app;
    private VoltDbIO.ConnectionConfig conn;

    @BeforeAll
    public void startCluster() throws Exception {
        db = createTestContainer();
        startAndConfigureTestContainer(db);
        client = db.getClient2();
        app = new ThreatDetectionApp(client);
        CsvDataLoader loader = new CsvDataLoader();
        loader.loadAccountData(app, "data/accounts.csv");
        loader.loadMerchantData(app, "data/merchants.csv");
        conn = VoltDbIO.connectionConfig()
                .withHosts(db.getHostAndPort())
                .withConnectionTimeout(60_000)
                .build();
        LOG.info("Testcontainer up at {}; seed loaded", db.getHostAndPort());
    }

    @AfterAll
    public void stopCluster() {
        shutdownIfNeeded(db);
    }

    @Test
    public void pageHitBurstFromOneSubnetCausesNextTxnRejectionWithSubnetPageHitRate() throws Exception {
        long now = System.currentTimeMillis();

        // ------------------------------------------------------------
        // 1. Baseline — a transaction from the target subnet with no
        //    prior activity should be accepted. Sanity check.
        // ------------------------------------------------------------
        VoltTable baseline = app.processRequest(1, 7001, now, 1, 100.0,
                "device-baseline", BOT_SUBNET_PREFIX + ".7");
        baseline.advanceRow();
        assertEquals(1L, baseline.getLong("ACCEPTED"),
                "clean baseline transaction from " + BOT_SUBNET_KEY + " should be ACCEPTED");
        assertEquals("NONE", baseline.getString("RULE_NAME"));

        // ------------------------------------------------------------
        // 2. Page-hit burst through applyIngest — 150 hits into the
        //    same subnet, all timestamped near `burstTime` so they
        //    fall in one REQUESTS_PER_SUBNET 5s window.
        // ------------------------------------------------------------
        Instant burstTime = Instant.ofEpochMilli(now).plusSeconds(10);
        List<PubsubMessage> messages = new ArrayList<>(BURST_SIZE);
        for (int i = 0; i < BURST_SIZE; i++) {
            String ip = BOT_SUBNET_PREFIX + "." + (i % 254 + 1);
            PageHitEvent event = new PageHitEvent(ip, "/products/" + i,
                    burstTime.plusMillis(i));
            messages.add(new PubsubMessage(
                    event.toJson().getBytes(StandardCharsets.UTF_8),
                    new HashMap<>()));
        }

        Pipeline p = Pipeline.create(PipelineOptionsFactory.create());
        PCollection<PubsubMessage> input = p.apply("SeedBurst",
                Create.of(messages).withCoder(PubsubMessageWithAttributesCoder.of()));
        PageHitsIngestPipeline.applyIngest(input, SUBNET_PREFIX_LENGTH, conn);
        p.run().waitUntilFinish();

        // Sanity: 600 page hits arrived with SOURCE_TYPE='PAGE' for the target subnet.
        VoltTable pageRows = client.callProcedureSync("@AdHoc",
                "SELECT COUNT(*) FROM SUBNET_REQUESTS "
                + "WHERE SUBNET = '" + BOT_SUBNET_KEY + "' AND SOURCE_TYPE = 'PAGE';")
                .getResults()[0];
        pageRows.advanceRow();
        assertEquals((long) BURST_SIZE, pageRows.getLong(0),
                "expected " + BURST_SIZE + " PAGE rows in subnet " + BOT_SUBNET_KEY);

        // ------------------------------------------------------------
        // 3. Money shot: a transaction from a DIFFERENT IP in the same
        //    subnet arrives. ProcessTransaction reads the subnet's page-hit
        //    (page-only count, well over 500) and rejects with
        //    RULE_NAME='SUBNET_PAGE_HIT_RATE'.
        // ------------------------------------------------------------
        long attackTimeMs = burstTime.toEpochMilli() + 500; // same 5s window
        VoltTable rejected = app.processRequest(2, 7002, attackTimeMs, 1, 50.0,
                "device-attacker", BOT_SUBNET_PREFIX + ".199");
        rejected.advanceRow();
        assertEquals(0L, rejected.getLong("ACCEPTED"),
                "transaction should be rejected due to shared subnet counter");
        assertEquals("SUBNET_PAGE_HIT_RATE", rejected.getString("RULE_NAME"),
                "REASON was: " + rejected.getString("REASON"));

        // Extra evidence for the blog post: the rejected row is in TRANSACTIONS
        // with ACCEPTED=0 + RULE_NAME='SUBNET_PAGE_HIT_RATE', findable by IP.
        VoltTable byIp = app.searchBlockedByIp(BOT_SUBNET_PREFIX + ".199");
        assertTrue(byIp.advanceRow(),
                "SearchBlockedByIp should find the attacker transaction");
        assertEquals("SUBNET_PAGE_HIT_RATE", byIp.getString("RULE_NAME"));

        // And the subnet has BOTH source types in SUBNET_REQUESTS, proving the
        // page-hit path and transaction path both wrote to the shared state.
        VoltTable sourceMix = client.callProcedureSync("@AdHoc",
                "SELECT SOURCE_TYPE, COUNT(*) FROM SUBNET_REQUESTS "
                + "WHERE SUBNET = '" + BOT_SUBNET_KEY + "' GROUP BY SOURCE_TYPE;")
                .getResults()[0];
        int pageCount = 0, txnCount = 0;
        while (sourceMix.advanceRow()) {
            String type = sourceMix.getString(0);
            long count = sourceMix.getLong(1);
            if ("PAGE".equals(type)) pageCount = (int) count;
            else if ("TXN".equals(type)) txnCount = (int) count;
        }
        assertNotEquals(0, pageCount, "expected PAGE rows in the shared counter");
        assertNotEquals(0, txnCount, "expected TXN rows in the shared counter");
        LOG.info("Both sources recorded in subnet {}: {} PAGE + {} TXN rows",
                BOT_SUBNET_KEY, pageCount, txnCount);
    }
}
