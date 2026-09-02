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

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Hidden;
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

    // @Hidden hides the option from --help and from Beam's DisplayData for the
    // pipeline; @JsonIgnore excludes it from the PipelineOptions JSON that
    // Dataflow displays in the job info panel. Together they keep the
    // plaintext password out of every visible surface.
    @Hidden
    @JsonIgnore
    @Description("VoltDB password. Empty for no-auth clusters.")
    @Default.String("")
    String getVoltdbPassword();
    void setVoltdbPassword(String value);

    @Description("Number of ACCOUNTS rows to seed before the read steps.")
    @Default.Integer(100)
    int getSeedCount();
    void setSeedCount(int value);

    @Description("Timeout in ms for the initial TCP+TLS handshake to VoltDB. Increase for cold Dataflow "
            + "workers where the first connection (worker cold-start plus TLS handshake) can exceed "
            + "voltdbclient defaults.")
    @Default.Integer(60000)
    int getConnectionTimeoutMs();
    void setConnectionTimeoutMs(int value);

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

    @Hidden
    @JsonIgnore
    @Description("Password for --sslTrustStore.")
    @Default.String("")
    String getSslTrustStorePassword();
    void setSslTrustStorePassword(String value);

    @Description("Path to the client key store presented to VoltDB for mutual TLS.")
    @Default.String("")
    String getSslKeyStore();
    void setSslKeyStore(String value);

    @Hidden
    @JsonIgnore
    @Description("Password for --sslKeyStore.")
    @Default.String("")
    String getSslKeyStorePassword();
    void setSslKeyStorePassword(String value);

    // --- Secret Manager integration ---

    @Description("Secret Manager resource name (projects/PROJECT/secrets/SECRET/versions/VERSION) "
            + "for the VoltDB password. When set, the plaintext is fetched on the worker at "
            + "connect time and never enters the pipeline graph. Overrides --voltdbPassword.")
    @Default.String("")
    String getSecretManagerPasswordSecret();
    void setSecretManagerPasswordSecret(String value);

    @Description("Secret Manager resource name for the SSL trust store password. When set, "
            + "the plaintext is fetched on the worker at connect time. Overrides --sslTrustStorePassword.")
    @Default.String("")
    String getSecretManagerTrustStorePasswordSecret();
    void setSecretManagerTrustStorePasswordSecret(String value);

    @Description("Secret Manager resource name for the SSL trust store JKS file (raw bytes payload). "
            + "When set, the connector fetches the payload on the worker, materializes it to a temp "
            + "file, and hands that path to Client2Config — no cert file needs to live on the worker "
            + "image or in GCS in unprotected form. Overrides --sslTrustStore.")
    @Default.String("")
    String getSecretManagerTrustStoreBytesSecret();
    void setSecretManagerTrustStoreBytesSecret(String value);
}