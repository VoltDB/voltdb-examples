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
package org.voltdb.example.threat.pipelines;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.client.Client2;
import org.voltdbtest.testcontainer.VoltDBCluster;

import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;
import org.voltdb.example.threat.IntegrationTestBase;
import org.voltdb.example.threat.app.ThreatDetectionApp;
import org.voltdb.example.threat.common.CsvDataLoader;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * IT for the read path of {@link ReportingPipeline}. Seeds a Testcontainer VoltDB
 * with a mix of accepted and rejected transactions, then runs
 * {@link ReportingPipeline#applyRead} to prove the connector round-trip works
 * end-to-end: {@code ReadTxnsSince} SP → connector → {@code PCollection<Row>}
 * with the expected schema and row count.
 *
 * <p>GeoIP enrichment, BigQuery, and Iceberg writes are deliberately excluded —
 * they require GCP credentials + live external services and are covered by
 * the manual Dataflow verification run, not by this offline IT.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ReportingPipelineIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingPipelineIT.class);

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
    public void readTxnsSinceReturnsAllTxnsAboveWatermark() throws Exception {
        long baseTime = System.currentTimeMillis();

        // Seed 4 transactions at t0, plus 2 more at t0+60_000 (past a mid-range watermark).
        app.processRequest(1, 8001, baseTime,        1, 100.0, "dev-a", "192.168.1.10");
        app.processRequest(2, 8002, baseTime + 100,  2, 200.0, "dev-b", "10.0.0.5");
        app.processRequest(3, 8003, baseTime + 200,  1, 300.0, "dev-c", "10.0.0.6");
        app.processRequest(1, 8004, baseTime + 300,  2, 400.0, "dev-d", "192.168.1.20");
        long midWatermark = baseTime + 30_000;
        app.processRequest(2, 8005, baseTime + 60_000, 1, 50.0,  "dev-e", "10.0.0.7");
        app.processRequest(3, 8006, baseTime + 60_500, 2, 75.0,  "dev-f", "192.168.1.30");

        // ------------------------------------------------------------
        // 1. Watermark = epoch 0 → all 6 rows returned.
        // ------------------------------------------------------------
        Pipeline pAll = Pipeline.create(PipelineOptionsFactory.create());
        PCollection<Row> allRows = ReportingPipeline.applyRead(pAll, conn, 0L);
        PAssert.that("all rows since epoch",
                allRows.apply("CountAll", Count.globally()))
                .containsInAnyOrder(6L);
        PAssert.that("expected schema + TXN_IDs", allRows).satisfies(rows -> {
            Set<Long> ids = new HashSet<>();
            for (Row r : rows) {
                assertEquals(ReportingPipeline.TXN_SCHEMA, r.getSchema(),
                        "row schema must equal TXN_SCHEMA");
                assertNotNull(r.getValue("txn_time"), "txn_time must be set");
                ids.add(r.getInt64("txn_id"));
            }
            assertEquals(Set.of(8001L, 8002L, 8003L, 8004L, 8005L, 8006L), ids);
            return null;
        });
        pAll.run().waitUntilFinish();

        // ------------------------------------------------------------
        // 2. Watermark between the two groups → only the 2 later rows.
        // ------------------------------------------------------------
        Pipeline pDelta = Pipeline.create(PipelineOptionsFactory.create());
        PCollection<Row> deltaRows = ReportingPipeline.applyRead(pDelta, conn, midWatermark);
        PAssert.that("post-watermark count",
                deltaRows.apply("CountDelta", Count.globally()))
                .containsInAnyOrder(2L);
        PAssert.that("post-watermark TXN_IDs", deltaRows).satisfies(rows -> {
            Set<Long> ids = new HashSet<>();
            for (Row r : rows) {
                ids.add(r.getInt64("txn_id"));
            }
            assertEquals(Set.of(8005L, 8006L), ids);
            return null;
        });
        pDelta.run().waitUntilFinish();
    }
}
