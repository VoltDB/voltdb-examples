package org.voltdb.beam.examples.basicio;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

/**
 * Lists all ACCOUNTS in parallel by running the {@code ScanAccountsPartition}
 * single-partition procedure once per VoltDB logical partition
 * ({@link VoltDbIO#read()} with {@code withPartitionedProcedure(...)}).
 * Scales for large partitioned tables; one Beam reader per partition.
 */
public final class ListAllAccountsInParallel {

    public static void run(BasicIoOptions options, VoltDbIO.ConnectionConfig conn) {
        Pipeline p = Pipeline.create(options);

        PCollection<Row> rows = p.apply("ScanAllPartitions",
                VoltDbIO.<Row>read()
                        .withConnectionConfig(conn)
                        .withPartitionedProcedure("ScanAccountsPartition",
                                VoltDbIO.PartitionKeyType.INTEGER)
                        .acknowledgePartitionedTableOnly()
                        .withRowMapper(new VoltDbIO.VoltTableRowMapper(LoadAccounts.SCHEMA)));

        PAssert.thatSingleton(rows.apply("Count", Count.globally()))
                .isEqualTo((long) options.getSeedCount());

        PipelineResult.State state = p.run().waitUntilFinish();
        if (state != PipelineResult.State.DONE) {
            throw new IllegalStateException("ListAllAccountsInParallel pipeline finished in state " + state);
        }
    }

    private ListAllAccountsInParallel() {}
}