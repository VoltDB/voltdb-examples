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
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

/**
 * Single-pipeline main class for the write path — the deployable entry point
 * used by the Dataflow Flex Template (see {@code deployment/Dockerfile.flex-launcher}).
 *
 * <p>Unlike {@link BasicIoExample}, which chains four sequential pipelines
 * with {@code waitUntilFinish()}, this class submits <em>one</em> pipeline
 * and returns. That shape is required for Flex Templates:
 * <ul>
 *   <li>Flex Templates run under {@code --templateLocation}, where
 *       {@code Pipeline.run()} writes the pipeline graph to GCS and returns
 *       a result whose {@code waitUntilFinish()} throws
 *       {@code UnsupportedOperationException}.</li>
 *   <li>Only one pipeline graph fits at that location — sequential
 *       {@code p.run()} calls would overwrite each other, and the launcher
 *       would submit only the last one.</li>
 * </ul>
 *
 * <p>End-to-end verification is external to this pipeline: after Dataflow
 * reports the job {@code Done}, query VoltDB directly and confirm
 * {@code SELECT COUNT(*) FROM ACCOUNTS} equals {@code --seedCount}. Read
 * operations aren't packaged as separate Flex Template mains because they
 * exercise the same SSL / Secret Manager / {@code extraFilesToStage}
 * plumbing — the write proof is sufficient for the deployment story.
 */
public final class WriteAccountsMain {

    private static final Logger LOG = LoggerFactory.getLogger(WriteAccountsMain.class);

    public static void main(String[] args) {
        BasicIoOptions options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(BasicIoOptions.class);

        VoltDbIO.ConnectionConfig conn = BasicIoExample.buildConnection(options);

        Pipeline p = Pipeline.create(options);
        List<Row> rows = IntStream.range(0, options.getSeedCount())
                .mapToObj(i -> Row.withSchema(LoadAccounts.SCHEMA)
                        .addValues(
                                i,
                                "account-" + i,
                                (byte) 1,
                                new BigDecimal("10000.00"),
                                new BigDecimal("5000.00"))
                        .build())
                .collect(Collectors.toList());

        p.apply("SeedRows", Create.of(rows).withRowSchema(LoadAccounts.SCHEMA))
                .apply("UpsertAccount", VoltDbIO.<Row>write()
                        .withConnectionConfig(conn)
                        .withProcedure("UpsertAccount")
                        .withParameterMapper(new VoltDbIO.RowToParametersMapper()));

        // NB: no waitUntilFinish() — under --templateLocation (Flex Template
        // mode) it throws UnsupportedOperationException. p.run() writes the
        // graph and returns; the Flex Template launcher submits the actual
        // Dataflow job.
        p.run();

        LOG.info("WriteAccountsMain pipeline submitted with seedCount={}", options.getSeedCount());
    }

    private WriteAccountsMain() {}
}