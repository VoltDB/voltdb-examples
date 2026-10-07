/* SPDX-License-Identifier: MIT */
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
 * Publishes synthetic {@link PageHitEvent} messages to a Google Cloud PubSub
 * topic. Simulates web traffic on the threat-detection demo site so the ingest
 * pipeline ({@code PageHitsIngestPipeline}) has something to consume.
 *
 * <p>Three modes:
 * <ul>
 *   <li><b>steady</b> — publishes {@code --rate} events/sec spread across
 *       random {@code 10.0.0.0/8} IPs (~65k distinct /24 subnets), simulating
 *       normal traffic. Should NOT trip the subnet-rate rule.</li>
 *   <li><b>burst</b> — publishes {@code --rate} events/sec from randomised
 *       last-octet IPs within a single {@code --subnet}, simulating a bot scan.
 *       Pushes the target subnet's REQUESTS_PER_SUBNET count past threshold in
 *       a few seconds, so any subsequent transaction from that subnet is
 *       rejected atomically by {@code ProcessTransaction} with
 *       {@code RULE_NAME='SUBNET_RATE'}.</li>
 *   <li><b>mixed</b> — the realistic demo mode. 80% of events hit the target
 *       {@code --subnet} (the attack), 20% spread across ~30 real public /24s
 *       for background traffic. Each baseline subnet accumulates a handful of
 *       events over the run — enough to show up in warehouse analytics with
 *       {@code COUNT(*) > 1} per subnet, but far below the 100-hits-per-5s
 *       SUBNET_PAGE_HIT_RATE threshold. Produces data suitable for the
 *       Chart 1 subnet-rate timeline (burst subnet stands out against a
 *       non-trivial baseline).</li>
 * </ul>
 *
 * <p>Authenticates via Application Default Credentials: run
 * {@code gcloud auth application-default login} once on the machine where this
 * process runs.
 *
 * <p><b>CLI:</b>
 * <pre>
 *   --topic=projects/PROJECT/topics/TOPIC        default: threat-page-hits topic
 *   --mode=steady|burst                          default: steady
 *   --rate=N                                     events/sec, default: 100
 *   --duration=SECONDS                           default: 30
 *   --subnet=A.B.C                               /24 prefix for burst mode, default: 78.46.220
 * </pre>
 *
 * <p>Not a Beam pipeline — a plain Java process using
 * {@code com.google.cloud.pubsub.v1.Publisher} directly. The ingest pipeline
 * on the other side is {@code PageHitsIngestPipeline}.
 */
public final class PageHitsGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(PageHitsGenerator.class);

    private static final String DEFAULT_TOPIC =
            "projects/voltdb-operator/topics/threat-page-hits";
    // Hetzner /24 in Nuremberg DE. Real public unicast — MaxMind returns a
    // concrete country/city/lat/lon, so the burst subnet shows up on the geo
    // chart. Avoid RFC 5737 ranges (203.0.113.0/24 etc.) here: MaxMind returns
    // NULL for reserved blocks and the attacker becomes invisible on the map.
    private static final String DEFAULT_SUBNET = "78.46.220";
    private static final String PAGES_RESOURCE = "data/pages.csv";

    private PageHitsGenerator() {
        // static main only
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        List<String> urls = loadUrlCatalog();
        LOG.info("Loaded {} URLs from {}", urls.size(), PAGES_RESOURCE);
        LOG.info("Config: topic={}, mode={}, rate={}/s, duration={}s, subnet={}",
                parsed.topic, parsed.mode, parsed.ratePerSec, parsed.durationSec, parsed.subnet);

        TopicName topicName = TopicName.parse(parsed.topic);
        Publisher publisher = Publisher.newBuilder(topicName).build();

        AtomicLong published = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        try {
            run(publisher, parsed, urls, published, failures);
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(1, TimeUnit.MINUTES);
        }
        LOG.info("Done. published={} failures={}", published.get(), failures.get());
    }

    private static void run(Publisher publisher, Args parsed, List<String> urls,
                            AtomicLong published, AtomicLong failures) {
        long endAt = System.currentTimeMillis() + parsed.durationSec * 1000L;
        long periodNanos = TimeUnit.SECONDS.toNanos(1) / parsed.ratePerSec;
        long nextEmitAt = System.nanoTime();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        while (System.currentTimeMillis() < endAt) {
            PageHitEvent event;
            switch (parsed.mode) {
                case STEADY:
                    event = steadyEvent(urls);
                    break;
                case BURST:
                    event = burstEvent(parsed.subnet, urls);
                    break;
                case MIXED:
                    // 80% of events hit the target burst subnet (the attack);
                    // 20% spread across BASELINE_SUBNETS (realistic background
                    // traffic). Each baseline subnet accumulates a handful of
                    // events over the run — enough to show up in BQ with >1
                    // event, but far below the 100-hits-per-5s rule threshold.
                    if (r.nextInt(100) < 80) {
                        event = burstEvent(parsed.subnet, urls);
                    } else {
                        String bg = BASELINE_SUBNETS[r.nextInt(BASELINE_SUBNETS.length)];
                        event = burstEvent(bg, urls);
                    }
                    break;
                default:
                    throw new IllegalStateException("unknown mode: " + parsed.mode);
            }
            publishAsync(publisher, event, published, failures);

            nextEmitAt += periodNanos;
            long sleepNanos = nextEmitAt - System.nanoTime();
            if (sleepNanos > 0) {
                LockSupport.parkNanos(sleepNanos);
            } else {
                // Fell behind; skip the sleep and let the loop catch up.
                nextEmitAt = System.nanoTime();
            }
        }
    }

    /**
     * Baseline /24 pool used by MIXED mode. Picked from real public unicast
     * ranges across different ISPs/countries so GeoIP enrichment produces
     * meaningful country/city output across many subnets.
     */
    private static final String[] BASELINE_SUBNETS = {
            "78.46.1",        // Hetzner — DE Nuremberg
            "217.160.0",      // 1&1 IONOS — DE
            "213.75.203",     // KPN — NL
            "62.219.254",     // Bezeq — IL
            "195.113.4",      // CESNET — CZ
            "202.12.29",      // APNIC — JP
            "156.154.70",     // Neustar — US
            "200.160.7",      // Registro.br — BR
            "41.63.42",       // Africa IS — RSA
            "143.244.32",     // DigitalOcean — SG
            "104.21.19",      // Cloudflare edge — various
            "185.199.108",    // GitHub Pages / Fastly — various
            "151.101.1",      // Fastly — US/EU
            "81.2.69",        // UK ISP range
            "188.42.184",     // Rapidswitch — UK
            "139.59.0",       // DigitalOcean — IN
            "80.252.0",       // Various EU
            "91.189.88",      // Canonical — UK
            "203.133.1",      // AU ISP
            "212.58.246",     // BBC — UK
            "92.242.144",     // RU ISP
            "109.237.129",    // Various EU
            "122.56.0",       // AU
            "182.161.60",     // JP ISP
            "190.249.1",      // CO/CL ISP
            "196.3.182",      // ZA ISP
            "41.75.192",      // Africa
            "177.69.1",       // BR ISP
            "216.58.192",     // Google — US
            "17.253.144",     // Apple — US
    };

    private static void publishAsync(Publisher publisher, PageHitEvent event,
                                     AtomicLong published, AtomicLong failures) {
        // The page_hits_raw BQ sink has publish_time NOT NULL (declared in RUNBOOK §7a).
        // The PubSub-BQ subscription with --use-table-schema does not auto-populate it
        // from PubSub metadata — the generator must stamp it on the message body.
        event.setPublishTime(Instant.now());
        PubsubMessage msg = PubsubMessage.newBuilder()
                .setData(ByteString.copyFrom(event.toJson(), StandardCharsets.UTF_8))
                .build();
        ApiFuture<String> future = publisher.publish(msg);
        ApiFutures.addCallback(future, new ApiFutureCallback<String>() {
            @Override public void onSuccess(String messageId) {
                published.incrementAndGet();
            }
            @Override public void onFailure(Throwable t) {
                failures.incrementAndGet();
                LOG.warn("Publish failed: {}", t.toString());
            }
        }, MoreExecutors.directExecutor());
    }

    private static PageHitEvent steadyEvent(List<String> urls) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // Random /24 within 10.0.0.0/8 — 65k distinct subnets, safe well below the
        // subnet-rate threshold at demo volumes.
        String ip = "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
        return new PageHitEvent(ip, urls.get(r.nextInt(urls.size())), Instant.now());
    }

    private static PageHitEvent burstEvent(String subnet, List<String> urls) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String ip = subnet + "." + r.nextInt(1, 255);
        return new PageHitEvent(ip, urls.get(r.nextInt(urls.size())), Instant.now());
    }

    private static List<String> loadUrlCatalog() throws IOException {
        List<String> urls = new ArrayList<>();
        try (InputStream is = PageHitsGenerator.class.getClassLoader()
                .getResourceAsStream(PAGES_RESOURCE);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + PAGES_RESOURCE);
            }
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                urls.add(comma < 0 ? line.trim() : line.substring(0, comma).trim());
            }
        }
        if (urls.isEmpty()) {
            throw new IOException("URL catalog is empty: " + PAGES_RESOURCE);
        }
        return urls;
    }

    enum Mode { STEADY, BURST, MIXED }

    /** Simple `--key=value` CLI parser. */
    static final class Args {
        String topic = DEFAULT_TOPIC;
        Mode mode = Mode.STEADY;
        int ratePerSec = 100;
        int durationSec = 30;
        String subnet = DEFAULT_SUBNET;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (String arg : argv) {
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Expected --key=value, got: " + arg);
                }
                int eq = arg.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Expected --key=value, got: " + arg);
                }
                String key = arg.substring(2, eq);
                String value = arg.substring(eq + 1);
                switch (key) {
                    case "topic":    a.topic = value; break;
                    case "mode":     a.mode = Mode.valueOf(value.toUpperCase(Locale.ROOT)); break;
                    case "rate":     a.ratePerSec = Integer.parseInt(value); break;
                    case "duration": a.durationSec = Integer.parseInt(value); break;
                    case "subnet":   a.subnet = value; break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: --" + key);
                }
            }
            if (a.ratePerSec <= 0) {
                throw new IllegalArgumentException("--rate must be > 0");
            }
            if (a.durationSec <= 0) {
                throw new IllegalArgumentException("--duration must be > 0");
            }
            if (a.mode == Mode.BURST && a.subnet.split("\\.").length != 3) {
                throw new IllegalArgumentException(
                        "--subnet must be a 3-octet /24 prefix (e.g. 203.0.113), got: " + a.subnet);
            }
            return a;
        }
    }
}
