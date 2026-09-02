package org.voltdb.beam.examples.basicio;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
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

    // Package-private so WriteAccountsMain (Flex Template entry point) can
    // reuse the same connection wiring.
    static VoltDbIO.ConnectionConfig buildConnection(BasicIoOptions options) {
        VoltDbIO.ConnectionConfig.Builder b = VoltDbIO.connectionConfig()
                .withHosts(options.getVoltdbHosts())
                .withConnectionTimeout(options.getConnectionTimeoutMs());
        if (!options.getVoltdbUser().isEmpty()) {
            b.withUsername(options.getVoltdbUser());
        }
        // Password: Secret Manager wins over plaintext. When neither is set the
        // cluster is assumed to be no-auth.
        if (!options.getSecretManagerPasswordSecret().isEmpty()) {
            final String secretName = options.getSecretManagerPasswordSecret();
            b.withPasswordSupplier(() -> fetchSecret(secretName));
        } else if (!options.getVoltdbPassword().isEmpty()) {
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
            // Trust store: Secret Manager bytes-supplier > Secret Manager password + local path
            // > plaintext.
            if (!options.getSecretManagerTrustStoreBytesSecret().isEmpty()) {
                final String tsBytesSecret = options.getSecretManagerTrustStoreBytesSecret();
                b.withTrustStoreBytesSupplier(() -> fetchSecretBytes(tsBytesSecret));
                if (!options.getSecretManagerTrustStorePasswordSecret().isEmpty()) {
                    final String tsPwSecret = options.getSecretManagerTrustStorePasswordSecret();
                    b.withTrustStorePasswordSupplier(() -> fetchSecret(tsPwSecret));
                } else if (!options.getSslTrustStorePassword().isEmpty()) {
                    b.withTrustStorePasswordSupplier(options::getSslTrustStorePassword);
                }
            } else if (!options.getSslTrustStore().isEmpty()) {
                if (!options.getSecretManagerTrustStorePasswordSecret().isEmpty()) {
                    final String tsSecret = options.getSecretManagerTrustStorePasswordSecret();
                    b.withTrustStore(options.getSslTrustStore())
                     .withTrustStorePasswordSupplier(() -> fetchSecret(tsSecret));
                } else {
                    b.withTrustStore(options.getSslTrustStore(), options.getSslTrustStorePassword());
                }
            }
            if (!options.getSslKeyStore().isEmpty()) {
                b.withKeyStore(options.getSslKeyStore(), options.getSslKeyStorePassword());
            }
        }
        return b.build();
    }

    /**
     * Fetch a plaintext secret payload from Google Cloud Secret Manager.
     * Called on the worker at ConnectionConfig.createClient() time via a
     * SerializableSupplier — the plaintext never enters the pipeline graph.
     * The caller passes a fully-qualified resource name of the form
     * {@code projects/PROJECT/secrets/SECRET/versions/VERSION}.
     */
    private static String fetchSecret(String secretResourceName) {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            return client.accessSecretVersion(secretResourceName)
                    .getPayload().getData().toStringUtf8();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Secret Manager secret "
                    + secretResourceName, e);
        }
    }

    /**
     * Fetch a binary secret payload (e.g., a JKS file) from Secret Manager.
     * Same lifecycle as {@link #fetchSecret} — resolved on the worker at
     * connect time via a SerializableSupplier, never in the pipeline graph.
     */
    private static byte[] fetchSecretBytes(String secretResourceName) {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            return client.accessSecretVersion(secretResourceName)
                    .getPayload().getData().toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Secret Manager secret bytes "
                    + secretResourceName, e);
        }
    }

    private static void step(String name, Runnable body) {
        LOG.info(">>> {}", name);
        body.run();
        LOG.info("<<< {} — OK", name);
    }

    private BasicIoExample() {}
}