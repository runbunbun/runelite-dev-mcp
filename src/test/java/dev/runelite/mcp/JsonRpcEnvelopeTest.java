package dev.runelite.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON-RPC 2.0 envelope shape produced by {@link McpHttpServer}.
 *
 * <p>The id parsing is the load-bearing piece: {@link McpHttpServer#parseStringField}
 * hands us a raw substring that may be numeric or string, and the spec requires the
 * outgoing id to match the request type. An earlier hand-built envelope emitted unquoted
 * string ids — a latent bug that this test would have caught.
 */
class JsonRpcEnvelopeTest {

    @Test
    void parseIdNumericReturnsNumber() {
        JsonElement id = McpHttpServer.parseId("42");
        assertTrue(id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber());
        assertEquals(42L, id.getAsLong());
    }

    @Test
    void parseIdNonNumericReturnsString() {
        JsonElement id = McpHttpServer.parseId("abc-123");
        assertTrue(id.isJsonPrimitive() && id.getAsJsonPrimitive().isString());
        assertEquals("abc-123", id.getAsString());
    }

    @Test
    void parseIdNullReturnsJsonNull() {
        assertEquals(JsonNull.INSTANCE, McpHttpServer.parseId(null));
    }

    @Test
    void resultEnvelopeWrapsPayloadVerbatim() {
        JsonObject inner = new JsonObject();
        inner.addProperty("foo", "bar");
        JsonObject env = JsonParser.parseString(
            McpHttpServer.jsonRpcResult("7", inner)).getAsJsonObject();
        assertEquals("2.0", env.get("jsonrpc").getAsString());
        assertEquals(7L, env.get("id").getAsLong());
        assertEquals("bar", env.getAsJsonObject("result").get("foo").getAsString());
        assertFalse(env.has("error"));
    }

    @Test
    void errorEnvelopeCarriesCodeAndMessage() {
        JsonObject env = JsonParser.parseString(
            McpHttpServer.jsonRpcError("9", -32601, "Method not found: foo")).getAsJsonObject();
        assertEquals(9L, env.get("id").getAsLong());
        JsonObject err = env.getAsJsonObject("error");
        assertEquals(-32601, err.get("code").getAsInt());
        assertEquals("Method not found: foo", err.get("message").getAsString());
        assertFalse(env.has("result"));
    }

    @Test
    void errorWithSpecialCharsIsEscapedByGson() {
        // Pre-refactor, a hand-built envelope leaked unescaped " into the message
        // field. Gson must escape it so the envelope round-trips through a parser.
        JsonObject env = JsonParser.parseString(
            McpHttpServer.jsonRpcError("1", -32602, "Invalid \"quoted\" arg")).getAsJsonObject();
        assertEquals("Invalid \"quoted\" arg", env.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void nullIdSerializesAsJsonNull() {
        JsonObject env = JsonParser.parseString(
            McpHttpServer.jsonRpcResult(null, new JsonObject())).getAsJsonObject();
        assertTrue(env.get("id").isJsonNull());
    }
}
