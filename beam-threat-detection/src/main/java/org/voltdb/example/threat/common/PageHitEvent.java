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
package org.voltdb.example.threat.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Event schema for a single page hit — one user request to a public page on the
 * threat-detection demo site. Produced by {@code PageHitGenerator}, published to
 * a Google Cloud PubSub topic, and consumed by the {@code PageHitIngestPipeline}
 * Beam streaming pipeline.
 *
 * <p>The ingest pipeline extracts the {@code sourceIp}'s /24 CIDR subnet and
 * calls VoltDB's {@code RecordSubnetRequest} SP via
 * {@code VoltDbIO.write} (fire-and-forget — the SP's return value is ignored).
 * Each page hit becomes one row in VoltDB's {@code SUBNET_REQUESTS} table with
 * {@code SOURCE_TYPE = 'PAGE'}, feeding the same subnet-rate counter that
 * transactions feed via {@code SOURCE_TYPE = 'TXN'}.
 *
 * <p><b>Wire format:</b> JSON via Jackson. Example message body:
 * <pre>{@code
 * {"source_ip":"203.0.113.42","page_url":"/products/42",
 *  "timestamp":"2026-09-28T14:00:00Z","publish_time":"2026-09-28T14:00:00.123Z"}
 * }</pre>
 * Timestamps are ISO-8601 strings (via {@code JavaTimeModule}) so PubSub UI
 * previews are human-readable.
 *
 * <p>{@code publish_time} is required by the {@code page_hits_raw} BigQuery
 * sink (populated by the PubSub-BQ subscription with {@code --use-table-schema}
 * — the subscription maps JSON fields by name and the table declares the
 * column {@code NOT NULL}). The {@code PageHitsGenerator} stamps it right
 * before publishing. For synthetic / in-process use that does not go through
 * PubSub + BigQuery (e.g. Testcontainer integration tests), the 3-arg
 * constructor leaves it null and that is fine.
 */
public final class PageHitEvent implements Serializable {

    private static final long serialVersionUID = 2L;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @JsonProperty("source_ip")    private String sourceIp;
    @JsonProperty("page_url")     private String pageUrl;
    @JsonProperty("timestamp")    private Instant timestamp;
    @JsonProperty("publish_time") private Instant publishTime;

    public PageHitEvent() {
        // required for Jackson + Beam serialization
    }

    /** Synthetic / in-process constructor — leaves {@code publishTime} null. */
    public PageHitEvent(String sourceIp, String pageUrl, Instant timestamp) {
        this(sourceIp, pageUrl, timestamp, null);
    }

    @JsonCreator
    public PageHitEvent(
            @JsonProperty("source_ip")    String sourceIp,
            @JsonProperty("page_url")     String pageUrl,
            @JsonProperty("timestamp")    Instant timestamp,
            @JsonProperty("publish_time") Instant publishTime) {
        this.sourceIp = sourceIp;
        this.pageUrl = pageUrl;
        this.timestamp = timestamp;
        this.publishTime = publishTime;
    }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Instant getPublishTime() { return publishTime; }
    public void setPublishTime(Instant publishTime) { this.publishTime = publishTime; }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PageHitEvent", e);
        }
    }

    public static PageHitEvent fromJson(String json) throws IOException {
        return MAPPER.readValue(json, PageHitEvent.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageHitEvent)) return false;
        PageHitEvent other = (PageHitEvent) o;
        return Objects.equals(sourceIp, other.sourceIp)
                && Objects.equals(pageUrl, other.pageUrl)
                && Objects.equals(timestamp, other.timestamp)
                && Objects.equals(publishTime, other.publishTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceIp, pageUrl, timestamp, publishTime);
    }

    @Override
    public String toString() {
        return "PageHitEvent{sourceIp=" + sourceIp
                + ", pageUrl=" + pageUrl
                + ", timestamp=" + timestamp
                + ", publishTime=" + publishTime + '}';
    }
}