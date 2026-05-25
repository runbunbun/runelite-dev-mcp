package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.runelite.mcp.ActionLog.ActionRecord;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders {@code actions} tool queries over an {@link ActionLog}.
 *
 * <p>Returns the most-recent {@code t} action records (newest-last) optionally filtered by
 * option substring, target substring, opcode CSV, identifier CSV, and a "since tick" lower bound.
 */
public class ActionsQueryHandler {

    private static final int DEFAULT_N = 50;

    private final ActionLog log;
    private final int currentTick;

    public ActionsQueryHandler(ActionLog log, int currentTick) {
        this.log = log;
        this.currentTick = currentTick;
    }

    public String handle(String args) {
        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("gameTick", currentTick);
        root.add("_meta", meta);

        if (log == null) {
            root.addProperty("error", "Action log not initialized");
            return root.toString();
        }

        int n = clampPositive(parseInt(args, "t", DEFAULT_N), 1, log.capacity());
        String optionFilter = lowerOrNull(McpHttpServer.parseStringField(args, "option"));
        String targetFilter = lowerOrNull(McpHttpServer.parseStringField(args, "target"));
        Set<Integer> opcodes = parseIntCsv(args, "opcodes");
        Set<Integer> ids = parseIntCsv(args, "ids");
        int since = parseInt(args, "since", Integer.MIN_VALUE);

        // Fetch a generous prefix; filtering may reduce the count, so over-fetch then trim.
        List<ActionRecord> recent = log.getLastN(Math.min(log.filled(), Math.max(n * 4, n)));

        JsonArray arr = new JsonArray();
        int included = 0;
        // walk newest-first so we cap at n filtered records, then reverse to chronological
        java.util.Deque<JsonObject> stack = new java.util.ArrayDeque<>();
        for (int i = recent.size() - 1; i >= 0 && included < n; i--) {
            ActionRecord r = recent.get(i);
            if (r == null) continue;
            if (r.tick < since) continue;
            if (optionFilter != null && !containsCi(r.option, optionFilter)) continue;
            if (targetFilter != null && !containsCi(r.target, targetFilter)) continue;
            if (opcodes != null && !opcodes.contains(r.opcodeId)) continue;
            if (ids != null && !ids.contains(r.identifier)) continue;
            stack.push(actionJson(r));
            included++;
        }
        while (!stack.isEmpty()) arr.add(stack.pop()); // pop in oldest-first order

        root.addProperty("capacity", log.capacity());
        root.addProperty("filled", log.filled());
        root.addProperty("returned", arr.size());
        root.add("actions", arr);
        return root.toString();
    }

    private static JsonObject actionJson(ActionRecord r) {
        JsonObject o = new JsonObject();
        o.addProperty("tick", r.tick);
        o.addProperty("timestampMs", r.timestampMs);
        o.addProperty("option", r.option);
        o.addProperty("target", r.target);
        o.addProperty("menuAction", r.menuActionName);
        o.addProperty("opcodeId", r.opcodeId);
        o.addProperty("identifier", r.identifier);
        o.addProperty("param0", r.param0);
        o.addProperty("param1", r.param1);
        if (r.itemId != -1) o.addProperty("itemId", r.itemId);
        // Widget id breakdown when param1 looks like a packed widget reference
        if ((r.param1 >>> 16) > 0 && (r.param1 >>> 16) < 4096) {
            JsonObject w = new JsonObject();
            w.addProperty("group", r.param1 >>> 16);
            w.addProperty("child", r.param1 & 0xFFFF);
            o.add("widget", w);
        }
        return o;
    }

    private static int parseInt(String args, String field, int def) {
        String raw = McpHttpServer.parseStringField(args, field);
        if (raw == null) return def;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return def; }
    }

    private static int clampPositive(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String lowerOrNull(String s) {
        if (s == null) return null;
        String stripped = s.replace("\"", "").trim();
        return stripped.isEmpty() ? null : stripped.toLowerCase(Locale.ROOT);
    }

    private static boolean containsCi(String haystack, String needleLower) {
        if (haystack == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static Set<Integer> parseIntCsv(String args, String field) {
        String raw = McpHttpServer.parseStringField(args, field);
        if (raw == null) return null;
        String stripped = raw.replace("[", "").replace("]", "").replace("\"", "").trim();
        if (stripped.isEmpty()) return null;
        Set<Integer> out = new HashSet<>();
        for (String tok : stripped.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try { out.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
        }
        return out.isEmpty() ? null : out;
    }
}
