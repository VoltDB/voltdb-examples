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

import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fraud Detection VoltDB client application.
 * Demonstrates high-throughput async transaction processing with real-time fraud checks.
 */
public class FraudDetectionApp {

    private final Client2 client;

    public FraudDetectionApp(Client2 client) {
        this.client = client;
    }

    // ========================================
    // Account Operations
    // ========================================

    public void upsertAccount(long accountId, byte enabled, double balance,
                              double dailyLimit, String name, String email) throws Exception {
        client.callProcedureAsync("UpsertAccount", accountId, enabled, balance,
                                  dailyLimit, name, email)
            .thenApply(response -> checkResponse("UpsertAccount", response))
            .get();
    }

    public VoltTable getAccount(long accountId) throws Exception {
        return client.callProcedureAsync("GetAccount", accountId)
            .thenApply(response -> checkResponse("GetAccount", response).getResults()[0])
            .get();
    }

    // ========================================
    // Merchant Operations
    // ========================================

    public void upsertMerchant(int merchantId, String name, String category) throws Exception {
        client.callProcedureAsync("UpsertMerchant", merchantId, name, category)
            .thenApply(response -> checkResponse("UpsertMerchant", response))
            .get();
    }

    // ========================================
    // Transaction Processing (Fraud Detection)
    // ========================================

    public CompletableFuture<ClientResponse> processTransactionAsync(
            long accountId, long txnId, long txnTimeMs, int merchantId,
            double amount, String deviceId) {
        return client.callProcedureAsync("ProcessTransaction",
            accountId, txnId, txnTimeMs, merchantId, amount, deviceId);
    }

    public VoltTable processTransaction(long accountId, long txnId, long txnTimeMs,
                                        int merchantId, double amount, String deviceId)
            throws Exception {
        return client.callProcedureAsync("ProcessTransaction",
            accountId, txnId, txnTimeMs, merchantId, amount, deviceId)
            .thenApply(response -> checkResponse("ProcessTransaction", response).getResults()[0])
            .get();
    }

    // ========================================
    // Cleanup
    // ========================================

    public void deleteAllData() throws Exception {
        client.callProcedureAsync("@AdHoc", "DELETE FROM TRANSACTIONS;")
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM MERCHANTS;"))
            .thenCompose(r -> client.callProcedureAsync("@AdHoc", "DELETE FROM ACCOUNTS;"))
            .get();
    }

    // ========================================
    // Benchmark
    // ========================================

    public void runBenchmark(int numAccounts, int numMerchants, int targetTPS,
                             int durationSeconds) throws Exception {
        msg("Initializing %d accounts and %d merchants...", numAccounts, numMerchants);
        for (int i = 1; i <= numAccounts; i++) {
            upsertAccount(i, (byte) 1, 0.0, 10000.0, "User" + i, "user" + i + "@example.com");
        }
        for (int i = 1; i <= numMerchants; i++) {
            upsertMerchant(i, "Merchant" + i, (i % 2 == 0) ? "Retail" : "Food");
        }
        msg("Initialization complete.");

        msg("Starting benchmark: target %d TPS for %d seconds...", targetTPS, durationSeconds);
        Random random = new Random(42);
        AtomicInteger total = new AtomicInteger(0);
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        long sleepNanos = 1_000_000_000L / targetTPS;
        long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        long lastReport = System.currentTimeMillis();

        while (System.currentTimeMillis() < endTime) {
            long txnId = random.nextLong() & Long.MAX_VALUE;
            long accountId = random.nextInt(numAccounts) + 1;
            int merchantId = random.nextInt(numMerchants) + 1;
            double amount = random.nextBoolean() ? 6000.0 : 400.0;
            String deviceId = "DEV" + random.nextInt(50);

            processTransactionAsync(accountId, txnId, System.currentTimeMillis(),
                                    merchantId, amount, deviceId)
                .whenComplete((response, ex) -> {
                    total.incrementAndGet();
                    if (ex != null) {
                        errors.incrementAndGet();
                        return;
                    }
                    if (response.getStatus() == ClientResponse.SUCCESS) {
                        VoltTable result = response.getResults()[0];
                        if (result.advanceRow() && result.getLong(0) == 1) {
                            accepted.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    } else {
                        errors.incrementAndGet();
                    }
                });

            long sleepMs = sleepNanos / 1_000_000;
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }

            // Periodic stats every 5 seconds
            if (System.currentTimeMillis() - lastReport >= 5000) {
                long elapsed = (System.currentTimeMillis() - (endTime - durationSeconds * 1000L)) / 1000;
                msg("[%ds] Total: %d | Accepted: %d | Rejected: %d | Errors: %d",
                    elapsed, total.get(), accepted.get(), rejected.get(), errors.get());
                lastReport = System.currentTimeMillis();
            }
        }

        // Wait for outstanding responses
        Thread.sleep(2000);

        float actualTPS = total.get() / (float) durationSeconds;
        msg("Benchmark complete. Total: %d | Accepted: %d | Rejected: %d | Errors: %d",
            total.get(), accepted.get(), rejected.get(), errors.get());
        msg("Target TPS: %d | Actual TPS: %.2f", targetTPS, actualTPS);
    }

    // ========================================
    // Internal
    // ========================================

    private static ClientResponse checkResponse(String procName, ClientResponse response) {
        if (response.getStatus() != ClientResponse.SUCCESS) {
            throw new RuntimeException(procName + " failed: " + response.getStatusString());
        }
        return response;
    }

    public static void printTable(String label, VoltTable table) {
        System.out.println("\n--- " + label + " ---");
        System.out.println(table.toFormattedString());
    }

    static void msg(String format, Object... args) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        System.out.printf("%s: %s%n", sdf.format(new Date()), String.format(format, args));
    }

    // ========================================
    // Main entry point
    // ========================================

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21211;
        int targetTPS = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        int duration = args.length > 3 ? Integer.parseInt(args[3]) : 30;

        Client2Config config = new Client2Config();
        Client2 client = ClientFactory.createClient(config);
        client.connectSync(host, port);
        msg("Connected to VoltDB at %s:%d", host, port);

        try {
            new VoltDBSetup(client).initSchemaIfNeeded();

            FraudDetectionApp app = new FraudDetectionApp(client);
            app.deleteAllData();
            app.runBenchmark(1000, 100, targetTPS, duration);
        } finally {
            client.close();
        }
    }
}
