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