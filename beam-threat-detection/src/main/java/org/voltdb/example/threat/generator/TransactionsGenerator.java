/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;

import org.voltdb.example.threat.app.ThreatDetectionApp;
import org.voltdb.example.threat.common.CidrUtils;
import org.voltdb.example.threat.common.CsvDataLoader;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Populates a VoltDB cluster's {@code TRANSACTIONS} table with synthetic
 * transactions. Intended for demo setup and re-runs.
 *
 * <p>Reference data (accounts + merchants) is UPSERTed from
 * {@code data/accounts.csv} and {@code data/merchants.csv} at the start of
 * every run — safe to re-run without pre-cleanup.
 *
 * <p>CLI:
 * <pre>
 *   --host=HOST --port=PORT               default: localhost:21212
 *   --mode=mixed|steady|burst              default: mixed
 *   --count=N                              accepted-txn base count; default: 40
 *   --seed=LONG                            RNG seed for reproducible runs; default: current time
 * </pre>
 *
 * <p>Modes:
 * <ul>
 *   <li><b>mixed</b> — {@code N} accepted transactions from a rotating set of
 *       real public IPs (varied countries → meaningful GeoIP output), plus a
 *       small deterministic set of rejections: VALIDATION (disabled account,
 *       invalid account), VELOCITY_BURST (velocity), HIGH_SPEND
 *       (spend), and SUBNET_RATE (subnet warmed locally, then a txn from it).
 *       This is the default and produces a reporting-friendly rejection mix.</li>
 *   <li><b>steady</b> — only accepted transactions from real public IPs. Useful
 *       for growing the row count without introducing new rejections.</li>
 *   <li><b>burst</b> — {@code N} accepted transactions all from the same
 *       account with tight timestamps, followed by one that trips
 *       VELOCITY_BURST.</li>
 * </ul>
 *
 * <p>Public IPs used are chosen from well-known public-DNS/service ranges so
 * that GeoIP lookups return real country/city results in the reporting
 * pipeline.
 */
public final class TransactionsGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionsGenerator.class);

    /**
     * Real public IPs picked for geographic diversity in GeoIP output.
     * Avoids anycast addresses (1.1.1.1, 8.8.4.4) because GeoLite2 nulls them —
     * only unicast addresses with unambiguous physical locations belong here.
     */
    private static final String[] PUBLIC_IPS = {
            "8.8.8.8",           // Google DNS — US (widely geolocated to CA)
            "217.160.0.1",       // 1&1 IONOS — Germany, unicast (real geo)
            "213.75.203.5",      // KPN — Netherlands
            "62.219.254.109",    // Bezeq — Israel
            "195.113.4.8",       // CESNET — Czech Republic
            "202.12.29.60",      // APNIC — Japan
            "156.154.70.5",      // Neustar DNS — US
            "200.160.7.130",     // Registro.br — Brazil
            "41.63.42.1",        // Africa Internet Solutions — RSA
            "143.244.32.1",      // DigitalOcean — Singapore
    };

    private TransactionsGenerator() {
        // static main only
    }

    public static void main(String[] args) throws Exception {
        Args opts = Args.parse(args);
        LOG.info("Config: host={}:{} mode={} count={} seed={}",
                opts.host, opts.port, opts.mode, opts.count, opts.seed);

        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(opts.host, opts.port);
        LOG.info("Connected to VoltDB at {}:{}", opts.host, opts.port);

        try {
            ThreatDetectionApp app = new ThreatDetectionApp(client);
            CsvDataLoader loader = new CsvDataLoader();
            List<Long> accounts = loader.loadAccountData(app, "data/accounts.csv");
            List<Integer> merchants = loader.loadMerchantData(app, "data/merchants.csv");
            LOG.info("Reference data ready: {} accounts, {} merchants",
                    accounts.size(), merchants.size());

            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();

            Random rng = new Random(opts.seed);
            long now = System.currentTimeMillis();

            switch (opts.mode) {
                case MIXED:
                    seedAcceptedBatch(app, rng, now, opts.count, accepted);
                    seedVelocityRejection(app, now, rejected);
                    seedSpendRejection(app, now, rejected);
                    seedSubnetPageHitRateRejection(app, now, rejected);
                    seedSubnetTxnRateRejection(app, now, rejected);
                    break;
                case STEADY:
                    seedAcceptedBatch(app, rng, now, opts.count, accepted);
                    break;
                case BURST:
                    seedBurst(app, now, opts.count, accepted, rejected);
                    break;
            }

            LOG.info("Done. accepted={} rejected={}", accepted.get(), rejected.get());
        } finally {
            client.close();
        }
    }

    /** N clean transactions, spread across accounts + merchants + public IPs. */
    private static void seedAcceptedBatch(ThreatDetectionApp app, Random rng, long baseTime,
                                          int count, AtomicInteger acc) throws Exception {
        long[] accounts = {1L, 2L, 3L, 4L, 5L};
        long txnIdBase = System.nanoTime() & 0x7FFFFFFFL; // per-run offset to avoid PK collisions across reruns
        for (int i = 0; i < count; i++) {
            long accountId = accounts[rng.nextInt(accounts.length)];
            int merchantId = 1 + rng.nextInt(5);
            String ip = PUBLIC_IPS[i % PUBLIC_IPS.length];
            double amount = 25.0 + rng.nextInt(200);
            long txnTimeMs = baseTime + i * 100L;
            long txnId = txnIdBase + i;
            VoltTable r = app.processRequest(accountId, txnId, txnTimeMs, merchantId,
                    amount, "device-seed-" + i, ip);
            r.advanceRow();
            if (r.getLong("ACCEPTED") == 1) acc.incrementAndGet();
        }
    }

    /** 6 txns for one account within 30s → the 6th trips VELOCITY_BURST. */
    private static void seedVelocityRejection(ThreatDetectionApp app, long baseTime,
                                              AtomicInteger rej) throws Exception {
        long t = baseTime + 40_000;
        long base = System.nanoTime() & 0x7FFFFFFFL;
        // Hetzner Nuremberg range — real geolocated unicast, unlike 1.0.0.x anycast.
        for (int i = 0; i < 5; i++) {
            app.processTransaction(2L, base + i, t + i, 1, 50.0, "dev-vel",
                    "85.10.192." + (1 + i), 0, 0);
        }
        countRejection(
                app.processTransaction(2L, base + 5, t + 5, 1, 50.0, "dev-vel",
                        "85.10.192.6", 0, 0), rej);
    }

    /** 2 txns totalling >$5,000 for one account within 1 min → HIGH_SPEND. */
    private static void seedSpendRejection(ThreatDetectionApp app, long baseTime,
                                           AtomicInteger rej) throws Exception {
        long t = baseTime + 50_000;
        long base = System.nanoTime() & 0x7FFFFFFFL;
        app.processTransaction(3L, base,     t,     2, 4900.0, "dev-spend", "8.8.8.8", 0, 0);
        countRejection(
                app.processTransaction(3L, base + 1, t + 10, 2, 200.0, "dev-spend", "8.8.8.8", 0, 0), rej);
    }

    /**
     * Warm one subnet with 501+ PAGE requests, then a txn from that subnet →
     * SUBNET_PAGE_HIT_RATE. Uses the same subnet (78.46.220.0/24) as
     * {@link #seedSubnetTxnRateRejection}; the two scenarios are spaced far
     * enough apart in time (20s vs 55s) that the 5-second rate window has
     * fully decayed between them, so each rule fires cleanly in isolation.
     */
    private static void seedSubnetPageHitRateRejection(ThreatDetectionApp app, long baseTime,
                                                       AtomicInteger rej) throws Exception {
        long t = baseTime + 20_000;
        long base = System.nanoTime() & 0x7FFFFFFFL;
        String attackerIp = "78.46.220.42"; // Hetzner — Germany (real geo, useful for maps)
        String subnet = CidrUtils.extractSubnet(attackerIp, 24);
        // SUBNET_PAGE_HIT_RATE threshold is 500/5s. Insert 501 PAGE requests in the same
        // window so the subsequent txn's rule check sees pageCount > 500.
        for (int i = 0; i < 501; i++) {
            app.recordPageHit(subnet, base + i, "78.46.220." + (i % 254 + 1),
                    "/products/" + i, t);
        }
        ThreatDetectionApp.SubnetRates rates =
                app.recordSubnetRequest(subnet, base + 501, attackerIp, t);
        countRejection(
                app.processTransaction(4L, base + 501, t, 1, 125.0, "dev-pageattacker",
                        attackerIp, rates.pageCount, rates.txnCount), rej);
    }

    /** Warm one subnet with 21+ TXN requests, then a txn from that subnet → SUBNET_TXN_RATE. */
    private static void seedSubnetTxnRateRejection(ThreatDetectionApp app, long baseTime,
                                                   AtomicInteger rej) throws Exception {
        long t = baseTime + 55_000;
        long base = System.nanoTime() & 0x7FFFFFFFL;
        String attackerIp = "78.46.220.1"; // Hetzner — Germany (real geo, useful for maps)
        String subnet = CidrUtils.extractSubnet(attackerIp, 24);
        // SUBNET_TXN_RATE threshold is 20/5s. Insert 21 TXN requests in the same
        // window so the 22nd attempt's rule check sees count > 20.
        for (int i = 0; i < 21; i++) {
            app.recordSubnetRequest(subnet, base + i, "78.46.220." + (i % 254 + 1), t);
        }
        ThreatDetectionApp.SubnetRates rates = app.recordSubnetRequest(subnet, base + 21, attackerIp, t);
        countRejection(
                app.processTransaction(5L, base + 21, t, 1, 75.0, "dev-attacker", attackerIp,
                        rates.pageCount, rates.txnCount), rej);
    }

    /** N txns for one account with tight timestamps, then one that trips VELOCITY_BURST. */
    private static void seedBurst(ThreatDetectionApp app, long baseTime, int count,
                                  AtomicInteger acc, AtomicInteger rej) throws Exception {
        long base = System.nanoTime() & 0x7FFFFFFFL;
        for (int i = 0; i < Math.min(count, 5); i++) {
            VoltTable r = app.processTransaction(2L, base + i, baseTime + i, 1, 50.0,
                    "dev-burst", "8.8.8.8", 0, 0);
            r.advanceRow();
            if (r.getLong("ACCEPTED") == 1) acc.incrementAndGet();
        }
        countRejection(
                app.processTransaction(2L, base + 6, baseTime + 6, 1, 50.0,
                        "dev-burst", "8.8.8.8", 0, 0), rej);
    }

    private static void countRejection(VoltTable result, AtomicInteger rej) {
        result.advanceRow();
        if (result.getLong("ACCEPTED") == 0) {
            rej.incrementAndGet();
        }
    }

    enum Mode { MIXED, STEADY, BURST }

    static final class Args {
        String host = "localhost";
        int port = 21212;
        Mode mode = Mode.MIXED;
        int count = 40;
        long seed = System.currentTimeMillis();

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
                    case "host":  a.host = value; break;
                    case "port":  a.port = Integer.parseInt(value); break;
                    case "mode":  a.mode = Mode.valueOf(value.toUpperCase(Locale.ROOT)); break;
                    case "count": a.count = Integer.parseInt(value); break;
                    case "seed":  a.seed = Long.parseLong(value); break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: --" + key);
                }
            }
            if (a.count <= 0) {
                throw new IllegalArgumentException("--count must be > 0");
            }
            return a;
        }
    }
}
