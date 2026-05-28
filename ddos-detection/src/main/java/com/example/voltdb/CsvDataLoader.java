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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class CsvDataLoader {

    /**
     * Load domain configuration from CSV.
     * CSV columns: DOMAIN,OWNER,MAX_REQUESTS_PER_IP
     */
    public List<String> loadDomainsData(DdosDetectionApp app, String resourcePath)
            throws Exception {
        List<String> domains = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getResource(resourcePath)))) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] fields = parseCsvLine(line);
                String domain = fields[0].trim();
                String owner = fields[1].trim();
                int maxRequests = Integer.parseInt(fields[2].trim());
                app.upsertDomain(domain, owner, maxRequests);
                domains.add(domain);
                System.out.printf("Loaded domain: %s (owner=%s, max=%d)%n", domain, owner, maxRequests);
            }
        }
        System.out.printf("Total domains loaded: %d%n", domains.size());
        return domains;
    }

    /**
     * Load requests by calling ProcessRequest for each row.
     * CSV columns: REQUEST_ID,SOURCE_IP,DOMAIN,REQUEST_TIME_MS
     * Returns list of request IDs.
     */
    public List<Long> loadRequestsData(DdosDetectionApp app, String resourcePath)
            throws Exception {
        List<Long> ids = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getResource(resourcePath)))) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] fields = parseCsvLine(line);
                long requestId = Long.parseLong(fields[0].trim());
                String sourceIp = fields[1].trim();
                String domain = fields[2].trim();
                long requestTimeMs = Long.parseLong(fields[3].trim());
                app.processRequest(sourceIp, requestId, domain, requestTimeMs);
                ids.add(requestId);
                System.out.printf("Processed request: id=%d, ip=%s, domain=%s%n",
                    requestId, sourceIp, domain);
            }
        }
        System.out.printf("Total requests processed: %d%n", ids.size());
        return ids;
    }

    public String[] parseCsvLine(String line) {
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
            throw new RuntimeException("Resource not found: " + resourcePath);
        }
        return is;
    }
}
