package org.voltdb.beam.examples.basicio;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

/**
 * Lists all ACCOUNTS via an ad-hoc SQL SELECT
 * ({@link VoltDbIO#read()} with {@code withQuery(...)}). Single-worker read,
 * intended for one-off scans or small tables.
 */
public final class ListAllAccountsViaSql {

    public static void run(BasicIoOptions options, VoltDbIO.ConnectionConfig conn) {
        Pipeline p = Pipeline.create(options);

        PCollection<Row> rows = p.apply("SelectAllAccounts",
                VoltDbIO.<Row>read()
                        .withConnectionConfig(conn)
                        .withQuery("SELECT ACCOUNT_ID, NAME, ENABLED, BALANCE, DAILY_LIMIT FROM ACCOUNTS")
                        .withRowMapper(new VoltDbIO.VoltTableRowMapper(LoadAccounts.SCHEMA)));

        PAssert.thatSingleton(rows.apply("Count", Count.globally()))
                .isEqualTo((long) options.getSeedCount());

        PipelineResult.State state = p.run().waitUntilFinish();
        if (state != PipelineResult.State.DONE) {
            throw new IllegalStateException("ListAllAccountsViaSql pipeline finished in state " + state);
        }
    }

    private ListAllAccountsViaSql() {}
}