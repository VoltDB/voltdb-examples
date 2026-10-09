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

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.CityResponse;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Enriches transaction rows with GeoIP fields ({@code COUNTRY_CODE},
 * {@code COUNTRY_NAME}, {@code CITY}, {@code LATITUDE}, {@code LONGITUDE})
 * by looking up {@code SOURCE_IP} in a MaxMind GeoLite2-City database.
 *
 * <p>The {@code .mmdb} file is fetched from GCS in {@code @Setup} (once per
 * worker JVM), materialized to a temp file on the worker's local disk, and
 * opened with {@link DatabaseReader}. Lookups from {@code @ProcessElement} are
 * sub-millisecond in-memory operations. The reader is closed in
 * {@code @Teardown}.
 *
 * <p>Unknown / unparseable IPs (private ranges, IPv6, malformed) emit rows
 * with the GeoIP fields left null so downstream analytics can distinguish
 * "enrichment failed" from "IP resolves to nothing".
 */
public final class GeoIpEnrichFn extends DoFn<Row, Row> {

    private static final Logger LOG = LoggerFactory.getLogger(GeoIpEnrichFn.class);

    /** GCS URI of the .mmdb file, resolved once at worker startup. */
    private final String mmdbGcsUri;

    /** Schema of the emitted rows — input schema + GeoIP fields. */
    private final Schema outputSchema;

    private transient DatabaseReader reader;
    private transient Path mmdbLocalPath;

    public GeoIpEnrichFn(String mmdbGcsUri, Schema outputSchema) {
        this.mmdbGcsUri = mmdbGcsUri;
        this.outputSchema = outputSchema;
    }

    /** Adds the GeoIP fields to an input schema. */
    public static Schema addGeoIpFields(Schema inputSchema) {
        Schema.Builder builder = Schema.builder();
        for (Schema.Field f : inputSchema.getFields()) {
            builder.addField(f);
        }
        return builder
                .addNullableField("country_code", Schema.FieldType.STRING)
                .addNullableField("country_name", Schema.FieldType.STRING)
                .addNullableField("city_name", Schema.FieldType.STRING)
                .addNullableField("latitude", Schema.FieldType.DOUBLE)
                .addNullableField("longitude", Schema.FieldType.DOUBLE)
                .build();
    }

    @Setup
    public void setup() throws IOException {
        // gs://bucket/path/to/file.mmdb → bucket + object.
        if (!mmdbGcsUri.startsWith("gs://")) {
            throw new IllegalArgumentException(
                    "geoipDbGcsUri must be a gs:// URI, got: " + mmdbGcsUri);
        }
        String pathPart = mmdbGcsUri.substring("gs://".length());
        int slash = pathPart.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(
                    "geoipDbGcsUri missing object path: " + mmdbGcsUri);
        }
        String bucket = pathPart.substring(0, slash);
        String object = pathPart.substring(slash + 1);

        LOG.info("Downloading MaxMind DB from gs://{}/{}", bucket, object);
        Storage storage = StorageOptions.getDefaultInstance().getService();
        Blob blob = storage.get(BlobId.of(bucket, object));
        if (blob == null) {
            throw new IOException("MaxMind DB not found at " + mmdbGcsUri);
        }
        mmdbLocalPath = Files.createTempFile("geolite2-city-", ".mmdb");
        try (java.nio.channels.ReadableByteChannel src = blob.reader();
             java.io.InputStream in = java.nio.channels.Channels.newInputStream(src)) {
            Files.copy(in, mmdbLocalPath, StandardCopyOption.REPLACE_EXISTING);
        }
        reader = new DatabaseReader.Builder(mmdbLocalPath.toFile()).build();
        LOG.info("MaxMind DB opened: {} bytes", Files.size(mmdbLocalPath));
    }

    @ProcessElement
    public void process(@Element Row input, OutputReceiver<Row> out) {
        String sourceIp = input.getString("source_ip");

        String countryCode = null;
        String countryName = null;
        String city = null;
        Double latitude = null;
        Double longitude = null;

        if (sourceIp != null && !sourceIp.isEmpty()) {
            try {
                InetAddress addr = InetAddress.getByName(sourceIp);
                CityResponse resp = reader.city(addr);
                if (resp.getCountry() != null) {
                    countryCode = resp.getCountry().getIsoCode();
                    countryName = resp.getCountry().getName();
                }
                if (resp.getCity() != null) {
                    city = resp.getCity().getName();
                }
                if (resp.getLocation() != null) {
                    latitude = resp.getLocation().getLatitude();
                    longitude = resp.getLocation().getLongitude();
                }
            } catch (AddressNotFoundException e) {
                // IP not in the DB — leave GeoIP fields null.
            } catch (Exception e) {
                LOG.debug("GeoIP lookup failed for {}: {}", sourceIp, e.toString());
            }
        }

        // Output schema = input schema + 5 GeoIP fields appended, in order.
        // Positional build: forward all input values, then the 5 geo values.
        Row.Builder b = Row.withSchema(outputSchema);
        for (Schema.Field f : input.getSchema().getFields()) {
            b.addValue(input.getValue(f.getName()));
        }
        Row output = b
                .addValue(countryCode)
                .addValue(countryName)
                .addValue(city)
                .addValue(latitude)
                .addValue(longitude)
                .build();
        out.output(output);
    }

    @Teardown
    public void teardown() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (mmdbLocalPath != null) {
            Files.deleteIfExists(mmdbLocalPath);
            mmdbLocalPath = null;
        }
    }
}