/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.pipelines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;
import org.voltdb.example.threat.common.CidrUtils;
import org.voltdb.example.threat.common.PageHitEvent;

/**
 * Streaming pipeline that ingests page-hit events from a Google Cloud PubSub
 * topic and records each hit into VoltDB's subnet counter via the
 * {@code RecordSubnetRequest} stored procedure.
 *
 * <p>Shape:
 * <pre>
 * PubsubIO.readMessages().fromTopic(inputTopic)
 *     -&gt; ParseFn (JSON -&gt; PageHitEvent; parse failures go to a side output)
 *          -- main --&gt; EventToRowFn -&gt; VoltDbIO.write("RecordSubnetRequest")
 *          -- fail --&gt; PubsubIO.writeMessages().to(deadLetterTopic)
 * </pre>
 *
 * <p>The SP call is fire-and-forget: {@code VoltDbIO.write} returns {@code PDone}
 * and the SP's per-element response is discarded. Each row is inserted into
 * {@code SUBNET_REQUESTS} with {@code SOURCE_TYPE = 'PAGE'} and feeds the
 * {@code PAGES_PER_SUBNET} view that {@code ProcessTransaction} reads to
 * evaluate the {@code SUBNET_PAGE_HIT_RATE} rule.
 *
 * <p>Bad messages (malformed JSON, missing fields) are re-published to the DLQ
 * topic with the original bytes preserved and error info attached as
 * attributes so the pipeline itself stays healthy.
 */
public final class PageHitsIngestPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(PageHitsIngestPipeline.class);

    /** Row schema — field order matches the {@code RecordSubnetRequest} SP signature. */
    static final Schema RECORD_SUBNET_REQUEST_SCHEMA = Schema.builder()
            .addStringField("SUBNET")
            .addInt64Field("REQUEST_ID")
            .addStringField("SOURCE_IP")
            .addInt64Field("REQUEST_TIME_MS")
            .addStringField("SOURCE_TYPE")
            .addNullableField("PAGE_URL", Schema.FieldType.STRING)
            .build();

    static final TupleTag<PageHitEvent> PARSED = new TupleTag<PageHitEvent>() {};
    static final TupleTag<PubsubMessage> PARSE_FAILURES = new TupleTag<PubsubMessage>() {};

    private PageHitsIngestPipeline() {
        // static main only
    }

    public static void main(String[] args) {
        PageHitsIngestOptions options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(PageHitsIngestOptions.class);
        // Force streaming — the source is unbounded PubSub.
        options.setStreaming(true);

        VoltDbIO.ConnectionConfig conn = buildConnection(options);

        Pipeline p = Pipeline.create(options);
        buildPipeline(p, options, conn);

        LOG.info("Starting PageHitsIngestPipeline. inputTopic={}, dlqTopic={}, voltdbHosts={}",
                options.getInputTopic(), options.getDeadLetterTopic(), options.getVoltdbHosts());
        p.run();
    }

    /**
     * Wires the full pipeline against the supplied {@link Pipeline}, including
     * the PubSub source and the PubSub DLQ sink.
     */
    static void buildPipeline(Pipeline p, PageHitsIngestOptions options,
                              VoltDbIO.ConnectionConfig conn) {
        PCollection<PubsubMessage> input = p.apply("ReadPageHits",
                PubsubIO.readMessages().fromTopic(options.getInputTopic()));
        PCollection<PubsubMessage> dlq = applyIngest(input, options.getSubnetPrefixLength(), conn);
        dlq.apply("WriteDlq", PubsubIO.writeMessages().to(options.getDeadLetterTopic()));
    }

    /**
     * Applies the parse + row-conversion + VoltDbIO.write chain to a bounded or
     * unbounded {@code PCollection<PubsubMessage>} and returns the dead-letter
     * side output.
     *
     * <p>Split out of {@link #buildPipeline} so integration tests can substitute
     * a {@code Create.of(...)} source and assert on the DLQ collection directly
     * without touching real PubSub.
     */
    public static PCollection<PubsubMessage> applyIngest(
            PCollection<PubsubMessage> input,
            int subnetPrefixLength,
            VoltDbIO.ConnectionConfig conn) {
        PCollectionTuple parsed = input.apply("ParsePageHit",
                ParDo.of(new ParsePageHitFn())
                        .withOutputTags(PARSED, TupleTagList.of(PARSE_FAILURES)));

        parsed.get(PARSED)
                .apply("EventToRow", ParDo.of(new EventToRowFn(subnetPrefixLength)))
                .setRowSchema(RECORD_SUBNET_REQUEST_SCHEMA)
                .apply("RecordSubnetRequest", VoltDbIO.<Row>write()
                        .withConnectionConfig(conn)
                        .withProcedure("RecordSubnetRequest")
                        .withParameterMapper(new VoltDbIO.RowToParametersMapper()));

        return parsed.get(PARSE_FAILURES);
    }

    static VoltDbIO.ConnectionConfig buildConnection(PageHitsIngestOptions options) {
        VoltDbIO.ConnectionConfig.Builder b = VoltDbIO.connectionConfig()
                .withHosts(options.getVoltdbHosts())
                .withConnectionTimeout(options.getConnectionTimeoutMs());
        if (!options.getVoltdbUser().isEmpty()) {
            b.withUsername(options.getVoltdbUser());
        }
        if (!options.getVoltdbPassword().isEmpty()) {
            b.withPassword(options.getVoltdbPassword());
        }
        return b.build();
    }

    /**
     * Parses a PubSub message payload as JSON into a {@link PageHitEvent}.
     * Malformed messages are emitted to the {@link #PARSE_FAILURES} side output
     * with the original bytes preserved and error info attached as attributes.
     */
    static class ParsePageHitFn extends DoFn<PubsubMessage, PageHitEvent> {

        @ProcessElement
        public void process(@Element PubsubMessage msg, MultiOutputReceiver out) {
            byte[] payload = msg.getPayload();
            try {
                String json = new String(payload, StandardCharsets.UTF_8);
                PageHitEvent event = PageHitEvent.fromJson(json);
                if (event.getSourceIp() == null || event.getPageUrl() == null
                        || event.getTimestamp() == null) {
                    throw new IOException("PageHitEvent is missing one of "
                            + "sourceIp/pageUrl/timestamp: " + event);
                }
                out.get(PARSED).output(event);
            } catch (Exception e) {
                Map<String, String> attributes = new HashMap<>();
                if (msg.getAttributeMap() != null) {
                    attributes.putAll(msg.getAttributeMap());
                }
                attributes.put("error.class", e.getClass().getName());
                String message = e.getMessage();
                attributes.put("error.message", message == null ? "" : message);
                out.get(PARSE_FAILURES).output(new PubsubMessage(payload, attributes));
            }
        }
    }

    /**
     * Converts a {@link PageHitEvent} into a {@link Row} whose field order
     * matches the {@code RecordSubnetRequest} SP parameter order.
     */
    static class EventToRowFn extends DoFn<PageHitEvent, Row> {

        private final int subnetPrefixLength;

        EventToRowFn(int subnetPrefixLength) {
            this.subnetPrefixLength = subnetPrefixLength;
        }

        @ProcessElement
        public void process(@Element PageHitEvent event, OutputReceiver<Row> out) {
            String subnet = CidrUtils.extractSubnet(event.getSourceIp(), subnetPrefixLength);
            // Random REQUEST_ID: at-least-once retries produce duplicate rows,
            // which is acceptable for a best-effort rate counter. See RUNBOOK.md
            // "Followup: harden REQUEST_ID for at-least-once retries" if exactly-once
            // counter accuracy ever becomes a requirement.
            long requestId = ThreadLocalRandom.current().nextLong();
            long requestTimeMs = event.getTimestamp().toEpochMilli();
            Row row = Row.withSchema(RECORD_SUBNET_REQUEST_SCHEMA)
                    .addValues(
                            subnet,
                            requestId,
                            event.getSourceIp(),
                            requestTimeMs,
                            "PAGE",
                            event.getPageUrl())
                    .build();
            out.output(row);
        }
    }
}
