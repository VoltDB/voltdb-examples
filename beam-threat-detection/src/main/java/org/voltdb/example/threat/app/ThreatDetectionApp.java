/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.app;

import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

import org.voltdb.example.threat.common.CidrUtils;
import org.voltdb.example.threat.common.CsvDataLoader;

import java.util.concurrent.CompletableFuture;

/**
 * Transaction real-time path.
 *
 * <p>Wraps a {@link Client2} and exposes the two-step threat-detection flow
 * used by the demo's user-facing service: extract the /24 CIDR subnet from the
 * source IP, {@code RecordSubnetRequest} to feed the shared counter (with
 * {@code SOURCE_TYPE='TXN'} and {@code PAGE_URL=null}), then
 * {@code ProcessTransaction} passing the returned count so all rules
 * (per-account velocity + cross-subnet rate) evaluate atomically.
 *
 * <p>The subnet counter is shared with the page-hit ingest pipeline
 * ({@code PageHitsIngestPipeline}), which writes {@code SOURCE_TYPE='PAGE'}
 * rows via {@code VoltDbIO.write}. Because both callers use the same
 * {@link CidrUtils#extractSubnet(String, int)} with the same prefix length
 * (default 24), their rows share {@code SUBNET_REQUESTS.SUBNET} keys and the
 * {@code REQUESTS_PER_SUBNET} view aggregates across sources.
 *
 * <p>Uses {@code Client2} directly rather than the Beam connector because this
 * path needs the SP result values ({@code ACCEPTED}, {@code REASON},
 * {@code RULE_NAME}) inline to drive the accept/reject response.
 * {@code VoltDbIO.write}'s {@code PDone} output can't surface per-element SP
 * results back to the caller.
 */
public final class ThreatDetectionApp {

    public static final int DEFAULT_CIDR_PREFIX = 24;

    private final Client2 client;
    private final int cidrPrefix;

    public ThreatDetectionApp(Client2 client) {
        this(client, DEFAULT_CIDR_PREFIX);
    }

    public ThreatDetectionApp(Client2 client, int cidrPrefix) {
        if (cidrPrefix < 0 || cidrPrefix > 32) {
            throw new IllegalArgumentException("CIDR prefix must be 0-32, got: " + cidrPrefix);
        }
        this.client = client;
        this.cidrPrefix = cidrPrefix;
    }

    public int getCidrPrefix() {
        return cidrPrefix;
    }

    // ========================================
    // Core: two-step threat-detection flow
    // ========================================

    /**
     * Per-subnet rate counts for the current 5-second window, returned from
     * {@link #recordSubnetRequest}. Both counts are needed by
     * {@link #processTransaction}, which evaluates two independent rate rules
     * ({@code SUBNET_PAGE_HIT_RATE}, {@code SUBNET_TXN_RATE}).
     */
    public static final class SubnetRates {
        public final long pageCount;
        public final long txnCount;
        public SubnetRates(long pageCount, long txnCount) {
            this.pageCount = pageCount;
            this.txnCount  = txnCount;
        }
    }

    /**
     * Record a transaction-driven subnet request and return both the page-hit
     * count and the transaction-request count for the subnet's current 5s
     * window. Called before {@link #processTransaction} so the subsequent
     * rule checks see fresh counts without a second round-trip.
     */
    public SubnetRates recordSubnetRequest(String subnet, long requestId, String sourceIp,
                                           long requestTimeMs) throws Exception {
        VoltTable result = call("RecordSubnetRequest",
                subnet, requestId, sourceIp, requestTimeMs, "TXN", null)
                .getResults()[0];
        result.advanceRow();
        return new SubnetRates(
                result.getLong("PAGE_COUNT"),
                result.getLong("TXN_COUNT"));
    }

    /**
     * Record a page-hit subnet request (SOURCE_TYPE='PAGE'). Called by the
     * streaming ingest pipeline in production; exposed here so demo generators
     * can warm the page-hit counter deterministically to construct scenarios
     * where {@code SUBNET_PAGE_HIT_RATE} fires. Returns the same
     * {@link SubnetRates} shape as {@link #recordSubnetRequest} for callers
     * that chain into {@link #processTransaction}.
     */
    public SubnetRates recordPageHit(String subnet, long requestId, String sourceIp,
                                     String pageUrl, long requestTimeMs) throws Exception {
        VoltTable result = call("RecordSubnetRequest",
                subnet, requestId, sourceIp, requestTimeMs, "PAGE", pageUrl)
                .getResults()[0];
        result.advanceRow();
        return new SubnetRates(
                result.getLong("PAGE_COUNT"),
                result.getLong("TXN_COUNT"));
    }

    /**
     * Evaluate a transaction. Caller supplies both subnet rate counts from
     * the prior {@link #recordSubnetRequest} call. Returns a one-row table
     * with columns {@code ACCEPTED} (tinyint), {@code REASON} (string), and
     * {@code RULE_NAME} (string).
     */
    public VoltTable processTransaction(long accountId, long txnId, long txnTimeMs,
                                        int merchantId, double amount, String deviceId,
                                        String sourceIp,
                                        long subnetPageCount, long subnetTxnCount)
            throws Exception {
        return call("ProcessTransaction",
                accountId, txnId, txnTimeMs, merchantId, amount, deviceId, sourceIp,
                subnetPageCount, subnetTxnCount)
                .getResults()[0];
    }

    /**
     * Convenience: computes subnet from the source IP, records the request,
     * then evaluates the transaction. This is the single-call entry point the
     * demo driver uses.
     */
    public VoltTable processRequest(long accountId, long txnId, long txnTimeMs,
                                    int merchantId, double amount, String deviceId,
                                    String sourceIp) throws Exception {
        String subnet = CidrUtils.extractSubnet(sourceIp, cidrPrefix);
        SubnetRates rates = recordSubnetRequest(subnet, txnId, sourceIp, txnTimeMs);
        return processTransaction(accountId, txnId, txnTimeMs, merchantId, amount,
                deviceId, sourceIp, rates.pageCount, rates.txnCount);
    }

    // ========================================
    // Reference-data CRUD (idempotent UPSERTs)
    // ========================================

    public void upsertAccount(long accountId, double balance, double dailyLimit,
                              String name, String email) throws Exception {
        call("UpsertAccount", accountId, balance, dailyLimit, name, email);
    }

    public void upsertMerchant(int merchantId, String name, String category) throws Exception {
        call("UpsertMerchant", merchantId, name, category);
    }

    public VoltTable getAccount(long accountId) throws Exception {
        return call("GetAccount", accountId).getResults()[0];
    }

    public VoltTable getTransactionsByAccount(long accountId) throws Exception {
        return call("GetTransactionsByAccount", accountId).getResults()[0];
    }

    public VoltTable searchBlockedByRule(String ruleName) throws Exception {
        return call("SearchBlockedByRule", ruleName).getResults()[0];
    }

    public VoltTable searchBlockedByIp(String sourceIp) throws Exception {
        return call("SearchBlockedByIp", sourceIp).getResults()[0];
    }

    public void deleteAllData() throws Exception {
        client.callProcedureAsync("@AdHoc", "DELETE FROM TRANSACTIONS;")
                .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM SUBNET_REQUESTS;"))
                .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM ACCOUNTS;"))
                .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM MERCHANTS;"))
                .get();
    }

    // ========================================
    // Internals
    // ========================================

    private ClientResponse call(String proc, Object... params) throws Exception {
        CompletableFuture<ClientResponse> f = client.callProcedureAsync(proc, params);
        ClientResponse response = f.get();
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException(proc + " failed: " + response.getStatusString());
        }
        return response;
    }

    // ========================================
    // CLI driver
    // ========================================

    /**
     * Small demo script: connects to VoltDB, loads reference data, and runs a
     * few sample transactions covering the accept path plus one subnet-rate
     * rejection. Intended for hand-verification against a running cluster
     * (e.g. via {@code kubectl port-forward}), not for CI.
     *
     * <p>CLI: {@code --host=HOST --port=PORT} (defaults localhost:21212).
     */
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 21212;
        for (String arg : args) {
            if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else {
                throw new IllegalArgumentException("Unknown arg: " + arg
                        + " (expected --host=... --port=...)");
            }
        }

        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(host, port);
        System.out.println("Connected to VoltDB at " + host + ":" + port);

        ThreatDetectionApp app = new ThreatDetectionApp(client);
        CsvDataLoader loader = new CsvDataLoader();

        System.out.println("Loading reference data...");
        loader.loadAccountData(app, "data/accounts.csv");
        loader.loadMerchantData(app, "data/merchants.csv");

        long now = System.currentTimeMillis();

        System.out.println("\n--- Accepted transaction ---");
        VoltTable r = app.processRequest(1, 1001, now, 1, 200.0, "device-abc", "192.168.1.45");
        printResult(r);

        System.out.println("\n--- Warming subnet 10.50.50.0/24 to trip SUBNET_RATE ---");
        String warmSubnet = CidrUtils.extractSubnet("10.50.50.1", 24);
        for (int i = 0; i < 101; i++) {
            app.recordSubnetRequest(warmSubnet, 4000 + i,
                    "10.50.50." + (i % 254 + 1), now);
        }
        System.out.println("Wrote 101 subnet requests. Next transaction from that subnet should be rejected.");

        System.out.println("\n--- Transaction from primed subnet (expect SUBNET_RATE rejection) ---");
        r = app.processRequest(2, 4200, now, 1, 50.0, "device-subnet", "10.50.50.99");
        printResult(r);

        System.out.println("\nDone. Rows in TRANSACTIONS:");
        VoltTable txns = app.getTransactionsByAccount(2);
        System.out.println(txns.toFormattedString());

        client.close();
    }

    private static void printResult(VoltTable table) {
        table.advanceRow();
        System.out.printf("  ACCEPTED=%d  RULE=%s  REASON=%s%n",
                table.getLong("ACCEPTED"),
                table.getString("RULE_NAME"),
                table.getString("REASON"));
    }
}
