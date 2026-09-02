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

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

/**
 * Lists all ACCOUNTS by calling the {@code GetAllAccounts} stored procedure
 * ({@link VoltDbIO#read()} with {@code withProcedure(...)}). Multi-partition
 * SP; single-worker read.
 */
public final class ListAllAccountsViaProcedure {

    public static void run(BasicIoOptions options, VoltDbIO.ConnectionConfig conn) {
        Pipeline p = Pipeline.create(options);

        PCollection<Row> rows = p.apply("CallGetAllAccounts",
                VoltDbIO.<Row>read()
                        .withConnectionConfig(conn)
                        .withProcedure("GetAllAccounts")
                        .withRowMapper(new VoltDbIO.VoltTableRowMapper(LoadAccounts.SCHEMA)));

        PAssert.thatSingleton(rows.apply("Count", Count.globally()))
                .isEqualTo((long) options.getSeedCount());

        PipelineResult.State state = p.run().waitUntilFinish();
        if (state != PipelineResult.State.DONE) {
            throw new IllegalStateException("ListAllAccountsViaProcedure pipeline finished in state " + state);
        }
    }

    private ListAllAccountsViaProcedure() {}
}