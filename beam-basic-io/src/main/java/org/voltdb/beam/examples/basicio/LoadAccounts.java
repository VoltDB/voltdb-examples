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

package org.voltdb.beam.examples.basicio;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.Row;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

/**
 * Bulk-loads reference-data ACCOUNTS via the connector's write path
 * ({@link VoltDbIO#write()} calling the {@code UpsertAccount} stored procedure).
 * UPSERT makes the operation idempotent under Beam's at-least-once retries.
 */
public final class LoadAccounts {

    /** Row schema matching the ACCOUNTS table and the UpsertAccount parameter order. */
    public static final Schema SCHEMA = Schema.builder()
            .addInt32Field("ACCOUNT_ID")
            .addStringField("NAME")
            .addByteField("ENABLED")
            .addDecimalField("BALANCE")
            .addDecimalField("DAILY_LIMIT")
            .build();

    public static void run(BasicIoOptions options, VoltDbIO.ConnectionConfig conn) {
        Pipeline p = Pipeline.create(options);

        List<Row> rows = IntStream.range(0, options.getSeedCount())
                .mapToObj(i -> Row.withSchema(SCHEMA)
                        .addValues(
                                i,
                                "account-" + i,
                                (byte) 1,
                                new BigDecimal("10000.00"),
                                new BigDecimal("5000.00"))
                        .build())
                .collect(Collectors.toList());

        p.apply("SeedRows", Create.of(rows).withRowSchema(SCHEMA))
                .apply("UpsertAccount", VoltDbIO.<Row>write()
                        .withConnectionConfig(conn)
                        .withProcedure("UpsertAccount")
                        .withParameterMapper(new VoltDbIO.RowToParametersMapper()));

        PipelineResult.State state = p.run().waitUntilFinish();
        if (state != PipelineResult.State.DONE) {
            throw new IllegalStateException("LoadAccounts pipeline finished in state " + state);
        }
    }

    private LoadAccounts() {}
}