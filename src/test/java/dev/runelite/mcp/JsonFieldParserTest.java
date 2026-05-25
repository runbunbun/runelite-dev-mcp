package dev.runelite.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins behavior of {@link McpHttpServer#parseStringField}, {@link McpHttpServer#extractObject},
 * and {@link McpHttpServer#hasField}. Replaces the hand-rolled parser whose escaped-quote
 * and nested-brace bugs prompted the gson switch.
 */
class JsonFieldParserTest {

    @Test
    void stringValueComesBackUnquoted() {
        assertEquals("hello", McpHttpServer.parseStringField("{\"x\":\"hello\"}", "x"));
    }

    @Test
    void escapedQuoteInValueIsPreserved() {
        // The old parser stopped at the first " inside the value, returning "Bob \\".
        // Gson correctly unescapes \" to ".
        String json = "{\"target\":\"Bob \\\"the legend\\\" Smith\"}";
        assertEquals("Bob \"the legend\" Smith", McpHttpServer.parseStringField(json, "target"));
    }

    @Test
    void numericValueComesBackAsString() {
        assertEquals("42", McpHttpServer.parseStringField("{\"n\":42}", "n"));
    }

    @Test
    void arrayValueComesBackAsRawJson() {
        // Several callers (BufferQueryHandler.parseCsvLower, parseIdSet) take the result
        // and strip [, ], " before splitting — preserve that contract.
        String raw = McpHttpServer.parseStringField("{\"types\":[\"npc\",\"obj\"]}", "types");
        assertEquals("[\"npc\",\"obj\"]", raw);
    }

    @Test
    void missingFieldReturnsNull() {
        assertNull(McpHttpServer.parseStringField("{\"x\":1}", "y"));
    }

    @Test
    void jsonNullValueReturnsNull() {
        assertNull(McpHttpServer.parseStringField("{\"x\":null}", "x"));
    }

    @Test
    void invalidJsonReturnsNullInsteadOfThrowing() {
        assertNull(McpHttpServer.parseStringField("not json", "anything"));
    }

    @Test
    void extractObjectRoundTripsNestedJson() {
        // Old parser miscounted braces if a string value contained { or }.
        String body = "{\"params\":{\"name\":\"chat\",\"arguments\":{\"text\":\"hi {there}\"}}}";
        String params = McpHttpServer.extractObject(body, "params");
        assertEquals("chat", McpHttpServer.parseStringField(params, "name"));
        String args = McpHttpServer.extractObject(params, "arguments");
        assertEquals("hi {there}", McpHttpServer.parseStringField(args, "text"));
    }

    @Test
    void extractObjectOnNonObjectReturnsNull() {
        assertNull(McpHttpServer.extractObject("{\"x\":\"string\"}", "x"));
    }

    @Test
    void hasFieldDistinguishesMissingFromExplicitNull() {
        // Notification detection hinges on this distinction.
        assertTrue(McpHttpServer.hasField("{\"id\":null}", "id"));
        assertFalse(McpHttpServer.hasField("{}", "id"));
    }
}
