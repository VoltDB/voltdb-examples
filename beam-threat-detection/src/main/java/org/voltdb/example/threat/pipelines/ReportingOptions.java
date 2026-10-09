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
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Hidden;

/**
 * PipelineOptions for the reporting batch pipeline. Reads new transactions
 * from VoltDB, enriches with MaxMind GeoIP, writes to BigQuery + Iceberg.
 */
public interface ReportingOptions extends GcpOptions {

    // --- VoltDB source ---

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

    @Description("Timeout in ms for the initial TCP+TLS handshake to VoltDB.")
    @Default.Integer(60000)
    int getConnectionTimeoutMs();
    void setConnectionTimeoutMs(int value);

    // --- BigQuery sink ---

    @Description("BigQuery dataset (in --project) that holds the target transactions table.")
    @Default.String("beam_threat_detection")
    String getBqDataset();
    void setBqDataset(String value);

    @Description("BigQuery target table name for enriched transactions.")
    @Default.String("transactions")
    String getBqTable();
    void setBqTable(String value);

    // --- Iceberg sink ---

    @Description("Iceberg warehouse root (HadoopCatalog on GCS).")
    @Default.String("gs://mpopova_volt_central1/iceberg/")
    String getIcebergWarehouse();
    void setIcebergWarehouse(String value);

    @Description("Iceberg table identifier as catalog.namespace.table.")
    @Default.String("hadoop.beam_threat_detection.transactions")
    String getIcebergTable();
    void setIcebergTable(String value);

    @Description("Enable the Iceberg sink. Set to false to skip Iceberg writes when the "
            + "Iceberg warehouse is not provisioned (BQ-only run).")
    @Default.Boolean(true)
    boolean getWriteIceberg();
    void setWriteIceberg(boolean value);

    // --- GeoIP ---

    @Description("GCS URI of the MaxMind GeoLite2-City .mmdb file. Workers download this at "
            + "cold-start and open it via com.maxmind.geoip2.DatabaseReader.")
    @Default.String("gs://mpopova_volt_central1/geoip/GeoLite2-City.mmdb")
    String getGeoipDbGcsUri();
    void setGeoipDbGcsUri(String value);

    // --- Watermark override ---

    @Description("Optional override for the initial watermark (epoch millis). When empty (default) "
            + "the pipeline reads MAX(TXN_TIME) from the BQ target table; set to 0 for a full "
            + "backfill or to a specific value for a controlled replay.")
    @Default.Long(-1L)
    long getInitialWatermarkMs();
    void setInitialWatermarkMs(long value);
}