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

/**
 * Single-partition procedure to record a request from a /24 CIDR subnet
 * and return the current request count for that subnet.
 *
 * <p>Partitioned on SUBNET — all requests from the same /24 prefix hit the
 * same partition, so the count read and the insert happen atomically.
 *
 * <p>The subnet counter is fed by two callers: the transaction real-time path
 * (Java microservice, {@code SOURCE_TYPE='TXN'}) and the page-hit real-time
 * path (Beam streaming pipeline, {@code SOURCE_TYPE='PAGE'}). The counts are
 * tracked SEPARATELY — two materialized views, two rate rules, two thresholds
 * — because the two signals have very different natural baselines (page hits
 * are high-volume passive signal; transactions are low-volume deliberate
 * actions).
 *
 * <p>Returns BOTH counts (page + txn) for the request's current 5s window so
 * the caller can hand them to {@code ProcessTransaction} without needing a
 * second round-trip. This preserves partition locality — both counts are
 * read from this SP's SUBNET-partitioned views; {@code ProcessTransaction}
 * stays single-partition on ACCOUNT_ID.
 *
 * <p>{@code SOURCE_TYPE} is required — callers must supply it, there is no
 * server-side default. {@code PAGE_URL} is meaningful only for
 * {@code SOURCE_TYPE='PAGE'} rows and should be null otherwise.
 */
public class RecordSubnetRequest extends VoltProcedure {

    public final SQLStmt insertRequest = new SQLStmt(
        "INSERT INTO SUBNET_REQUESTS " +
        "(REQUEST_ID, SUBNET, SOURCE_IP, SOURCE_TYPE, PAGE_URL, REQUEST_TIME) " +
        "VALUES (?, ?, ?, ?, ?, ?);");

    // Filter by the specific 5s window the incoming request falls in. Without
    // this, the view may hold multiple windows for the same subnet and reading
    // the first row returns an arbitrary window's count.
    public final SQLStmt getPageCount = new SQLStmt(
        "SELECT PAGE_COUNT FROM PAGES_PER_SUBNET " +
        "WHERE SUBNET = ? AND WINDOW_5SEC = TIME_WINDOW(SECOND, 5, ?);");

    public final SQLStmt getTxnCount = new SQLStmt(
        "SELECT TXN_COUNT FROM TXNS_PER_SUBNET " +
        "WHERE SUBNET = ? AND WINDOW_5SEC = TIME_WINDOW(SECOND, 5, ?);");

    /**
     * @param subnet         the /24 subnet prefix (e.g., "192.168.1"); partition key
     * @param requestId      unique request identifier
     * @param sourceIp       full IP address
     * @param requestTimeMs  request time in epoch milliseconds
     * @param sourceType     {@code 'TXN'} or {@code 'PAGE'}; required
     * @param pageUrl        URL of the page hit for {@code 'PAGE'} rows; null for {@code 'TXN'}
     * @return VoltTable with one row: PAGE_COUNT (bigint), TXN_COUNT (bigint) —
     *         both counts in the request's current 5s window
     */
    public VoltTable run(String subnet, long requestId, String sourceIp, long requestTimeMs,
                         String sourceType, String pageUrl) {

        TimestampType requestTime = new TimestampType(requestTimeMs * 1000);

        voltQueueSQL(insertRequest, requestId, subnet, sourceIp, sourceType, pageUrl, requestTime);
        voltExecuteSQL();

        // Read both rate counts in a single batched round-trip.
        voltQueueSQL(getPageCount, subnet, requestTime);
        voltQueueSQL(getTxnCount,  subnet, requestTime);
        VoltTable[] results = voltExecuteSQL(true);

        long pageCount = results[0].advanceRow() ? results[0].getLong(0) : 0L;
        long txnCount  = results[1].advanceRow() ? results[1].getLong(0) : 0L;

        VoltTable result = new VoltTable(
            new VoltTable.ColumnInfo("PAGE_COUNT", VoltType.BIGINT),
            new VoltTable.ColumnInfo("TXN_COUNT",  VoltType.BIGINT)
        );
        result.addRow(pageCount, txnCount);
        return result;
    }
}