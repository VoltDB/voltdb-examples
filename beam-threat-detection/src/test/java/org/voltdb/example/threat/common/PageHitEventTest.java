/* SPDX-License-Identifier: MIT */
package org.voltdb.example.threat.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PageHitEventTest {

    @Test
    public void jsonRoundTripPreservesFields() throws IOException {
        PageHitEvent original = new PageHitEvent(
                "203.0.113.42",
                "/products/42",
                Instant.parse("2026-09-28T14:00:00Z"));

        String json = original.toJson();
        PageHitEvent decoded = PageHitEvent.fromJson(json);

        assertEquals(original, decoded);
    }

    @Test
    public void jsonUsesIso8601Timestamp() {
        PageHitEvent event = new PageHitEvent(
                "203.0.113.42",
                "/login",
                Instant.parse("2026-09-28T14:00:00Z"));

        String json = event.toJson();

        assertTrue(json.contains("\"timestamp\":\"2026-09-28T14:00:00Z\""),
                "expected ISO-8601 timestamp in JSON, got: " + json);
        assertTrue(json.contains("\"source_ip\":\"203.0.113.42\""));
        assertTrue(json.contains("\"page_url\":\"/login\""));
    }

    @Test
    public void fromJsonAcceptsValidPayload() throws IOException {
        String json = "{\"source_ip\":\"10.0.0.1\","
                + "\"page_url\":\"/help\","
                + "\"timestamp\":\"2026-09-28T14:00:00Z\"}";

        PageHitEvent event = PageHitEvent.fromJson(json);

        assertNotNull(event);
        assertEquals("10.0.0.1", event.getSourceIp());
        assertEquals("/help", event.getPageUrl());
        assertEquals(Instant.parse("2026-09-28T14:00:00Z"), event.getTimestamp());
    }

    @Test
    public void fromJsonRejectsMalformedPayload() {
        assertThrows(IOException.class, () -> PageHitEvent.fromJson("not-json"));
    }
}