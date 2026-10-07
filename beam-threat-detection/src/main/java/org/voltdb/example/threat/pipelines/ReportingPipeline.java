/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.pipelines;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.managed.Managed;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionRowTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdb.beam.sdk.io.voltdb.VoltDbIO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled batch pipeline that reads new transactions from VoltDB, enriches
 * them with MaxMind GeoIP, and writes to BigQuery and Iceberg in parallel.
 *
 * <p>Shape:
 * <pre>
 *   watermark = SELECT MAX(TXN_TIME) FROM &lt;bq_table&gt;   (main-thread, before p.run())
 *   VoltDbIO.read().withProcedure("ReadTxnsSince", watermark)
 *     -&gt; GeoIpEnrichFn
 *     -&gt; branch: BigQueryIO.write (append, dedupe on TXN_ID)
 *     -&gt; branch: Managed.ICEBERG write (append)
 * </pre>
 *
 * <p>Watermark strategy is sink-driven: BQ is the source of truth for
 * "what has been exported already". If a run fails after read but before
 * write, the next run automatically re-reads the same rows because the BQ
 * MAX(TXN_TIME) never advanced.
 */
public final class ReportingPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingPipeline.class);

    /**
     * Schema of the {@code ReadTxnsSince} SP output. Field order MUST match the
     * SELECT column list in {@code voltdb-ddl.sql}; the connector's row mapper
     * copies VoltTable columns to Row fields positionally, so names here can
     * use the lowercase BigQuery / analytics convention independently of
     * VoltDB's uppercase SQL identifiers.
     */
    public static final Schema TXN_SCHEMA = Schema.builder()
            .addInt64Field("txn_id")
            .addInt64Field("account_id")
            .addDateTimeField("txn_time")
            .addNullableField("merchant_id", Schema.FieldType.INT32)
            .addNullableField("amount", Schema.FieldType.DECIMAL)
            .addNullableField("device_id", Schema.FieldType.STRING)
            .addNullableField("source_ip", Schema.FieldType.STRING)
            .addNullableField("accepted", Schema.FieldType.BYTE)
            .addNullableField("reason", Schema.FieldType.STRING)
            .addNullableField("rule_name", Schema.FieldType.STRING)
            .addNullableField("account_name", Schema.FieldType.STRING)
            .addNullableField("merchant_name", Schema.FieldType.STRING)
            .addNullableField("merchant_category", Schema.FieldType.STRING)
            .build();

    /** GeoIP-enriched output schema — input schema + 5 GeoIP fields. */
    public static final Schema ENRICHED_SCHEMA = GeoIpEnrichFn.addGeoIpFields(TXN_SCHEMA);

    private ReportingPipeline() {
        // static main only
    }

    public static void main(String[] args) {
        ReportingOptions options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(ReportingOptions.class);

        long watermarkMs = resolveWatermark(options);
        LOG.info("Reporting run: voltdbHosts={}, bqTable={}.{}, watermarkMs={}",
                options.getVoltdbHosts(), options.getBqDataset(), options.getBqTable(),
                watermarkMs);

        VoltDbIO.ConnectionConfig conn = buildConnection(options);

        Pipeline p = Pipeline.create(options);

        PCollection<Row> txns = applyRead(p, conn, watermarkMs);

        PCollection<Row> enriched = txns
                .apply("GeoIpEnrich",
                        ParDo.of(new GeoIpEnrichFn(options.getGeoipDbGcsUri(), ENRICHED_SCHEMA)))
                .setRowSchema(ENRICHED_SCHEMA);

        // BigQuery sink — append; watermark prevents overlap, TXN_ID is the dedupe key
        // if we ever want MERGE (see RUNBOOK: hardening options).
        String bqTable = options.getProject() + ":" + options.getBqDataset() + "." + options.getBqTable();
        enriched.apply("WriteBigQuery", BigQueryIO.<Row>write()
                .to(bqTable)
                .useBeamSchema()
                .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                .withMethod(BigQueryIO.Write.Method.FILE_LOADS));

        // Iceberg sink — optional (--writeIceberg=false to skip when warehouse not provisioned).
        //
        // Beam's Managed.ICEBERG maps Schema.FieldType.DECIMAL → Iceberg string (because Beam
        // DECIMAL is unbounded and Iceberg decimal requires precision/scale), then at write
        // time tries to cast the row value to String — which throws ClassCastException because
        // the value is still java.math.BigDecimal. Workaround: materialize `amount` as a
        // STRING-typed field for the Iceberg branch only, keeping the BQ branch on DECIMAL
        // (BigQueryIO handles BigDecimal → NUMERIC correctly).
        if (options.getWriteIceberg()) {
            Map<String, Object> icebergConfig = new HashMap<>();
            icebergConfig.put("table", options.getIcebergTable());
            Map<String, String> catalogProps = new HashMap<>();
            catalogProps.put("type", "hadoop");
            catalogProps.put("warehouse", options.getIcebergWarehouse());
            icebergConfig.put("catalog_properties", catalogProps);

            Schema icebergSchema = withAmountAsString(ENRICHED_SCHEMA);
            PCollection<Row> forIceberg = enriched
                    .apply("AmountToStringForIceberg", MapElements
                            .into(TypeDescriptor.of(Row.class))
                            .via((Row r) -> toIcebergRow(r, icebergSchema)))
                    .setRowSchema(icebergSchema);

            PCollectionRowTuple.of("input", forIceberg)
                    .apply("WriteIceberg",
                            Managed.write(Managed.ICEBERG).withConfig(icebergConfig));
        }

        p.run().waitUntilFinish();
    }

    /**
     * Resolve the pipeline's read watermark. When {@code --initialWatermarkMs}
     * is set (&gt;= 0), use it verbatim. Otherwise query
     * {@code SELECT MAX(TXN_TIME) FROM <bq_table>}; if the table does not exist
     * or is empty, use epoch 0 (full backfill on the first run).
     */
    static long resolveWatermark(ReportingOptions options) {
        long override = options.getInitialWatermarkMs();
        if (override >= 0L) {
            LOG.info("Using --initialWatermarkMs override: {}", override);
            return override;
        }
        String fqTable = "`" + options.getProject() + "." + options.getBqDataset()
                + "." + options.getBqTable() + "`";
        String sql = "SELECT UNIX_MILLIS(MAX(txn_time)) AS MAX_MS FROM " + fqTable;
        BigQuery bq = BigQueryOptions.newBuilder()
                .setProjectId(options.getProject())
                .build()
                .getService();
        try {
            TableResult result = bq.query(QueryJobConfiguration.newBuilder(sql).build());
            for (com.google.cloud.bigquery.FieldValueList row : result.iterateAll()) {
                FieldValue v = row.get("MAX_MS");
                if (v == null || v.isNull()) {
                    return 0L;
                }
                return v.getLongValue();
            }
            return 0L;
        } catch (BigQueryException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Not found") || msg.contains("does not exist")
                    || msg.contains("was not found")) {
                LOG.info("BQ target table not yet present — starting from epoch 0.");
                return 0L;
            }
            throw new RuntimeException("Failed to resolve watermark from BigQuery", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while resolving watermark", e);
        }
    }

    /**
     * Applies just the VoltDB {@code ReadTxnsSince} read step. Extracted so
     * integration tests can exercise the connector round-trip against a
     * Testcontainer without pulling in the GeoIP / BigQuery / Iceberg sinks
     * (which require GCP credentials and live external services).
     */
    public static PCollection<Row> applyRead(Pipeline p,
                                             VoltDbIO.ConnectionConfig conn,
                                             long watermarkMs) {
        return p.apply("ReadTxnsSince", VoltDbIO.<Row>read()
                .withConnectionConfig(conn)
                .withProcedure("ReadTxnsSince", watermarkMs)
                .withRowMapper(new VoltDbIO.VoltTableRowMapper(TXN_SCHEMA)));
    }

    /**
     * Returns an Iceberg-compatible copy of the enriched schema. Beam's
     * Managed.ICEBERG write path has broken handling for a couple of primitive
     * types that it maps to Iceberg {@code string} without converting the
     * values — specifically {@code DECIMAL} (expects BigDecimal → String) and
     * {@code BYTE} (expects Byte → String). Both throw ClassCastException at
     * write time. Workaround: project those fields to concrete types the sink
     * handles cleanly — DECIMAL → STRING (full-precision plain-string form),
     * BYTE → INT32.
     */
    static Schema withAmountAsString(Schema in) {
        Schema.Builder b = Schema.builder();
        for (Schema.Field f : in.getFields()) {
            if (f.getName().equals("amount")) {
                b.addNullableField("amount", Schema.FieldType.STRING);
            } else if (f.getName().equals("accepted")) {
                b.addNullableField("accepted", Schema.FieldType.INT32);
            } else {
                b.addField(f);
            }
        }
        return b.build();
    }

    /**
     * Project an enriched row into the Iceberg-compatible schema, converting
     * {@code amount} ({@link BigDecimal} → String) and {@code accepted}
     * ({@link Byte} → Integer). See {@link #withAmountAsString(Schema)} for
     * why these two fields need the dance.
     */
    static Row toIcebergRow(Row in, Schema icebergSchema) {
        List<Object> values = new ArrayList<>(icebergSchema.getFieldCount());
        for (Schema.Field f : icebergSchema.getFields()) {
            switch (f.getName()) {
                case "amount": {
                    BigDecimal amt = in.getDecimal("amount");
                    values.add(amt == null ? null : amt.toPlainString());
                    break;
                }
                case "accepted": {
                    Byte accepted = in.getByte("accepted");
                    values.add(accepted == null ? null : accepted.intValue());
                    break;
                }
                default:
                    values.add(in.getValue(f.getName()));
            }
        }
        return Row.withSchema(icebergSchema).attachValues(values);
    }

    static VoltDbIO.ConnectionConfig buildConnection(ReportingOptions options) {
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
}