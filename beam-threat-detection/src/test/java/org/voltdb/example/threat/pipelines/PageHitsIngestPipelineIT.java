/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.pipelines;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageWithAttributesCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
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
import org.voltdb.client.ClientResponse;
import org.voltdbtest.testcontainer.VoltDBCluster;

import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;
import org.voltdb.example.threat.IntegrationTestBase;
import org.voltdb.example.threat.common.PageHitEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link PageHitsIngestPipeline#applyIngest}, running against
 * a VoltDB Testcontainer. Replaces the PubSub source with {@code Create.of(...)}
 * so the pipeline logic (parse → row → VoltDbIO.write, DLQ side output) is
 * exercised offline. Asserts:
 * <ul>
 *   <li>Valid page-hit messages end up in {@code SUBNET_REQUESTS} with
 *       {@code SOURCE_TYPE = 'PAGE'} and the expected /24 subnet key.</li>
 *   <li>Malformed messages surface in the DLQ side output with the raw payload
 *       preserved and error info stamped into attributes.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PageHitsIngestPipelineIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(PageHitsIngestPipelineIT.class);

    private static final int SUBNET_PREFIX_LENGTH = 24;

    private VoltDBCluster db;
    private Client2 client;
    private VoltDbIO.ConnectionConfig conn;

    @BeforeAll
    public void startCluster() throws Exception {
        db = createTestContainer();
        startAndConfigureTestContainer(db);
        client = db.getClient2();
        conn = VoltDbIO.connectionConfig()
                .withHosts(db.getHostAndPort())
                .withConnectionTimeout(60_000)
                .build();
        LOG.info("VoltDB testcontainer up at {}", db.getHostAndPort());
    }

    @AfterAll
    public void stopCluster() {
        shutdownIfNeeded(db);
    }

    @Test
    public void ingestsValidHitsAndDlqsInvalidOnes() throws Exception {
        Instant t0 = Instant.parse("2026-09-28T14:00:00Z");
        List<PubsubMessage> messages = new ArrayList<>();
        messages.add(pubsubJson(new PageHitEvent("203.0.113.42", "/products/42", t0)));
        messages.add(pubsubJson(new PageHitEvent("203.0.113.99", "/login", t0.plusSeconds(1))));
        messages.add(pubsubJson(new PageHitEvent("10.0.0.5", "/help", t0.plusSeconds(2))));
        // Malformed — not JSON.
        messages.add(new PubsubMessage("not-json".getBytes(StandardCharsets.UTF_8),
                Collections.emptyMap()));

        Pipeline p = Pipeline.create(PipelineOptionsFactory.create());
        PCollection<PubsubMessage> input = p.apply("SeedInput",
                Create.of(messages).withCoder(PubsubMessageWithAttributesCoder.of()));

        PCollection<PubsubMessage> dlq = PageHitsIngestPipeline.applyIngest(
                input, SUBNET_PREFIX_LENGTH, conn);

        PAssert.that(dlq).satisfies(iter -> {
            List<PubsubMessage> list = new ArrayList<>();
            iter.forEach(list::add);
            assertEquals(1, list.size(), "exactly one malformed message expected");
            PubsubMessage failed = list.get(0);
            assertEquals("not-json",
                    new String(failed.getPayload(), StandardCharsets.UTF_8),
                    "DLQ should preserve raw payload");
            Map<String, String> attrs = failed.getAttributeMap();
            assertNotNull(attrs.get("error.class"), "error.class attribute missing");
            assertNotNull(attrs.get("error.message"), "error.message attribute missing");
            return null;
        });

        p.run().waitUntilFinish();

        // The three valid hits should have been inserted into SUBNET_REQUESTS with
        // SOURCE_TYPE='PAGE'. Verify count, source type, and the /24 subnet key.
        ClientResponse resp = client.callProcedureSync("@AdHoc",
                "SELECT SUBNET, SOURCE_IP, SOURCE_TYPE, PAGE_URL FROM SUBNET_REQUESTS;");
        assertEquals(ClientResponse.SUCCESS, resp.getStatus(), resp.getStatusString());

        VoltTable rows = resp.getResults()[0];
        assertEquals(3, rows.getRowCount(), "one row per valid page hit");

        Set<String> subnetsSeen = new HashSet<>();
        Set<String> ipsSeen = new HashSet<>();
        while (rows.advanceRow()) {
            String subnet = rows.getString("SUBNET");
            String sourceIp = rows.getString("SOURCE_IP");
            String sourceType = rows.getString("SOURCE_TYPE");
            String pageUrl = rows.getString("PAGE_URL");
            assertEquals("PAGE", sourceType, "SOURCE_TYPE must be PAGE");
            assertNotNull(pageUrl, "PAGE_URL must be populated for page hits");
            subnetsSeen.add(subnet);
            ipsSeen.add(sourceIp);
        }
        // 203.0.113.42 and 203.0.113.99 share /24 subnet 203.0.113.0;
        // 10.0.0.5 gives 10.0.0.0 — two distinct subnets.
        assertEquals(new HashSet<>(java.util.Arrays.asList("203.0.113.0", "10.0.0.0")),
                subnetsSeen, "computed /24 subnet keys");
        assertEquals(new HashSet<>(java.util.Arrays.asList(
                        "203.0.113.42", "203.0.113.99", "10.0.0.5")),
                ipsSeen, "each source IP recorded exactly once");
    }

    private static PubsubMessage pubsubJson(PageHitEvent event) {
        return new PubsubMessage(
                event.toJson().getBytes(StandardCharsets.UTF_8),
                new HashMap<>());
    }
}
