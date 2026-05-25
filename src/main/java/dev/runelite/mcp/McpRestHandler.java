package dev.runelite.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Read-only REST API for the MCP plugin. Exposes a small surface alongside the
 * MCP JSON-RPC server for dashboards and health checks:
 *
 *   GET /api/status              — login state, game tick, uptime
 *   GET /api/debug/bank-widgets  — widget-tree introspection of bank groups (12, 15)
 */
public class McpRestHandler {

    private static final Logger log = Logger.getLogger(McpRestHandler.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Client client;
    private final ClientThread clientThread;
    private final ConfigManager configManager;
    private final long startMs = System.currentTimeMillis();

    public McpRestHandler(Client client, ClientThread clientThread, ConfigManager configManager) {
        this.client = client;
        this.clientThread = clientThread;
        this.configManager = configManager;
    }

    public void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        try {
            if (path.equals("/api/status") && "GET".equals(method)) {
                handleGetStatus(ex);
            } else if (path.equals("/api/debug/bank-widgets") && "GET".equals(method)) {
                handleDebugBankWidgets(ex);
            } else {
                sendJson(ex, 404, errorJson("Not found"));
            }
        } catch (Exception e) {
            log.warning("REST handler error: " + e.getMessage());
            sendJson(ex, 500, errorJson(e.getMessage()));
        }
    }

    private static String errorJson(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message != null ? message : "");
        return o.toString();
    }

    private void handleGetStatus(HttpExchange ex) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("gameTick", client.getTickCount());
        result.addProperty("loginState", client.getGameState().name());
        result.addProperty("uptimeMs", System.currentTimeMillis() - startMs);
        sendJson(ex, 200, GSON.toJson(result));
    }

    private void handleDebugBankWidgets(HttpExchange ex) throws IOException {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try { future.complete(buildBankDebug()); }
            catch (Exception e) { future.complete(new JsonObject()); }
        });
        JsonObject result;
        try { result = future.get(2, TimeUnit.SECONDS); }
        catch (Exception e) { result = new JsonObject(); result.addProperty("error", "timeout"); }
        sendJson(ex, 200, GSON.toJson(result));
    }

    private JsonObject buildBankDebug() {
        JsonObject result = new JsonObject();

        JsonArray childScan = new JsonArray();
        for (int c = 0; c < 60; c++) {
            net.runelite.api.widgets.Widget w = client.getWidget(12, c);
            if (w == null) continue;
            JsonObject wj = new JsonObject();
            wj.addProperty("child", c);
            wj.addProperty("visible", !w.isHidden());
            wj.addProperty("text", w.getText());
            wj.addProperty("itemId", w.getItemId());
            java.awt.Rectangle b = w.getBounds();
            wj.addProperty("bounds", b.x + "," + b.y + "," + b.width + "x" + b.height);

            net.runelite.api.widgets.Widget[] dynChildren = w.getDynamicChildren();
            int dynCount = dynChildren != null ? dynChildren.length : 0;
            wj.addProperty("dynamicChildren", dynCount);

            if (dynCount > 0) {
                int withItems = 0;
                JsonArray sampleItems = new JsonArray();
                for (int i = 0; i < dynChildren.length && sampleItems.size() < 5; i++) {
                    net.runelite.api.widgets.Widget dc = dynChildren[i];
                    if (dc == null) continue;
                    int iid = dc.getItemId();
                    if (iid > 0) {
                        withItems++;
                        JsonObject si = new JsonObject();
                        si.addProperty("idx", i);
                        si.addProperty("itemId", iid);
                        si.addProperty("qty", dc.getItemQuantity());
                        java.awt.Rectangle db = dc.getBounds();
                        si.addProperty("bounds", db.x + "," + db.y + "," + db.width + "x" + db.height);
                        String[] acts = dc.getActions();
                        if (acts != null && acts.length > 0) {
                            JsonArray a = new JsonArray();
                            for (String s : acts) if (s != null) a.add(s);
                            si.add("actions", a);
                        }
                        sampleItems.add(si);
                    }
                }
                wj.addProperty("childrenWithItems", withItems);
                if (sampleItems.size() > 0) wj.add("sampleItems", sampleItems);
            }

            String[] actions = w.getActions();
            if (actions != null) {
                JsonArray acts = new JsonArray();
                for (String a : actions) if (a != null) acts.add(a);
                if (acts.size() > 0) wj.add("actions", acts);
            }

            childScan.add(wj);
        }
        result.add("group12", childScan);

        JsonArray g15Scan = new JsonArray();
        for (int c = 0; c < 10; c++) {
            net.runelite.api.widgets.Widget w = client.getWidget(15, c);
            if (w == null) continue;
            JsonObject wj = new JsonObject();
            wj.addProperty("child", c);
            wj.addProperty("visible", !w.isHidden());
            wj.addProperty("text", w.getText());
            net.runelite.api.widgets.Widget[] dynChildren = w.getDynamicChildren();
            int dynCount = dynChildren != null ? dynChildren.length : 0;
            wj.addProperty("dynamicChildren", dynCount);
            if (dynCount > 0) {
                int withItems = 0;
                JsonArray sampleItems = new JsonArray();
                for (int i = 0; i < dynChildren.length && sampleItems.size() < 3; i++) {
                    net.runelite.api.widgets.Widget dc = dynChildren[i];
                    if (dc == null) continue;
                    int iid = dc.getItemId();
                    if (iid > 0) {
                        withItems++;
                        JsonObject si = new JsonObject();
                        si.addProperty("idx", i);
                        si.addProperty("itemId", iid);
                        si.addProperty("qty", dc.getItemQuantity());
                        sampleItems.add(si);
                    }
                }
                wj.addProperty("childrenWithItems", withItems);
                if (sampleItems.size() > 0) wj.add("sampleItems", sampleItems);
            }
            g15Scan.add(wj);
        }
        result.add("group15", g15Scan);

        return result;
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
