package org.voltdb.beam.examples.basicio;

import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

/**
 * Minimally viable example that exercises every VoltDbIO operation against a
 * running VoltDB cluster:
 * <ol>
 *   <li>{@link LoadAccounts} — write path via UpsertAccount</li>
 *   <li>{@link ListAllAccountsViaSql} — ad-hoc SQL read</li>
 *   <li>{@link ListAllAccountsViaProcedure} — stored-procedure read</li>
 *   <li>{@link ListAllAccountsInParallel} — partitioned parallel read</li>
 * </ol>
 * Each step runs as its own Beam pipeline so a failure in one does not obscure
 * the others. Selects the runner (Direct, Dataflow, ...) via the standard
 * {@code --runner} flag; VoltDB coordinates via {@code --voltdbHosts} etc.
 */
public final class BasicIoExample {

    private static final Logger LOG = LoggerFactory.getLogger(BasicIoExample.class);

    public static void main(String[] args) {
        BasicIoOptions options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(BasicIoOptions.class);

        VoltDbIO.ConnectionConfig conn = buildConnection(options);

        step("Load reference data (write)",
                () -> LoadAccounts.run(options, conn));
        step("List all accounts via SQL (ad-hoc read)",
                () -> ListAllAccountsViaSql.run(options, conn));
        step("List all accounts via stored procedure (multi-partition read)",
                () -> ListAllAccountsViaProcedure.run(options, conn));
        step("List all accounts in parallel by partition (partition-parallel read)",
                () -> ListAllAccountsInParallel.run(options, conn));

        LOG.info("Basic-io example completed successfully.");
    }

    private static VoltDbIO.ConnectionConfig buildConnection(BasicIoOptions options) {
        VoltDbIO.ConnectionConfig.Builder b = VoltDbIO.connectionConfig()
                .withHosts(options.getVoltdbHosts());
        if (!options.getVoltdbUser().isEmpty()) {
            b.withUsername(options.getVoltdbUser());
        }
        if (!options.getVoltdbPassword().isEmpty()) {
            b.withPassword(options.getVoltdbPassword());
        }
        if (options.getSslEnabled()) {
            b.withSslEnabled(true);
            if (options.getSslHostnameCheck()) {
                b.withSslHostnameCheck(true);
            }
            if (!options.getSslPropertyFile().isEmpty()) {
                b.withSslPropertyFile(options.getSslPropertyFile());
            }
            if (!options.getSslTrustStore().isEmpty()) {
                b.withTrustStore(options.getSslTrustStore(), options.getSslTrustStorePassword());
            }
            if (!options.getSslKeyStore().isEmpty()) {
                b.withKeyStore(options.getSslKeyStore(), options.getSslKeyStorePassword());
            }
        }
        return b.build();
    }

    private static void step(String name, Runnable body) {
        LOG.info(">>> {}", name);
        body.run();
        LOG.info("<<< {} — OK", name);
    }

    private BasicIoExample() {}
}