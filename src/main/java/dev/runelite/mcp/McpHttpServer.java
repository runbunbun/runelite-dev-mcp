package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.runelite.mcp.api.WorldReader;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Lightweight MCP protocol server using JDK HttpServer.
 * Implements JSON-RPC 2.0 with SSE notifications for tool discovery,
 * plus REST API endpoints at /api/* for the web control panel.
 * Wire-compatible with the mcp-middleware upstream protocol.
 */
public class McpHttpServer {

    private static final Logger log = Logger.getLogger(McpHttpServer.class.getName());

    private final int port;
    private final Client client;
    private final ClientThread clientThread;
    private final WorldReader world;
    private final StateBuffer stateBuffer;
    private final ActionLog actionLog;
    private final McpToolHandler toolHandler;
    private final McpRestHandler restHandler;
    private HttpServer server;
    private final List<OutputStream> sseClients = new CopyOnWriteArrayList<>();
    private final List<OutputStream> getStreamClients = new CopyOnWriteArrayList<>();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public McpHttpServer(int port, Client client, ClientThread clientThread,
                         WorldReader world, StateBuffer stateBuffer, ActionLog actionLog, DrawManager drawManager) {
        this.port = port;
        this.client = client;
        this.clientThread = clientThread;
        this.world = world;
        this.stateBuffer = stateBuffer;
        this.actionLog = actionLog;
        this.toolHandler = new McpToolHandler(client, clientThread, world, stateBuffer, actionLog, drawManager);
        this.restHandler = new McpRestHandler(client, clientThread);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // REST API (longest prefix match takes precedence over "/" fallback)
        server.createContext("/api/", restHandler::handle);

        // MCP JSON-RPC
        server.createContext("/mcp", this::handleMcp);
        server.createContext("/", this::handleMcp);
        server.createContext("/sse", this::handleSse);
        server.createContext("/mcp/sse", this::handleSse);
        server.createContext("/health", this::handleHealth);

        server.start();
        log.info("HTTP server listening on port " + port + " (MCP + REST API)");
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        for (OutputStream os : sseClients) {
            try { os.close(); } catch (IOException ignored) {}
        }
        sseClients.clear();
        for (OutputStream os : getStreamClients) {
            try { os.close(); } catch (IOException ignored) {}
        }
        getStreamClients.clear();
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("status", "ok");
        sendJson(ex, 200, o.toString());
    }

    private void handleMcp(HttpExchange ex) throws IOException {
        String httpMethod = ex.getRequestMethod();
        String accept = ex.getRequestHeaders().getFirst("Accept");
        String sidIn = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        String proto = ex.getRequestHeaders().getFirst("Mcp-Protocol-Version");
        log.info("MCP " + httpMethod + " path=" + ex.getRequestURI()
            + " accept=" + accept + " sid=" + sidIn + " proto=" + proto);

        applyCors(ex);

        if ("OPTIONS".equals(httpMethod)) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        // Streamable HTTP: GET opens a server→client SSE stream
        if ("GET".equals(httpMethod)) {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.getResponseHeaders().add("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            getStreamClients.add(os);
            try {
                os.write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException ignored) {}
            // Keep open — closed on shutdown or client disconnect
            return;
        }

        // Streamable HTTP: optional session termination
        if ("DELETE".equals(httpMethod)) {
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (sid != null) sessions.remove(sid);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        if (!"POST".equals(httpMethod)) {
            sendJson(ex, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String body;
        try (InputStream is = ex.getRequestBody();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            body = sb.toString();
        }

        String id = parseStringField(body, "id");
        String method = parseStringField(body, "method");
        log.info("MCP POST body method=" + method + " id=" + id + " body=" + body);

        // Mint a session id on initialize so streamable-HTTP clients can use it on later requests
        if ("initialize".equals(method)) {
            String sid = UUID.randomUUID().toString();
            sessions.put(sid, System.currentTimeMillis());
            ex.getResponseHeaders().add("Mcp-Session-Id", sid);
        }

        String result;
        switch (method != null ? method : "") {
            case "initialize":
                result = handleInitialize(id);
                break;
            case "ping":
                result = jsonRpcResult(id, new JsonObject());
                break;
            case "tools/list":
                result = handleToolsList(id);
                break;
            case "tools/call":
                result = handleToolsCall(id, body);
                break;
            case "resources/list":
                result = handleResourcesList(id);
                break;
            case "resources/read":
                result = handleResourcesRead(id, body);
                break;
            case "notifications/initialized":
            case "notifications/cancelled":
                result = null; // notifications → 202, no body
                break;
            default:
                if (method != null && method.startsWith("notifications/")) {
                    result = null;
                } else {
                    result = jsonRpcError(id, -32601, "Method not found: " + method);
                }
        }

        // JSON-RPC notifications get 202 Accepted with no body (per streamable-HTTP spec)
        if (result == null) {
            log.info("MCP POST response=202 (notification)");
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }

        log.info("MCP POST response=200 body=" + (result.length() > 500 ? result.substring(0, 500) + "..." : result));
        sendJson(ex, 200, result);
    }

    private void handleSse(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        applyCors(ex);
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        sseClients.add(os);

        // Send endpoint event
        String endpoint = "event: endpoint\ndata: http://localhost:" + port + "/mcp\n\n";
        os.write(endpoint.getBytes(StandardCharsets.UTF_8));
        os.flush();

        // Keep connection open — will be closed when plugin shuts down
    }

    // ========== JSON-RPC Method Handlers ==========

    private String handleInitialize(String id) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2025-03-26");
        JsonObject server = new JsonObject();
        server.addProperty("name", "osrs-game-server");
        server.addProperty("version", "2.0.0");
        result.add("serverInfo", server);
        JsonObject caps = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", true);
        caps.add("tools", tools);
        JsonObject resources = new JsonObject();
        resources.addProperty("subscribe", false);
        resources.addProperty("listChanged", false);
        caps.add("resources", resources);
        result.add("capabilities", caps);
        return jsonRpcResult(id, result);
    }

    private String handleToolsList(String id) {
        JsonObject result = new JsonObject();
        result.add("tools", toolHandler.getToolSchemas());
        return jsonRpcResult(id, result);
    }

    private String handleToolsCall(String id, String body) {
        String paramsStr = extractObject(body, "params");
        if (paramsStr == null) return jsonRpcError(id, -32603, "Missing params");

        String toolName = parseStringField(paramsStr, "name");
        String argsStr = extractObject(paramsStr, "arguments");
        if (toolName == null) return jsonRpcError(id, -32603, "Missing tool name");

        JsonObject content = new JsonObject();
        try {
            String toolResult = toolHandler.handleToolCall(toolName, argsStr != null ? argsStr : "{}");
            JsonObject item = new JsonObject();
            if ("screenshot".equals(toolName) && !toolResult.startsWith("Error")) {
                // Screenshot returns raw base64 PNG; wrap as MCP image content for inline render.
                item.addProperty("type", "image");
                item.addProperty("data", toolResult);
                item.addProperty("mimeType", "image/png");
            } else {
                item.addProperty("type", "text");
                item.addProperty("text", toolResult);
            }
            JsonArray arr = new JsonArray();
            arr.add(item);
            content.add("content", arr);
            content.addProperty("isError", false);
        } catch (Exception e) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "text");
            item.addProperty("text", e.getMessage() != null ? e.getMessage() : "Unknown error");
            JsonArray arr = new JsonArray();
            arr.add(item);
            content.add("content", arr);
            content.addProperty("isError", true);
        }
        return jsonRpcResult(id, content);
    }

    private String handleResourcesList(String id) {
        JsonObject result = new JsonObject();
        result.add("resources", new JsonArray());
        return jsonRpcResult(id, result);
    }

    private String handleResourcesRead(String id, String body) {
        JsonObject result = new JsonObject();
        result.add("contents", new JsonArray());
        return jsonRpcResult(id, result);
    }

    // ========== JSON Utilities ==========

    /** Build a JSON-RPC 2.0 success envelope around {@code result}. */
    static String jsonRpcResult(String id, JsonElement result) {
        JsonObject env = new JsonObject();
        env.addProperty("jsonrpc", "2.0");
        env.add("id", parseId(id));
        env.add("result", result);
        return env.toString();
    }

    /** Build a JSON-RPC 2.0 error envelope. */
    static String jsonRpcError(String id, int code, String message) {
        JsonObject env = new JsonObject();
        env.addProperty("jsonrpc", "2.0");
        env.add("id", parseId(id));
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message != null ? message : "");
        env.add("error", err);
        return env.toString();
    }

    /**
     * JSON-RPC ids may be numbers or strings. {@link #parseStringField} returns whatever
     * raw substring it found, so we try numeric first and fall back to string. Missing id
     * becomes JSON {@code null} per the spec.
     */
    static JsonElement parseId(String id) {
        if (id == null) return JsonNull.INSTANCE;
        try { return new JsonPrimitive(Long.parseLong(id)); }
        catch (NumberFormatException ignored) {}
        try { return new JsonPrimitive(Double.parseDouble(id)); }
        catch (NumberFormatException ignored) {}
        return new JsonPrimitive(id);
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        applyCors(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Echo Origin back only when on the allow-list. Native MCP clients (Claude Desktop,
     * Claude Code CLI, curl) send no Origin header and aren't subject to CORS at all —
     * so a missing Origin is fine. A present-but-unrecognized Origin (a random webpage
     * the user visited) gets no CORS headers, and the browser then blocks the response.
     */
    static void applyCors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        if (!isOriginAllowed(origin)) return;
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
        ex.getResponseHeaders().add("Vary", "Origin");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers",
            "Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version");
        ex.getResponseHeaders().add("Access-Control-Expose-Headers", "Mcp-Session-Id");
    }

    private static boolean isOriginAllowed(String origin) {
        if (origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1")) return true;
        if (origin.startsWith("https://localhost") || origin.startsWith("https://127.0.0.1")) return true;
        if (origin.startsWith("vscode-webview://")) return true;
        return false;
    }

    static String parseStringField(String json, String field) {
        // Handles both "field":"value" and "field":123
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;

        if (json.charAt(idx) == '"') {
            // String value
            idx++;
            int end = json.indexOf('"', idx);
            if (end < 0) return null;
            return json.substring(idx, end);
        } else if (json.charAt(idx) == 'n') {
            return null; // null
        } else {
            // Number or other literal
            int end = idx;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != ' ') end++;
            return json.substring(idx, end);
        }
    }

    /** Extract a nested JSON object value for a given key. Handles brace nesting. */
    static String extractObject(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length() || json.charAt(idx) != '{') return null;

        int depth = 0;
        int start = idx;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

}
