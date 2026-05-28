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

import java.math.BigDecimal;

/**
 * Single-partition stored procedure for fraud detection.
 * Partitioned on ACCOUNT_ID.
 *
 * In a single atomic operation:
 * 1. Validates account exists and is enabled
 * 2. Validates merchant exists
 * 3. Checks 3 time-window materialized views for velocity/spend fraud rules
 * 4. Checks single-transaction amount limit and daily balance limit
 * 5. Inserts transaction and updates balance if accepted
 */
public class ProcessTransaction extends VoltProcedure {

    // Account check
    public final SQLStmt checkAccount = new SQLStmt(
        "SELECT ENABLED, CAST(BALANCE AS FLOAT), CAST(DAILY_LIMIT AS FLOAT), EMAIL " +
        "FROM ACCOUNTS WHERE ACCOUNT_ID = ?;");

    // Merchant check (replicated table — accessible from any partition)
    public final SQLStmt checkMerchant = new SQLStmt(
        "SELECT MERCHANT_ID FROM MERCHANTS WHERE MERCHANT_ID = ?;");

    // Fraud checks using time-window materialized views
    public final SQLStmt checkTxn5Min = new SQLStmt(
        "SELECT TXN_COUNT, TOTAL_SPENT FROM TXN_SUMMARY_5MIN WHERE ACCOUNT_ID = ?;");

    public final SQLStmt checkTxn1Min = new SQLStmt(
        "SELECT TXN_COUNT, TOTAL_SPENT FROM TXN_SUMMARY_1MIN WHERE ACCOUNT_ID = ?;");

    public final SQLStmt checkTxn30Sec = new SQLStmt(
        "SELECT TXN_COUNT, TOTAL_SPENT FROM TXN_SUMMARY_30SEC WHERE ACCOUNT_ID = ?;");

    // Transaction insert and balance update
    public final SQLStmt insertTxn = new SQLStmt(
        "INSERT INTO TRANSACTIONS (TXN_ID, ACCOUNT_ID, TXN_TIME, MERCHANT_ID, AMOUNT, " +
        "DEVICE_ID, ACCEPTED, REASON) VALUES (?, ?, ?, ?, ?, ?, ?, ?);");

    public final SQLStmt updateBalance = new SQLStmt(
        "UPDATE ACCOUNTS SET BALANCE = BALANCE + ? WHERE ACCOUNT_ID = ?;");

    public enum RejectReason {
        INVALID_ACCOUNT("Invalid Account"),
        ACCOUNT_DISABLED("Account Disabled"),
        LARGE_TRANSACTION("Large Transaction (> $5,000)"),
        EXCEEDS_DAILY_LIMIT("Exceeds Daily Limit"),
        TOO_MANY_TXNS_30SEC("Too Many Transactions in 30 Seconds (> 5)"),
        TOO_MANY_TXNS_OR_HIGH_SPEND_5MIN("Too Many Transactions or High Spending in 5 Minutes"),
        HIGH_SPENDING_1MIN("High Spending in 1 Minute (> $5,000)"),
        INVALID_MERCHANT("Invalid Merchant");

        public final String reason;
        RejectReason(String reason) { this.reason = reason; }
    }

    private static final String ACCEPTED_REASON = "Accepted";

    private VoltTable buildResult(byte accepted, String reason) {
        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("ACCEPTED", VoltType.TINYINT),
            new VoltTable.ColumnInfo("REASON", VoltType.STRING)
        );
        result.addRow(accepted, reason);
        return result;
    }

    // accountId is the partition key (PARAMETER 0 by default in DDL)
    public VoltTable run(long accountId, long txnId, long txnTimeMs, int merchantId,
                         double amount, String deviceId) {

        TimestampType txnTime = new TimestampType(getTransactionTime());

        // Phase 1: Validate account and merchant
        voltQueueSQL(checkAccount, EXPECT_ZERO_OR_ONE_ROW, accountId);
        voltQueueSQL(checkMerchant, EXPECT_ZERO_OR_ONE_ROW, merchantId);
        VoltTable[] initialChecks = voltExecuteSQL();

        VoltTable accountInfo = initialChecks[0];
        VoltTable merchantInfo = initialChecks[1];

        if (accountInfo.getRowCount() == 0) {
            return buildResult((byte) 0, RejectReason.INVALID_ACCOUNT.reason);
        }
        if (merchantInfo.getRowCount() == 0) {
            return buildResult((byte) 0, RejectReason.INVALID_MERCHANT.reason);
        }

        accountInfo.advanceRow();
        byte enabled = (byte) accountInfo.getLong(0);
        double balance = accountInfo.getDouble(1);
        double dailyLimit = accountInfo.getDouble(2);

        RejectReason rejectReason = null;

        if (enabled == 0) {
            rejectReason = RejectReason.ACCOUNT_DISABLED;
        } else if (amount > 5000) {
            rejectReason = RejectReason.LARGE_TRANSACTION;
        } else if (balance + amount > dailyLimit) {
            rejectReason = RejectReason.EXCEEDS_DAILY_LIMIT;
        } else {
            // Phase 2: Check time-window fraud rules
            rejectReason = checkFraudRules(accountId, amount);
        }

        byte accepted = (byte) (rejectReason == null ? 1 : 0);
        String reasonStr = rejectReason == null ? ACCEPTED_REASON : rejectReason.reason;

        if (rejectReason == null) {
            voltQueueSQL(updateBalance, amount, accountId);
        }

        // Phase 3: Record transaction and commit
        voltQueueSQL(insertTxn, txnId, accountId, txnTime, merchantId, amount,
                     deviceId, accepted, reasonStr);
        voltExecuteSQL(true);

        return buildResult(accepted, reasonStr);
    }

    private RejectReason checkFraudRules(long accountId, double amount) {
        voltQueueSQL(checkTxn5Min, accountId);
        voltQueueSQL(checkTxn1Min, accountId);
        voltQueueSQL(checkTxn30Sec, accountId);
        VoltTable[] fraudChecks = voltExecuteSQL();

        // 5-minute window: >10 txns or >$10,000 total
        if (fraudChecks[0].advanceRow()) {
            long txnCount = fraudChecks[0].getLong(0);
            BigDecimal totalSpent = fraudChecks[0].getDecimalAsBigDecimal(1);
            long spent = totalSpent != null ? totalSpent.longValue() : 0;
            if (txnCount > 10 || spent + amount > 10000) {
                return RejectReason.TOO_MANY_TXNS_OR_HIGH_SPEND_5MIN;
            }
        }

        // 1-minute window: >$5,000 total
        if (fraudChecks[1].advanceRow()) {
            BigDecimal totalSpent = fraudChecks[1].getDecimalAsBigDecimal(1);
            long spent = totalSpent != null ? totalSpent.longValue() : 0;
            if (spent + amount > 5000) {
                return RejectReason.HIGH_SPENDING_1MIN;
            }
        }

        // 30-second window: >5 txns
        if (fraudChecks[2].advanceRow()) {
            long txnCount = fraudChecks[2].getLong(0);
            if (txnCount > 5) {
                return RejectReason.TOO_MANY_TXNS_30SEC;
            }
        }

        return null; // No fraud detected
    }
}
