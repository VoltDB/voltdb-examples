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