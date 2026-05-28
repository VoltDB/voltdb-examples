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

package com.example.voltdb.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

/**
 * Multi-step atomic procedure: Process an incoming web request for DDoS detection.
 * Partitioned on SOURCE_IP — all requests from the same IP are on the same partition.
 *
 * Steps (all execute as a single ACID transaction):
 * 1. Check if IP is already blocked (early exit)
 * 2. Read domain config from replicated DOMAINS table
 * 3. Insert the request record
 * 4. Check Rule 1: >threshold requests from same IP to same domain in 1 sec
 * 5. Check Rule 2: >1000 requests from same IP to any domain in 1 sec
 * 6. If either triggers: insert BLOCKED_IPS record, return BLOCKED
 * 7. Otherwise: return ALLOWED
 */
public class ProcessRequest extends VoltProcedure {

    // Phase 1: Check if already blocked
    public final SQLStmt checkBlocked = new SQLStmt(
        "SELECT SOURCE_IP, RULE_NAME FROM BLOCKED_IPS WHERE SOURCE_IP = ? ORDER BY BLOCKED_TIME DESC LIMIT 1;");

    // Phase 1b: Read domain config (replicated table)
    public final SQLStmt getDomainConfig = new SQLStmt(
        "SELECT MAX_REQUESTS_PER_IP FROM DOMAINS WHERE DOMAIN = ?;");

    // Phase 2: Insert the request
    public final SQLStmt insertRequest = new SQLStmt(
        "INSERT INTO REQUESTS (REQUEST_ID, SOURCE_IP, DOMAIN, REQUEST_TIME, BLOCKED) " +
        "VALUES (?, ?, ?, ?, ?);");

    // Phase 3: Check Rule 1 — per-IP per-domain count via materialized view
    public final SQLStmt checkRule1 = new SQLStmt(
        "SELECT REQUEST_COUNT FROM REQUESTS_PER_IP_DOMAIN_1SEC " +
        "WHERE SOURCE_IP = ? AND DOMAIN = ?;");

    // Phase 3: Check Rule 2 — per-IP total count via materialized view
    public final SQLStmt checkRule2 = new SQLStmt(
        "SELECT REQUEST_COUNT FROM REQUESTS_PER_IP_1SEC WHERE SOURCE_IP = ?;");

    // Phase 4: Block the IP
    public final SQLStmt insertBlock = new SQLStmt(
        "INSERT INTO BLOCKED_IPS (SOURCE_IP, DOMAIN, BLOCKED_TIME, REASON, RULE_NAME) " +
        "VALUES (?, ?, ?, ?, ?);");

    private static final int DEFAULT_MAX_PER_DOMAIN = 100;
    private static final int MAX_TOTAL_REQUESTS = 1000;

    private VoltTable buildResult(byte blocked, String reason, String ruleName) {
        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("BLOCKED", VoltType.TINYINT),
            new VoltTable.ColumnInfo("REASON", VoltType.STRING),
            new VoltTable.ColumnInfo("RULE_NAME", VoltType.STRING)
        );
        result.addRow(blocked, reason, ruleName);
        return result;
    }

    // source_ip is param 0 (partition key)
    public VoltTable run(String sourceIp, long requestId, String domain, long requestTimeMs) {

        TimestampType requestTime = new TimestampType(requestTimeMs * 1000);

        // ==========================================
        // Phase 1: Check if IP is already blocked
        // ==========================================
        voltQueueSQL(checkBlocked, EXPECT_ZERO_OR_ONE_ROW, sourceIp);
        voltQueueSQL(getDomainConfig, EXPECT_ZERO_OR_ONE_ROW, domain);
        VoltTable[] phase1 = voltExecuteSQL();

        VoltTable blockedInfo = phase1[0];
        if (blockedInfo.advanceRow()) {
            String existingRule = blockedInfo.getString("RULE_NAME");
            // Still insert the request as blocked for tracking
            voltQueueSQL(insertRequest, requestId, sourceIp, domain, requestTime, (byte) 1);
            voltExecuteSQL(true);
            return buildResult((byte) 1, "IP already blocked by " + existingRule, existingRule);
        }

        // Read domain-specific threshold (or use default)
        VoltTable domainInfo = phase1[1];
        int maxPerDomain = DEFAULT_MAX_PER_DOMAIN;
        if (domainInfo.advanceRow()) {
            maxPerDomain = (int) domainInfo.getLong(0);
        }

        // ==========================================
        // Phase 2: Insert the request (initially as allowed)
        // ==========================================
        voltQueueSQL(insertRequest, requestId, sourceIp, domain, requestTime, (byte) 0);
        voltExecuteSQL();

        // ==========================================
        // Phase 3: Check DDoS rules via materialized views
        // ==========================================
        voltQueueSQL(checkRule1, sourceIp, domain);
        voltQueueSQL(checkRule2, sourceIp);
        VoltTable[] ruleChecks = voltExecuteSQL();

        String blockReason = null;
        String ruleName = null;

        // Rule 1: per-IP per-domain threshold
        if (ruleChecks[0].advanceRow()) {
            long perDomainCount = ruleChecks[0].getLong(0);
            if (perDomainCount > maxPerDomain) {
                blockReason = String.format(
                    ">%d requests from %s to %s in 1 sec (count: %d)",
                    maxPerDomain, sourceIp, domain, perDomainCount);
                ruleName = "RULE1_PER_DOMAIN";
            }
        }

        // Rule 2: per-IP total threshold (check even if Rule 1 triggered — log both)
        if (blockReason == null && ruleChecks[1].advanceRow()) {
            long totalCount = ruleChecks[1].getLong(0);
            if (totalCount > MAX_TOTAL_REQUESTS) {
                blockReason = String.format(
                    ">%d total requests from %s in 1 sec (count: %d)",
                    MAX_TOTAL_REQUESTS, sourceIp, totalCount);
                ruleName = "RULE2_TOTAL";
            }
        }

        // ==========================================
        // Phase 4: Block if rule triggered, then commit
        // ==========================================
        if (blockReason != null) {
            voltQueueSQL(insertBlock, sourceIp, domain, requestTime, blockReason, ruleName);
            voltExecuteSQL(true);
            return buildResult((byte) 1, blockReason, ruleName);
        }

        // No rules triggered — commit the allowed request
        voltExecuteSQL(true);
        return buildResult((byte) 0, "ALLOWED", "NONE");
    }
}
