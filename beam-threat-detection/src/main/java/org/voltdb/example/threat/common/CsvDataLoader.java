/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.common;

import org.voltdb.example.threat.app.ThreatDetectionApp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads reference data (ACCOUNTS, MERCHANTS) into VoltDB from CSV files on the
 * classpath. Uses {@link ThreatDetectionApp}'s UPSERT methods so the operation
 * is idempotent — safe to re-run against a live cluster.
 */
public final class CsvDataLoader {

    /**
     * CSV columns: {@code account_id,balance,daily_limit,name,email}.
     */
    public List<Long> loadAccountData(ThreatDetectionApp app, String resourcePath)
            throws Exception {
        List<Long> accountIds = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getResource(resourcePath), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] fields = parseCsvLine(line);
                long accountId = Long.parseLong(fields[0].trim());
                double balance = Double.parseDouble(fields[1].trim());
                double dailyLimit = Double.parseDouble(fields[2].trim());
                String name = fields[3].trim();
                String email = fields[4].trim();
                app.upsertAccount(accountId, balance, dailyLimit, name, email);
                accountIds.add(accountId);
            }
        }
        return accountIds;
    }

    /**
     * CSV columns: {@code merchant_id,name,category}.
     */
    public List<Integer> loadMerchantData(ThreatDetectionApp app, String resourcePath)
            throws Exception {
        List<Integer> merchantIds = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getResource(resourcePath), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] fields = parseCsvLine(line);
                int merchantId = Integer.parseInt(fields[0].trim());
                String name = fields[1].trim();
                String category = fields[2].trim();
                app.upsertMerchant(merchantId, name, category);
                merchantIds.add(merchantId);
            }
        }
        return merchantIds;
    }

    /** Splits a CSV line honoring double-quote escaping. */
    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private InputStream getResource(String resourcePath) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new RuntimeException("Resource not found on classpath: " + resourcePath);
        }
        return is;
    }
}
