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
package org.voltdb.example.threat.pipelines;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Hidden;
import org.apache.beam.sdk.options.StreamingOptions;

/**
 * PipelineOptions for the page-hits ingest streaming pipeline.
 *
 * <p>Extends {@link StreamingOptions} so {@code --streaming=true} is the default,
 * and {@link PubsubOptions} so the runner recognises PubSub-specific flags
 * (project resolution, emulator host, etc.).
 */
public interface PageHitsIngestOptions extends StreamingOptions, PubsubOptions {

    // --- PubSub ---

    @Description("Fully-qualified PubSub topic the pipeline reads page-hit events from, "
            + "e.g. projects/PROJECT/topics/threat-page-hits. The runner creates a "
            + "temporary subscription on this topic.")
    @Default.String("projects/voltdb-operator/topics/threat-page-hits")
    String getInputTopic();
    void setInputTopic(String value);

    @Description("Fully-qualified PubSub topic that unparseable messages are re-published "
            + "to, with the raw payload preserved and error info attached as message "
            + "attributes (error.class, error.message).")
    @Default.String("projects/voltdb-operator/topics/threat-page-hits-dlq")
    String getDeadLetterTopic();
    void setDeadLetterTopic(String value);

    // --- VoltDB ---

    @Description("Comma-separated VoltDB hosts, e.g. host1:21212,host2:21212")
    @Default.String("localhost:21212")
    String getVoltdbHosts();
    void setVoltdbHosts(String value);

    @Description("VoltDB username. Empty for no-auth clusters.")
    @Default.String("")
    String getVoltdbUser();
    void setVoltdbUser(String value);

    @Hidden
    @JsonIgnore
    @Description("VoltDB password. Empty for no-auth clusters.")
    @Default.String("")
    String getVoltdbPassword();
    void setVoltdbPassword(String value);

    @Description("Timeout in ms for the initial TCP+TLS handshake to VoltDB. Increase for "
            + "cold Dataflow workers where the first connection can exceed voltdbclient "
            + "defaults.")
    @Default.Integer(60000)
    int getConnectionTimeoutMs();
    void setConnectionTimeoutMs(int value);

    // --- Subnet ---

    @Description("CIDR prefix length used to bucket source IPs into subnet keys. Matches "
            + "the granularity of SUBNET_REQUESTS partitioning and the REQUESTS_PER_SUBNET "
            + "rate window.")
    @Default.Integer(24)
    int getSubnetPrefixLength();
    void setSubnetPrefixLength(int value);
}
