package org.voltdb.beam.examples.basicio;

import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;

/**
 * Pipeline options for the basic-io example. Extends Beam's PipelineOptions so
 * the --runner flag (DirectRunner, DataflowRunner, ...) is inherited.
 */
public interface BasicIoOptions extends PipelineOptions {

    @Description("Comma-separated VoltDB hosts, e.g. host1:21212,host2:21212")
    @Default.String("localhost:21212")
    String getVoltdbHosts();
    void setVoltdbHosts(String value);

    @Description("VoltDB username. Empty for no-auth clusters.")
    @Default.String("")
    String getVoltdbUser();
    void setVoltdbUser(String value);

    @Description("VoltDB password. Empty for no-auth clusters.")
    @Default.String("")
    String getVoltdbPassword();
    void setVoltdbPassword(String value);

    @Description("Number of ACCOUNTS rows to seed before the read steps.")
    @Default.Integer(100)
    int getSeedCount();
    void setSeedCount(int value);

    // --- SSL / TLS ---

    @Description("Enable TLS for the client-side connection to VoltDB.")
    @Default.Boolean(false)
    boolean getSslEnabled();
    void setSslEnabled(boolean value);

    @Description("Verify server hostname against the certificate. Only used when --sslEnabled=true.")
    @Default.Boolean(false)
    boolean getSslHostnameCheck();
    void setSslHostnameCheck(boolean value);

    @Description("SSL properties file (trustStore/trustStorePassword/keyStore/keyStorePassword). "
            + "When set, takes precedence over --sslTrustStore / --sslKeyStore.")
    @Default.String("")
    String getSslPropertyFile();
    void setSslPropertyFile(String value);

    @Description("Path to the client trust store used to verify the VoltDB server cert.")
    @Default.String("")
    String getSslTrustStore();
    void setSslTrustStore(String value);

    @Description("Password for --sslTrustStore.")
    @Default.String("")
    String getSslTrustStorePassword();
    void setSslTrustStorePassword(String value);

    @Description("Path to the client key store presented to VoltDB for mutual TLS.")
    @Default.String("")
    String getSslKeyStore();
    void setSslKeyStore(String value);

    @Description("Password for --sslKeyStore.")
    @Default.String("")
    String getSslKeyStorePassword();
    void setSslKeyStorePassword(String value);
}