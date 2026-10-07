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

package com.voltactivedata.example.threat.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

import java.math.BigDecimal;

/**
 * Multi-step atomic procedure: Process a financial transaction for threat detection.
 * Partitioned on ACCOUNT_ID — all transactions for the same account are on the same partition.
 *
 * Combines fraud detection (per-account velocity rules) with subnet-based threat detection.
 * The subnet rate counts are passed in from a prior call to RecordSubnetRequest
 * (which is partition-local on SUBNET), so this SP stays single-partition on ACCOUNT_ID.
 *
 * Steps (all execute as a single ACID transaction):
 * 1. Insert the transaction record (initially as accepted) so the per-account
 *    views include this row when the rule checks read them.
 * 2. Evaluate four independent rules — any firing rejects the transaction:
 *    - HIGH_SPEND            (>$5,000 spent in 1 minute)
 *    - VELOCITY_BURST        (>5 transactions in 30 seconds)
 *    - SUBNET_PAGE_HIT_RATE  (>500 page hits per 5s from the same /24 subnet)
 *    - SUBNET_TXN_RATE       (>20 transaction requests per 5s from the same /24 subnet)
 * 3. If rejected: UPDATE the row to ACCEPTED=0 with REASON + RULE_NAME.
 * 4. If accepted: UPDATE account balance by the transaction amount.
 */
public class ProcessTransaction extends VoltProcedure {

    // Insert transaction
    public final SQLStmt insertTxn = new SQLStmt(
        "INSERT INTO TRANSACTIONS (TXN_ID, ACCOUNT_ID, TXN_TIME, MERCHANT_ID, AMOUNT, " +
        "DEVICE_ID, SOURCE_IP, ACCEPTED, REASON, RULE_NAME) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");

    // Per-account rule checks via materialized views.
    // Filter by the window that contains the current transaction's timestamp.
    // TIME_WINDOW(SECOND, 30, ?) computes which 30-second bucket the timestamp falls in,
    // matching exactly one row in the view — the window we just inserted into.
    // This avoids reading stale windows from prior bursts.
    public final SQLStmt checkTxn1Min = new SQLStmt(
        "SELECT TXN_COUNT, TOTAL_SPENT FROM TXN_SUMMARY_1MIN " +
        "WHERE ACCOUNT_ID = ? AND WINDOW_1MIN = TIME_WINDOW(SECOND, 60, ?);");

    public final SQLStmt checkTxn30Sec = new SQLStmt(
        "SELECT TXN_COUNT, TOTAL_SPENT FROM TXN_SUMMARY_30SEC " +
        "WHERE ACCOUNT_ID = ? AND WINDOW_30SEC = TIME_WINDOW(SECOND, 30, ?);");

    // Mark transaction as rejected
    public final SQLStmt rejectTxn = new SQLStmt(
        "UPDATE TRANSACTIONS SET ACCEPTED = 0, REASON = ?, RULE_NAME = ? " +
        "WHERE TXN_ID = ? AND ACCOUNT_ID = ?;");

    // Update balance on accepted transaction
    public final SQLStmt updateBalance = new SQLStmt(
        "UPDATE ACCOUNTS SET BALANCE = BALANCE + ? WHERE ACCOUNT_ID = ?;");

    // Subnet rate thresholds — tracked independently per source type because
    // page hits (passive, high-volume) and transactions (deliberate, low-volume)
    // have very different natural baselines. ANY rule firing rejects the txn.
    private static final int MAX_SUBNET_PAGE_RATE = 500; // page hits per 5s window
    private static final int MAX_SUBNET_TXN_RATE  = 20;  // txn requests per 5s window

    private static final String ACCEPTED_REASON = "Accepted";

    private VoltTable buildResult(byte accepted, String reason, String ruleName) {
        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("ACCEPTED", VoltType.TINYINT),
            new VoltTable.ColumnInfo("REASON", VoltType.STRING),
            new VoltTable.ColumnInfo("RULE_NAME", VoltType.STRING)
        );
        result.addRow(accepted, reason, ruleName);
        return result;
    }

    /**
     * @param accountId         partition key
     * @param txnId             unique transaction ID
     * @param txnTimeMs         transaction time in epoch milliseconds
     * @param merchantId        merchant identifier
     * @param amount            transaction amount
     * @param deviceId          device identifier
     * @param sourceIp          IP address of the requestor
     * @param subnetPageCount   page-hit count in the source IP's /24 subnet in the
     *                          current 5s window (from a prior RecordSubnetRequest call)
     * @param subnetTxnCount    transaction-request count in the source IP's /24 subnet
     *                          in the current 5s window (from a prior RecordSubnetRequest call)
     */
    public VoltTable run(long accountId, long txnId, long txnTimeMs, int merchantId,
                         double amount, String deviceId, String sourceIp,
                         long subnetPageCount, long subnetTxnCount) {

        TimestampType txnTime = new TimestampType(txnTimeMs * 1000);

        // Insert the transaction first (initially as accepted) so the per-account
        // views include this row when the rule checks below read them.
        voltQueueSQL(insertTxn, txnId, accountId, txnTime, merchantId, amount,
                     deviceId, sourceIp, (byte) 1, ACCEPTED_REASON, "NONE");
        voltExecuteSQL();

        // Evaluate all threat-detection rules against the just-updated views.
        String blockReason = null;
        String ruleName = null;

        // Rule: TXN_SUMMARY_1MIN — reject if >$5,000 spent in 1 minute
        voltQueueSQL(checkTxn1Min, accountId, txnTime);
        // Rule: TXN_SUMMARY_30SEC — reject if >5 txns in 30 seconds
        voltQueueSQL(checkTxn30Sec, accountId, txnTime);
        VoltTable[] fraudChecks = voltExecuteSQL();

        if (fraudChecks[0].advanceRow()) {
            BigDecimal totalSpent = fraudChecks[0].getDecimalAsBigDecimal(1);
            long spent = totalSpent != null ? totalSpent.longValue() : 0;
            if (spent > 5000) {
                blockReason = String.format(
                    "High Spending in 1 Minute (>$5,000): total $%d for account %d",
                    spent, accountId);
                ruleName = "HIGH_SPEND";
            }
        }

        if (blockReason == null && fraudChecks[1].advanceRow()) {
            long txnCount = fraudChecks[1].getLong(0);
            if (txnCount > 5) {
                blockReason = String.format(
                    "Too Many Transactions in 30 Seconds (>5): count %d for account %d",
                    txnCount, accountId);
                ruleName = "VELOCITY_BURST";
            }
        }

        // Rule: SUBNET_PAGE_HIT_RATE — bot scan (passive high-volume signal)
        if (blockReason == null && subnetPageCount > MAX_SUBNET_PAGE_RATE) {
            blockReason = String.format(
                "Too Many Page Hits from Subnet (>%d/5s): count %d from IP %s",
                MAX_SUBNET_PAGE_RATE, subnetPageCount, sourceIp);
            ruleName = "SUBNET_PAGE_HIT_RATE";
        }

        // Rule: SUBNET_TXN_RATE — credential-stuffing-style txn flood (deliberate signal)
        if (blockReason == null && subnetTxnCount > MAX_SUBNET_TXN_RATE) {
            blockReason = String.format(
                "Too Many Transactions from Subnet (>%d/5s): count %d from IP %s",
                MAX_SUBNET_TXN_RATE, subnetTxnCount, sourceIp);
            ruleName = "SUBNET_TXN_RATE";
        }

        // ==========================================
        // Phase 4: Record result and commit
        // ==========================================
        if (blockReason != null) {
            voltQueueSQL(rejectTxn, blockReason, ruleName, txnId, accountId);
            voltExecuteSQL(true);
            return buildResult((byte) 0, blockReason, ruleName);
        }

        // Accepted — update balance
        voltQueueSQL(updateBalance, amount, accountId);
        voltExecuteSQL(true);
        return buildResult((byte) 1, ACCEPTED_REASON, "NONE");
    }
}