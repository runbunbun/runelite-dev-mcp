package dev.runelite.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the JSON string escape behavior used to embed tool descriptions, error
 * messages, and tool results into hand-built JSON-RPC envelopes. A regression
 * here breaks tools/list and silently corrupts tool responses — exactly the bug
 * that took down MCP tool discovery in the 0.1.0 development cycle.
 */
class EscapeJsonStringTest {

    @Test
    void nullBecomesEmpty() {
        assertEquals("", McpHttpServer.escapeJsonString(null));
    }

    @Test
    void emptyStringPassesThrough() {
        assertEquals("", McpHttpServer.escapeJsonString(""));
    }

    @Test
    void plainAsciiUnchanged() {
        assertEquals("hello world", McpHttpServer.escapeJsonString("hello world"));
    }

    @Test
    void doubleQuoteIsEscaped() {
        assertEquals("a\\\"b", McpHttpServer.escapeJsonString("a\"b"));
    }

    @Test
    void backslashIsEscapedBeforeQuoteHandling() {
        // Order matters: \ must be doubled before " is prefixed with \, otherwise
        // an input like a\" produces a\\\" instead of a\\\\\".
        assertEquals("a\\\\\\\"b", McpHttpServer.escapeJsonString("a\\\"b"));
    }

    @Test
    void newlineCarriageReturnTabAreEscaped() {
        assertEquals("a\\nb", McpHttpServer.escapeJsonString("a\nb"));
        assertEquals("a\\rb", McpHttpServer.escapeJsonString("a\rb"));
        assertEquals("a\\tb", McpHttpServer.escapeJsonString("a\tb"));
    }

    @Test
    void toolDescriptionWithQuotesRoundTripsThroughJsonRpc() {
        // The actual bug: tool descriptions like 'Tile filter: "x,y,plane"' put
        // raw quotes in the tools/list response, breaking the JSON envelope.
        String desc = "Tile filter: \"x,y,plane\"";
        String escaped = McpHttpServer.escapeJsonString(desc);
        assertEquals("Tile filter: \\\"x,y,plane\\\"", escaped);
    }
}
