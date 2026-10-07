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
package org.voltdb.example.threat.generator;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;

import org.voltdb.example.threat.app.ThreatDetectionApp;
import org.voltdb.example.threat.common.CidrUtils;
import org.voltdb.example.threat.common.CsvDataLoader;
import org.voltdb.example.threat.common.PageHitEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end coordinated scenario driver for the Chart 1 / Chart 2 narrative.
 * Replaces the shell orchestration of {@code PageHitsGenerator} +
 * {@code TransactionsGenerator} with a single self-contained process that
 * knows the full attack timeline and keeps page-hit publishing + transaction
 * firing in strict relative-time sync.
 *
 * <p><b>Timeline (relative to {@code anchorMs}, defaults to NOW):</b>
 * <pre>
 *   t=  0-30s   baseline page-hit traffic to BASELINE_SUBNETS (10 hits/s spread)
 *   t= 30-60s   PAGE BURST — 200 hits/s to the attack subnet via PubSub
 *               (natural ingest populates BQ.page_hits_raw > 1000 hits/5s,
 *                well above SUBNET_PAGE_HIT_RATE threshold = 500)
 *   t= 60-120s  baseline tail
 *
 *   t=  5,15s   NONE: accepted txns from attack subnet (quiet windows)
 *   t= 40,45,50 SUBNET_PAGE_HIT_RATE: 3 attacker txns DURING the burst
 *               (the SP sees the warm page-counter and rejects)
 *   t= 80s      SUBNET_TXN_RATE: 21 TXN warm-ups + 4 attacker txns in a 5s
 *               window (counter > 20 → rejected)
 *   t=100,115s  NONE: more accepted txns (burst has decayed; counters cold)
 * </pre>
 *
 * <p>The attack-subnet (default {@code 78.46.220.0/24}) is Hetzner Nuremberg —
 * real MaxMind geo so the chart's world map shows the attacker at a concrete
 * city, not NULL coordinates.
 *
 * <p>Produces exactly the data shape the notebook charts assume:
 * <ul>
 *   <li>{@code BQ.page_hits_raw}: baseline + burst + tail timeline, burst
 *       subnet dominant and above-threshold during t=30-60s.</li>
 *   <li>{@code VoltDB.TRANSACTIONS} (and via reporting pipeline {@code BQ.transactions}):
 *       12 attacker-subnet rows — 5 ACCEPTED, 3 SUBNET_PAGE_HIT_RATE, 4 SUBNET_TXN_RATE —
 *       with txn_time matching the chart's narrative windows.</li>
 * </ul>
 *
 * <p>CLI:
 * <pre>
 *   --host=HOST --port=PORT        VoltDB (default localhost:21212)
 *   --topic=TOPIC                  PubSub topic (default: threat-page-hits)
 *   --subnet=A.B.C                 /24 attack subnet (default: 78.46.220)
 *   --anchorMs=LONG                anchor epoch-ms; default: System.currentTimeMillis()
 * </pre>
 */
public final class NarrativeScene {

    private static final Logger LOG = LoggerFactory.getLogger(NarrativeScene.class);

    private static final String DEFAULT_TOPIC =
            "projects/voltdb-operator/topics/threat-page-hits";
    private static final String DEFAULT_SUBNET = "78.46.220";
    private static final String PAGES_RESOURCE = "data/pages.csv";

    /**
     * Baseline IPs chosen to hit real MaxMind geo so {@code BQ.transactions}'s
     * country/city/lat/lon come out populated. Used by the baseline-accepted
     * txns scattered through the narrative — gives the geo map a worldwide
     * backdrop of dots against which the attacker ring stands out.
     */
    private static final String[] BASELINE_IPS = {
            "8.8.8.8",         // Google DNS — US
            "217.160.0.1",     // 1&1 IONOS — Germany
            "213.75.203.5",    // KPN — Netherlands
            "62.219.254.109",  // Bezeq — Israel
            "195.113.4.8",     // CESNET — Czech Republic
            "202.12.29.60",    // APNIC — Japan
            "156.154.70.5",    // Neustar — US
            "200.160.7.130",   // Registro.br — Brazil
            "41.63.42.1",      // Africa Internet Solutions — South Africa
            "143.244.32.1",    // DigitalOcean — Singapore
    };

    /** Baseline /24s with real public geo — same pool as {@link PageHitsGenerator}. */
    private static final String[] BASELINE_SUBNETS = {
            "78.46.1",   "217.160.0", "213.75.203", "62.219.254", "195.113.4",
            "202.12.29", "156.154.70","200.160.7",  "41.63.42",   "143.244.32",
            "104.21.19", "185.199.108","151.101.1", "81.2.69",    "188.42.184",
            "139.59.0",  "80.252.0",  "91.189.88",  "203.133.1",  "212.58.246",
            "92.242.144","109.237.129","122.56.0",  "182.161.60", "190.249.1",
            "196.3.182", "41.75.192", "177.69.1",   "216.58.192", "17.253.144",
    };

    // Narrative timing (seconds, relative to anchorMs)
    private static final int T_BASELINE_END   = 30;
    private static final int T_BURST_END      = 60;
    private static final int T_TAIL_END       = 120;
    private static final int BASELINE_RATE    = 10;    // hits/sec across baseline subnets
    private static final int BURST_RATE       = 200;   // hits/sec to attack subnet

    private NarrativeScene() { /* static main only */ }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        List<String> urls = loadUrlCatalog();
        LOG.info("Narrative scene config: anchorMs={} topic={} subnet={} host={}:{}",
                parsed.anchorMs, parsed.topic, parsed.subnet, parsed.host, parsed.port);

        TopicName topicName = TopicName.parse(parsed.topic);
        Publisher publisher = Publisher.newBuilder(topicName).build();

        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(parsed.host, parsed.port);
        ThreatDetectionApp app = new ThreatDetectionApp(client);
        CsvDataLoader loader = new CsvDataLoader();
        loader.loadAccountData(app, "data/accounts.csv");
        loader.loadMerchantData(app, "data/merchants.csv");
        LOG.info("Connected to VoltDB; reference data loaded");

        AtomicLong published = new AtomicLong();
        AtomicLong failures  = new AtomicLong();
        try {
            // Thread 1: page-hit publishing timeline (baseline → burst → tail).
            Thread pageThread = new Thread(
                    () -> publishPageHits(publisher, parsed.subnet, parsed.anchorMs, urls, published, failures),
                    "narrative-pages");
            pageThread.start();

            // Main thread: coordinated txn firing.
            fireTxns(app, parsed.subnet, parsed.anchorMs);

            pageThread.join();
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(1, TimeUnit.MINUTES);
            client.close();
        }
        LOG.info("Scene done. page-hits: published={} failures={}", published.get(), failures.get());
    }

    // ─── Page-hit publishing timeline ─────────────────────────────────────────

    private static void publishPageHits(Publisher publisher, String burstSubnet, long anchorMs,
                                        List<String> urls, AtomicLong published, AtomicLong failures) {
        try {
            waitUntil(anchorMs);
            // Phase 1 (0-30s): baseline across BASELINE_SUBNETS at BASELINE_RATE hits/s.
            publishPhase(publisher, urls, published, failures, anchorMs, 0, T_BASELINE_END,
                         BASELINE_RATE, /* burstSubnet= */ null);
            // Phase 2 (30-60s): BURST — 100% to burstSubnet at BURST_RATE hits/s.
            publishPhase(publisher, urls, published, failures, anchorMs, T_BASELINE_END, T_BURST_END,
                         BURST_RATE, burstSubnet);
            // Phase 3 (60-120s): tail baseline across BASELINE_SUBNETS.
            publishPhase(publisher, urls, published, failures, anchorMs, T_BURST_END, T_TAIL_END,
                         BASELINE_RATE, null);
            LOG.info("Page-hit timeline complete");
        } catch (Exception e) {
            LOG.error("Page-hit publisher failed", e);
        }
    }

    /** Publish events at {@code ratePerSec} between [startSec, endSec] relative to anchorMs.
     *  If {@code burstSubnet} is non-null, every event goes to that /24; otherwise events
     *  spread uniformly across {@link #BASELINE_SUBNETS}. */
    private static void publishPhase(Publisher publisher, List<String> urls,
                                     AtomicLong published, AtomicLong failures,
                                     long anchorMs, int startSec, int endSec,
                                     int ratePerSec, String burstSubnet) {
        long periodNanos = TimeUnit.SECONDS.toNanos(1) / ratePerSec;
        long startMs = anchorMs + startSec * 1000L;
        long endMs   = anchorMs + endSec * 1000L;
        waitUntil(startMs);
        long nextEmitAt = System.nanoTime();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (System.currentTimeMillis() < endMs) {
            String subnet = burstSubnet != null
                    ? burstSubnet
                    : BASELINE_SUBNETS[r.nextInt(BASELINE_SUBNETS.length)];
            String ip = subnet + "." + r.nextInt(1, 255);
            PageHitEvent event = new PageHitEvent(ip, urls.get(r.nextInt(urls.size())), Instant.now());
            publishAsync(publisher, event, published, failures);
            nextEmitAt += periodNanos;
            long sleepNanos = nextEmitAt - System.nanoTime();
            if (sleepNanos > 0) LockSupport.parkNanos(sleepNanos);
            else nextEmitAt = System.nanoTime();
        }
    }

    private static void publishAsync(Publisher publisher, PageHitEvent event,
                                     AtomicLong published, AtomicLong failures) {
        // Match PageHitsGenerator: stamp publish_time so BQ page_hits_raw's NOT NULL column accepts it.
        event.setPublishTime(Instant.now());
        PubsubMessage msg = PubsubMessage.newBuilder()
                .setData(ByteString.copyFrom(event.toJson(), StandardCharsets.UTF_8))
                .build();
        ApiFuture<String> future = publisher.publish(msg);
        ApiFutures.addCallback(future, new ApiFutureCallback<String>() {
            @Override public void onSuccess(String id)         { published.incrementAndGet(); }
            @Override public void onFailure(Throwable t)       { failures.incrementAndGet();
                LOG.warn("Publish failed: {}", t.toString()); }
        }, MoreExecutors.directExecutor());
    }

    // ─── Txn firing timeline ─────────────────────────────────────────────────

    private static void fireTxns(ThreatDetectionApp app, String burstSubnet, long anchorMs)
            throws Exception {
        // Baseline-subnet accepted txns scattered across the narrative — they feed
        // the chart's world map (BQ.transactions is where the GeoIP enrichment lands,
        // so a subnet needs at least one txn here to show up as a geo dot). These
        // use BASELINE_IPS which MaxMind resolves to concrete cities worldwide.
        for (int i = 0; i < BASELINE_IPS.length; i++) {
            int tSec = 10 + i * 11;   // 10, 21, 32, ..., 109 — spread across the 120s window
            fireAcceptedAt(app, anchorMs, tSec, 1L + (i % 5), BASELINE_IPS[i], 50.0 + i * 30);
        }

        // Burst-subnet accepted txns in quiet windows (before/after burst).
        fireAcceptedAt(app, anchorMs,   5, 1L, burstSubnet + ".11",  87.50);
        fireAcceptedAt(app, anchorMs,  15, 2L, burstSubnet + ".23", 249.99);

        // SUBNET_PAGE_HIT_RATE: 3 attacker txns DURING the burst.
        // Natural PubSub burst at 200/s to this subnet → BQ page_hits_raw > 1000/5s.
        // Streaming ingest lag can leave VoltDB SUBNET_REQUESTS behind, so we also
        // insert a 501-record warm-up at the attacker-txn moment to guarantee the
        // SP's rate check sees PAGE_COUNT > 500 and SUBNET_PAGE_HIT_RATE fires.
        fireSubnetPageHitRejectAt(app, anchorMs, 40, 3L, burstSubnet + ".199", 1250.0);
        fireSubnetPageHitRejectAt(app, anchorMs, 45, 4L, burstSubnet + ".217",  980.0);
        fireSubnetPageHitRejectAt(app, anchorMs, 50, 5L, burstSubnet + ".88",   420.0);

        // Chart-2 Scenario B — VELOCITY_BURST alone. Eve (account 5L) fires 6 txns in
        // 6 seconds; her per-account 30s counter crosses the threshold on the 6th and
        // all subsequent. Subnet-wide txn count stays well under SUBNET_TXN_RATE
        // threshold (20/5s), so only VELOCITY_BURST fires. ~2 txns rejected.
        seedVelocityBurstAt(app, anchorMs, 68, burstSubnet);

        // Chart-2 Scenario A — SUBNET_TXN_RATE alone. Warm subnet TXN counter with 21
        // requests + fire 4 attacker attempts in the same 5s window.
        fireSubnetTxnRejectBatchAt(app, anchorMs, 80, burstSubnet, new double[]{310, 125, 440, 215});

        // Chart-2 Scenario C — BOTH rules fire in the same window. Eve's 30s counter
        // is still elevated from Scenario B (txns at t=68..73 fall inside [t-30, t]
        // for t≤103), so new Eve txns trip VELOCITY_BURST (SP checks it first). Other
        // accounts in the same warmed-subnet window trip SUBNET_TXN_RATE.
        seedCombinedBurstAt(app, anchorMs, 95, burstSubnet);

        // Accepted tail (burst decayed, counters cold).
        fireAcceptedAt(app, anchorMs, 108, 1L, burstSubnet + ".54", 175.00);
        fireAcceptedAt(app, anchorMs, 115, 2L, burstSubnet + ".77", 119.00);
    }

    /**
     * Scenario B — VELOCITY_BURST alone. Fires 6 Eve (account 5L) txns 1s apart
     * starting at {@code tSec}. The baseline Eve txn at t=54s puts her 30s rolling
     * counter at 1 before this scenario; the 2nd-5th txns here keep her at 2-5
     * (ACCEPT); the 6th makes her 6 → VELOCITY_BURST rejects it and any later.
     * Subnet TXN count stays low (6 total in the 5s burst window), well under
     * {@code SUBNET_TXN_RATE} threshold.
     */
    private static void seedVelocityBurstAt(ThreatDetectionApp app, long baseTime, int tSec,
                                            String burstSubnet) throws Exception {
        long base = System.nanoTime() & 0x7FFFFFFFL;
        String eveIp = burstSubnet + ".9";   // consistent IP for Eve across the scene
        for (int i = 0; i < 6; i++) {
            long t = baseTime + (tSec + i) * 1000L;
            waitUntil(t);
            VoltTable r = app.processRequest(5L, base + i, t, 1, 50.0 + i * 10.0,
                    "dev-velocity-burst", eveIp);
            r.advanceRow();
            LOG.info("t+{}s velocity-burst Eve txn #{} → ACCEPTED={} rule={}",
                    tSec + i, i + 1, r.getLong("ACCEPTED"), r.getString("RULE_NAME"));
        }
    }

    /**
     * Scenario C — both rules fire in the same window. Warms the subnet TXN
     * counter past threshold, then fires 2 Eve txns (whose 30s counter is still
     * elevated from Scenario B at t=68..73 → VELOCITY_BURST wins since the SP
     * checks it before SUBNET_TXN_RATE) and 2 non-Eve txns (clean velocity →
     * SUBNET_TXN_RATE wins). 4 rejections total.
     */
    private static void seedCombinedBurstAt(ThreatDetectionApp app, long baseTime, int tSec,
                                            String burstSubnet) throws Exception {
        long t = baseTime + tSec * 1000L;
        waitUntil(t);
        String subnet = CidrUtils.extractSubnet(burstSubnet + ".1", 24);
        long base = System.nanoTime() & 0x7FFFFFFFL;
        // Warm subnet TXN counter: 21 requests in the same 5s window.
        for (int i = 0; i < 21; i++) {
            app.recordSubnetRequest(subnet, base + i, burstSubnet + "." + (i % 254 + 1), t);
        }
        // Eve attempts → VELOCITY_BURST (SP checks first; her per-account 30s count already > 5).
        String eveIp = burstSubnet + ".9";
        for (int k = 0; k < 2; k++) {
            ThreatDetectionApp.SubnetRates rates =
                    app.recordSubnetRequest(subnet, base + 21 + k, eveIp, t + k);
            VoltTable r = app.processTransaction(5L, base + 21 + k, t + k, 1, 100.0 + k * 10,
                    "dev-combined-eve", eveIp, rates.pageCount, rates.txnCount);
            r.advanceRow();
            LOG.info("t+{}s combined Eve #{} → ACCEPTED={} rule={}",
                    tSec, k + 1, r.getLong("ACCEPTED"), r.getString("RULE_NAME"));
        }
        // Non-Eve attempts → SUBNET_TXN_RATE (velocity doesn't apply, subnet counter > 20).
        long[] otherAccts = {1L, 2L};
        for (int k = 0; k < otherAccts.length; k++) {
            String ip = burstSubnet + "." + (120 + k);
            ThreatDetectionApp.SubnetRates rates =
                    app.recordSubnetRequest(subnet, base + 23 + k, ip, t + 2 + k);
            VoltTable r = app.processTransaction(otherAccts[k], base + 23 + k, t + 2 + k,
                    1, 200.0 + k * 10, "dev-combined-other", ip, rates.pageCount, rates.txnCount);
            r.advanceRow();
            LOG.info("t+{}s combined other-acct #{} → ACCEPTED={} rule={}",
                    tSec, k + 1, r.getLong("ACCEPTED"), r.getString("RULE_NAME"));
        }
    }

    private static void fireAcceptedAt(ThreatDetectionApp app, long anchorMs, int tSec,
                                       long accountId, String ip, double amount) throws Exception {
        long t = anchorMs + tSec * 1000L;
        waitUntil(t);
        long id = System.nanoTime() & 0x7FFFFFFFL;
        VoltTable r = app.processRequest(accountId, id, t, 1, amount, "dev-narrative", ip);
        r.advanceRow();
        LOG.info("t+{}s accepted acct={} ip={} → ACCEPTED={} rule={}",
                tSec, accountId, ip, r.getLong("ACCEPTED"), r.getString("RULE_NAME"));
    }

    private static void fireSubnetPageHitRejectAt(ThreatDetectionApp app, long anchorMs, int tSec,
                                                  long accountId, String ip, double amount) throws Exception {
        long t = anchorMs + tSec * 1000L;
        waitUntil(t);
        String subnet = CidrUtils.extractSubnet(ip, 24);
        long base = System.nanoTime() & 0x7FFFFFFFL;
        // Warm page counter in VoltDB SUBNET_REQUESTS (501 > threshold 500).
        for (int i = 0; i < 501; i++) {
            app.recordPageHit(subnet, base + i, subnet + "." + (i % 254 + 1), "/narrative/" + i, t);
        }
        ThreatDetectionApp.SubnetRates rates = app.recordSubnetRequest(subnet, base + 501, ip, t);
        VoltTable r = app.processTransaction(accountId, base + 501, t, 1, amount, "dev-narrative-attacker",
                ip, rates.pageCount, rates.txnCount);
        r.advanceRow();
        LOG.info("t+{}s SUBNET_PAGE_HIT_RATE acct={} ip={} → ACCEPTED={} rule={}",
                tSec, accountId, ip, r.getLong("ACCEPTED"), r.getString("RULE_NAME"));
    }

    private static void fireSubnetTxnRejectBatchAt(ThreatDetectionApp app, long anchorMs, int tSec,
                                                   String burstSubnet, double[] amounts) throws Exception {
        long t = anchorMs + tSec * 1000L;
        waitUntil(t);
        String subnet = burstSubnet + ".0";
        subnet = CidrUtils.extractSubnet(burstSubnet + ".1", 24);
        long base = System.nanoTime() & 0x7FFFFFFFL;
        // Warm the TXN counter past threshold 20 (insert 21 TXN requests in same 5s window).
        for (int i = 0; i < 21; i++) {
            app.recordSubnetRequest(subnet, base + i, burstSubnet + "." + (i % 254 + 1), t);
        }
        // Fire the batch of attacker txns — each one's rate check will see > 20.
        for (int j = 0; j < amounts.length; j++) {
            String ip = burstSubnet + "." + (100 + j);
            ThreatDetectionApp.SubnetRates rates = app.recordSubnetRequest(subnet, base + 21 + j, ip, t + j);
            VoltTable r = app.processTransaction((long) (j + 1), base + 21 + j, t + j,
                    1, amounts[j], "dev-narrative-txn-burst", ip, rates.pageCount, rates.txnCount);
            r.advanceRow();
            LOG.info("t+{}s SUBNET_TXN_RATE acct={} ip={} → ACCEPTED={} rule={}",
                    tSec, j + 1, ip, r.getLong("ACCEPTED"), r.getString("RULE_NAME"));
        }
    }

    private static void waitUntil(long epochMs) {
        long sleep = epochMs - System.currentTimeMillis();
        if (sleep > 0) {
            try { Thread.sleep(sleep); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<String> loadUrlCatalog() throws IOException {
        List<String> urls = new ArrayList<>();
        try (InputStream is = NarrativeScene.class.getClassLoader().getResourceAsStream(PAGES_RESOURCE);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            if (is == null) throw new IOException("Resource not found: " + PAGES_RESOURCE);
            String line; boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                urls.add(comma < 0 ? line.trim() : line.substring(0, comma).trim());
            }
        }
        if (urls.isEmpty()) throw new IOException("URL catalog is empty: " + PAGES_RESOURCE);
        return urls;
    }

    static final class Args {
        String host  = "localhost";
        int port     = 21212;
        String topic = DEFAULT_TOPIC;
        String subnet = DEFAULT_SUBNET;
        long anchorMs = System.currentTimeMillis();

        static Args parse(String[] argv) {
            Args a = new Args();
            for (String arg : argv) {
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Expected --key=value, got: " + arg);
                }
                int eq = arg.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("Expected --key=value, got: " + arg);
                String key = arg.substring(2, eq).toLowerCase(Locale.ROOT);
                String val = arg.substring(eq + 1);
                switch (key) {
                    case "host":     a.host = val; break;
                    case "port":     a.port = Integer.parseInt(val); break;
                    case "topic":    a.topic = val; break;
                    case "subnet":   a.subnet = val; break;
                    case "anchorms": a.anchorMs = Long.parseLong(val); break;
                    default: throw new IllegalArgumentException("Unknown arg: --" + key);
                }
            }
            if (a.subnet.split("\\.").length != 3) {
                throw new IllegalArgumentException("--subnet must be a /24 prefix (A.B.C), got: " + a.subnet);
            }
            return a;
        }
    }
}
