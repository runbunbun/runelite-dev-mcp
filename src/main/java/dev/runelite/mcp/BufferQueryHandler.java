package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.runelite.mcp.StateBuffer.TickSnapshot;
import dev.runelite.mcp.api.snapshot.GroundItemSnapshot;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.ObjectSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders {@code buffer} tool queries over a {@link StateBuffer}.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code t > 0} — return the full snapshot at exact tick {@code t}.</li>
 *   <li>{@code t < 0} — return the last {@code |t|} ticks of sparse deltas
 *       ({@code added}, {@code removed}, {@code changed}) keyed per entity type.</li>
 * </ul>
 *
 * <p>Filters (all AND'd): {@code types}, {@code names}, {@code ids}, {@code tile}, {@code area}.
 */
public class BufferQueryHandler {

    private static final Set<String> ALL_TYPES = new HashSet<>(Arrays.asList(
        "npc", "obj", "ground", "player", "otherplayer"));

    private final StateBuffer buffer;
    private final int currentTick;

    public BufferQueryHandler(StateBuffer buffer, int currentTick) {
        this.buffer = buffer;
        this.currentTick = currentTick;
    }

    public String handle(String args) {
        if (buffer == null) {
            return "{\"error\":\"State buffer not initialized\"}";
        }

        int t = parseInt(args, "t", -5);
        Filter filter = Filter.parse(args);

        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("gameTick", currentTick);
        root.add("_meta", meta);
        root.addProperty("bufferCapacity", buffer.capacity());
        root.addProperty("bufferFilled", buffer.filled());

        int[] range = buffer.range();
        if (range != null) {
            JsonObject br = new JsonObject();
            br.addProperty("from", range[0]);
            br.addProperty("to", range[1]);
            root.add("bufferRange", br);
        }

        if (filter.hasAny()) root.add("filter", filter.toJson());

        if (t > 0) {
            renderAbsolute(root, t, filter);
        } else {
            int n = Math.max(1, -t);
            renderDeltas(root, n, filter);
        }

        return root.toString();
    }

    // ========== Absolute (positive t) ==========

    private void renderAbsolute(JsonObject root, int tick, Filter filter) {
        TickSnapshot s = buffer.getByTick(tick);
        root.addProperty("type", "absolute");
        root.addProperty("tick", tick);

        if (s == null) {
            root.addProperty("found", false);
            return;
        }
        root.addProperty("found", true);
        root.addProperty("timestampMs", s.timestampMs);

        JsonObject state = new JsonObject();
        if (filter.includesType("player") && s.player != null) {
            state.add("player", playerJson(s.player));
        }
        if (filter.includesType("npc")) {
            JsonArray arr = new JsonArray();
            for (NpcSnapshot n : s.npcs) if (filter.matches(n)) arr.add(npcJson(n));
            state.add("npcs", arr);
        }
        if (filter.includesType("obj")) {
            JsonArray arr = new JsonArray();
            for (ObjectSnapshot o : s.objects) if (filter.matches(o)) arr.add(objJson(o));
            state.add("objects", arr);
        }
        if (filter.includesType("ground")) {
            JsonArray arr = new JsonArray();
            for (GroundItemSnapshot g : s.groundItems) if (filter.matches(g)) arr.add(groundJson(g));
            state.add("groundItems", arr);
        }
        if (filter.includesType("otherplayer")) {
            JsonArray arr = new JsonArray();
            for (PlayerSnapshot p : s.otherPlayers) if (filter.matches(p)) arr.add(playerJson(p));
            state.add("otherPlayers", arr);
        }
        root.add("state", state);
    }

    // ========== Delta (negative t) ==========

    private void renderDeltas(JsonObject root, int n, Filter filter) {
        List<TickSnapshot> last = buffer.getLastN(n + 1); // +1 to get prev for the first delta
        root.addProperty("type", "delta");

        JsonArray ticks = new JsonArray();
        int omitted = 0;
        for (int i = 1; i < last.size(); i++) {
            TickSnapshot prev = last.get(i - 1);
            TickSnapshot curr = last.get(i);
            JsonObject deltas = computeDeltas(prev, curr, filter);
            if (deltas.size() == 0) { omitted++; continue; }
            JsonObject tickObj = new JsonObject();
            tickObj.addProperty("tick", curr.tick);
            tickObj.addProperty("timestampMs", curr.timestampMs);
            tickObj.add("deltas", deltas);
            ticks.add(tickObj);
        }
        // Edge case: only 1 entry available — no prev to diff against
        if (last.size() == 1) {
            TickSnapshot curr = last.get(0);
            JsonObject deltas = computeDeltas(null, curr, filter); // everything is "added"
            if (deltas.size() > 0) {
                JsonObject tickObj = new JsonObject();
                tickObj.addProperty("tick", curr.tick);
                tickObj.addProperty("timestampMs", curr.timestampMs);
                tickObj.add("deltas", deltas);
                ticks.add(tickObj);
            } else {
                omitted++;
            }
        }
        if (omitted > 0) root.addProperty("ticksOmitted", omitted);
        root.add("ticks", ticks);
    }

    private JsonObject computeDeltas(TickSnapshot prev, TickSnapshot curr, Filter filter) {
        JsonObject out = new JsonObject();
        if (filter.includesType("player")) {
            JsonObject pd = playerDelta(prev != null ? prev.player : null, curr.player);
            if (pd != null) out.add("player", pd);
        }
        if (filter.includesType("npc")) {
            JsonObject d = npcDeltas(prev != null ? prev.npcs : null, curr.npcs, filter);
            if (d.size() > 0) out.add("npcs", d);
        }
        if (filter.includesType("obj")) {
            JsonObject d = objectDeltas(prev != null ? prev.objects : null, curr.objects, filter);
            if (d.size() > 0) out.add("objects", d);
        }
        if (filter.includesType("ground")) {
            JsonObject d = groundDeltas(prev != null ? prev.groundItems : null, curr.groundItems, filter);
            if (d.size() > 0) out.add("groundItems", d);
        }
        if (filter.includesType("otherplayer")) {
            JsonObject d = otherPlayerDeltas(prev != null ? prev.otherPlayers : null, curr.otherPlayers, filter);
            if (d.size() > 0) out.add("otherPlayers", d);
        }
        return out;
    }

    // ========== Player delta ==========

    private JsonObject playerDelta(PlayerSnapshot prev, PlayerSnapshot curr) {
        if (curr == null && prev == null) return null;
        if (prev == null) return playerJson(curr);
        if (curr == null) { JsonObject o = new JsonObject(); o.addProperty("removed", true); return o; }
        JsonObject changed = new JsonObject();
        diffPos(changed, "pos", prev.worldX, prev.worldY, prev.plane, curr.worldX, curr.worldY, curr.plane);
        diffInt(changed, "animation", prev.animation, curr.animation);
        diffInt(changed, "currentHealth", prev.currentHealth, curr.currentHealth);
        diffInt(changed, "prayerPoints", prev.prayerPoints, curr.prayerPoints);
        diffInt(changed, "runEnergy", prev.runEnergy, curr.runEnergy);
        diffInt(changed, "specialAttackEnergy", prev.specialAttackEnergy, curr.specialAttackEnergy);
        diffBool(changed, "runEnabled", prev.runEnabled, curr.runEnabled);
        diffBool(changed, "interacting", prev.interacting, curr.interacting);
        diffInt(changed, "interactingIndex", prev.interactingIndex, curr.interactingIndex);
        diffStr(changed, "interactingName", prev.interactingName, curr.interactingName);
        diffBool(changed, "poisoned", prev.poisoned, curr.poisoned);
        diffBool(changed, "venomed", prev.venomed, curr.venomed);
        return changed.size() > 0 ? changed : null;
    }

    // ========== NPC deltas ==========

    private JsonObject npcDeltas(List<NpcSnapshot> prev, List<NpcSnapshot> curr, Filter filter) {
        Map<Integer, NpcSnapshot> prevMap = new HashMap<>();
        if (prev != null) for (NpcSnapshot n : prev) if (filter.matches(n)) prevMap.put(n.index, n);
        Map<Integer, NpcSnapshot> currMap = new HashMap<>();
        for (NpcSnapshot n : curr) if (filter.matches(n)) currMap.put(n.index, n);

        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        JsonObject changed = new JsonObject();

        for (Map.Entry<Integer, NpcSnapshot> e : currMap.entrySet()) {
            if (!prevMap.containsKey(e.getKey())) added.add(npcJson(e.getValue()));
            else {
                JsonObject c = npcDelta(prevMap.get(e.getKey()), e.getValue());
                if (c.size() > 0) changed.add(String.valueOf(e.getKey()), c);
            }
        }
        for (Integer idx : prevMap.keySet()) if (!currMap.containsKey(idx)) removed.add(idx);

        JsonObject out = new JsonObject();
        if (added.size() > 0) out.add("added", added);
        if (removed.size() > 0) out.add("removed", removed);
        if (changed.size() > 0) out.add("changed", changed);
        return out;
    }

    private JsonObject npcDelta(NpcSnapshot prev, NpcSnapshot curr) {
        JsonObject c = new JsonObject();
        diffPos(c, "pos", prev.worldX, prev.worldY, prev.plane, curr.worldX, curr.worldY, curr.plane);
        diffInt(c, "animation", prev.animation, curr.animation);
        diffInt(c, "healthRatio", prev.healthRatio, curr.healthRatio);
        diffBool(c, "interacting", prev.interacting, curr.interacting);
        diffInt(c, "interactingIndex", prev.interactingIndex, curr.interactingIndex);
        diffStr(c, "interactingName", prev.interactingName, curr.interactingName);
        diffInt(c, "overheadIcon", prev.overheadIcon, curr.overheadIcon);
        diffBool(c, "inCombat", prev.inCombat, curr.inCombat);
        return c;
    }

    // ========== Object deltas ==========

    private JsonObject objectDeltas(List<ObjectSnapshot> prev, List<ObjectSnapshot> curr, Filter filter) {
        Map<String, ObjectSnapshot> prevMap = new HashMap<>();
        if (prev != null) for (ObjectSnapshot o : prev) if (filter.matches(o)) prevMap.put(objectKey(o), o);
        Map<String, ObjectSnapshot> currMap = new HashMap<>();
        for (ObjectSnapshot o : curr) if (filter.matches(o)) currMap.put(objectKey(o), o);

        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        for (Map.Entry<String, ObjectSnapshot> e : currMap.entrySet()) {
            if (!prevMap.containsKey(e.getKey())) added.add(objJson(e.getValue()));
        }
        for (Map.Entry<String, ObjectSnapshot> e : prevMap.entrySet()) {
            if (!currMap.containsKey(e.getKey())) removed.add(objJson(e.getValue()));
        }
        JsonObject out = new JsonObject();
        if (added.size() > 0) out.add("added", added);
        if (removed.size() > 0) out.add("removed", removed);
        return out;
    }

    private static String objectKey(ObjectSnapshot o) {
        return o.id + "@" + o.worldX + "," + o.worldY + "," + o.plane;
    }

    // ========== Ground item deltas ==========

    private JsonObject groundDeltas(List<GroundItemSnapshot> prev, List<GroundItemSnapshot> curr, Filter filter) {
        Map<String, GroundItemSnapshot> prevMap = new HashMap<>();
        if (prev != null) for (GroundItemSnapshot g : prev) if (filter.matches(g)) prevMap.put(groundKey(g), g);
        Map<String, GroundItemSnapshot> currMap = new HashMap<>();
        for (GroundItemSnapshot g : curr) if (filter.matches(g)) currMap.put(groundKey(g), g);

        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        JsonObject changed = new JsonObject();
        for (Map.Entry<String, GroundItemSnapshot> e : currMap.entrySet()) {
            GroundItemSnapshot prevG = prevMap.get(e.getKey());
            if (prevG == null) added.add(groundJson(e.getValue()));
            else if (prevG.quantity != e.getValue().quantity) {
                JsonObject c = new JsonObject();
                JsonArray qa = new JsonArray();
                qa.add(prevG.quantity);
                qa.add(e.getValue().quantity);
                c.add("quantity", qa);
                changed.add(e.getKey(), c);
            }
        }
        for (Map.Entry<String, GroundItemSnapshot> e : prevMap.entrySet()) {
            if (!currMap.containsKey(e.getKey())) removed.add(groundJson(e.getValue()));
        }
        JsonObject out = new JsonObject();
        if (added.size() > 0) out.add("added", added);
        if (removed.size() > 0) out.add("removed", removed);
        if (changed.size() > 0) out.add("changed", changed);
        return out;
    }

    private static String groundKey(GroundItemSnapshot g) {
        return g.id + "@" + g.worldX + "," + g.worldY + "," + g.plane;
    }

    // ========== Other-player deltas (PlayerSnapshot keyed by name) ==========

    private JsonObject otherPlayerDeltas(List<PlayerSnapshot> prev, List<PlayerSnapshot> curr, Filter filter) {
        Map<String, PlayerSnapshot> prevMap = new HashMap<>();
        if (prev != null) for (PlayerSnapshot p : prev) if (filter.matches(p) && p.name != null) prevMap.put(p.name, p);
        Map<String, PlayerSnapshot> currMap = new HashMap<>();
        for (PlayerSnapshot p : curr) if (filter.matches(p) && p.name != null) currMap.put(p.name, p);

        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        JsonObject changed = new JsonObject();
        for (Map.Entry<String, PlayerSnapshot> e : currMap.entrySet()) {
            PlayerSnapshot prevP = prevMap.get(e.getKey());
            if (prevP == null) added.add(playerJson(e.getValue()));
            else {
                JsonObject c = new JsonObject();
                diffPos(c, "pos", prevP.worldX, prevP.worldY, prevP.plane, e.getValue().worldX, e.getValue().worldY, e.getValue().plane);
                diffInt(c, "animation", prevP.animation, e.getValue().animation);
                diffInt(c, "currentHealth", prevP.currentHealth, e.getValue().currentHealth);
                diffBool(c, "interacting", prevP.interacting, e.getValue().interacting);
                diffStr(c, "interactingName", prevP.interactingName, e.getValue().interactingName);
                if (c.size() > 0) changed.add(e.getKey(), c);
            }
        }
        for (Map.Entry<String, PlayerSnapshot> e : prevMap.entrySet()) {
            if (!currMap.containsKey(e.getKey())) removed.add(e.getKey());
        }
        JsonObject out = new JsonObject();
        if (added.size() > 0) out.add("added", added);
        if (removed.size() > 0) out.add("removed", removed);
        if (changed.size() > 0) out.add("changed", changed);
        return out;
    }

    // ========== JSON entity renderers ==========

    private static JsonObject playerJson(PlayerSnapshot p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name);
        JsonArray pos = new JsonArray();
        pos.add(p.worldX); pos.add(p.worldY); pos.add(p.plane);
        o.add("pos", pos);
        o.addProperty("animation", p.animation);
        o.addProperty("currentHealth", p.currentHealth);
        o.addProperty("maxHealth", p.maxHealth);
        o.addProperty("prayerPoints", p.prayerPoints);
        o.addProperty("runEnergy", p.runEnergy);
        o.addProperty("runEnabled", p.runEnabled);
        o.addProperty("interacting", p.interacting);
        if (p.interactingName != null) o.addProperty("interactingName", p.interactingName);
        return o;
    }

    private static JsonObject npcJson(NpcSnapshot n) {
        JsonObject o = new JsonObject();
        o.addProperty("index", n.index);
        o.addProperty("id", n.id);
        if (n.name != null) o.addProperty("name", n.name);
        JsonArray pos = new JsonArray();
        pos.add(n.worldX); pos.add(n.worldY); pos.add(n.plane);
        o.add("pos", pos);
        o.addProperty("animation", n.animation);
        o.addProperty("healthRatio", n.healthRatio);
        o.addProperty("healthScale", n.healthScale);
        if (n.interacting) {
            o.addProperty("interactingIndex", n.interactingIndex);
            if (n.interactingName != null) o.addProperty("interactingName", n.interactingName);
        }
        return o;
    }

    private static JsonObject objJson(ObjectSnapshot ob) {
        JsonObject o = new JsonObject();
        o.addProperty("id", ob.id);
        if (ob.name != null) o.addProperty("name", ob.name);
        JsonArray pos = new JsonArray();
        pos.add(ob.worldX); pos.add(ob.worldY); pos.add(ob.plane);
        o.add("pos", pos);
        if (ob.objectType != null) o.addProperty("type", ob.objectType);
        return o;
    }

    private static JsonObject groundJson(GroundItemSnapshot g) {
        JsonObject o = new JsonObject();
        o.addProperty("id", g.id);
        if (g.name != null) o.addProperty("name", g.name);
        o.addProperty("quantity", g.quantity);
        JsonArray pos = new JsonArray();
        pos.add(g.worldX); pos.add(g.worldY); pos.add(g.plane);
        o.add("pos", pos);
        return o;
    }

    // ========== Diff helpers ==========

    private static void diffInt(JsonObject out, String key, int a, int b) {
        if (a == b) return;
        JsonArray pair = new JsonArray();
        pair.add(a); pair.add(b);
        out.add(key, pair);
    }

    private static void diffBool(JsonObject out, String key, boolean a, boolean b) {
        if (a == b) return;
        JsonArray pair = new JsonArray();
        pair.add(a); pair.add(b);
        out.add(key, pair);
    }

    private static void diffStr(JsonObject out, String key, String a, String b) {
        if ((a == null && b == null) || (a != null && a.equals(b))) return;
        JsonArray pair = new JsonArray();
        if (a != null) pair.add(a); else pair.add((String) null);
        if (b != null) pair.add(b); else pair.add((String) null);
        out.add(key, pair);
    }

    private static void diffPos(JsonObject out, String key,
                                int ax, int ay, int ap, int bx, int by, int bp) {
        if (ax == bx && ay == by && ap == bp) return;
        JsonArray pair = new JsonArray();
        JsonArray before = new JsonArray();
        before.add(ax); before.add(ay); before.add(ap);
        JsonArray after = new JsonArray();
        after.add(bx); after.add(by); after.add(bp);
        pair.add(before); pair.add(after);
        out.add(key, pair);
    }

    // ========== Arg parsing ==========

    private static int parseInt(String args, String field, int def) {
        String raw = McpHttpServer.parseStringField(args, field);
        if (raw == null) return def;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return def; }
    }

    /** Bundles all filter predicates parsed from tool args. */
    static final class Filter {
        final Set<String> types;          // lowercase entity type tokens
        final Set<String> names;          // lowercase names
        final Set<Integer> ids;
        final int[] tile;                 // [x, y, plane] or null
        final int[] area;                 // [x1, y1, x2, y2, plane] or null

        private Filter(Set<String> types, Set<String> names, Set<Integer> ids, int[] tile, int[] area) {
            this.types = types; this.names = names; this.ids = ids; this.tile = tile; this.area = area;
        }

        static Filter parse(String args) {
            Set<String> types = parseCsvLower(args, "types");
            if (types == null) types = ALL_TYPES;
            return new Filter(types, parseCsvLower(args, "names"), parseIdSet(args), parseTile(args), parseArea(args));
        }

        boolean hasAny() {
            return !types.equals(ALL_TYPES) || names != null || ids != null || tile != null || area != null;
        }

        boolean includesType(String t) { return types.contains(t); }

        boolean matches(NpcSnapshot n) {
            return matchName(n.name) && matchId(n.id) && matchPos(n.worldX, n.worldY, n.plane);
        }
        boolean matches(ObjectSnapshot o) {
            return matchName(o.name) && matchId(o.id) && matchPos(o.worldX, o.worldY, o.plane);
        }
        boolean matches(GroundItemSnapshot g) {
            return matchName(g.name) && matchId(g.id) && matchPos(g.worldX, g.worldY, g.plane);
        }
        boolean matches(PlayerSnapshot p) {
            return matchName(p.name) && matchPos(p.worldX, p.worldY, p.plane);
        }

        private boolean matchName(String name) {
            if (names == null) return true;
            return name != null && names.contains(name.toLowerCase(Locale.ROOT));
        }
        private boolean matchId(int id) {
            if (ids == null) return true;
            return ids.contains(id);
        }
        private boolean matchPos(int x, int y, int plane) {
            if (tile != null) {
                if (x != tile[0] || y != tile[1] || plane != tile[2]) return false;
            }
            if (area != null) {
                int xmin = Math.min(area[0], area[2]);
                int xmax = Math.max(area[0], area[2]);
                int ymin = Math.min(area[1], area[3]);
                int ymax = Math.max(area[1], area[3]);
                if (x < xmin || x > xmax || y < ymin || y > ymax || plane != area[4]) return false;
            }
            return true;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonArray ta = new JsonArray();
            for (String t : types) ta.add(t);
            o.add("types", ta);
            if (names != null) {
                JsonArray na = new JsonArray();
                for (String n : names) na.add(n);
                o.add("names", na);
            }
            if (ids != null) {
                JsonArray ia = new JsonArray();
                for (Integer i : ids) ia.add(i);
                o.add("ids", ia);
            }
            if (tile != null) {
                JsonArray a = new JsonArray();
                a.add(tile[0]); a.add(tile[1]); a.add(tile[2]);
                o.add("tile", a);
            }
            if (area != null) {
                JsonArray a = new JsonArray();
                a.add(area[0]); a.add(area[1]); a.add(area[2]); a.add(area[3]); a.add(area[4]);
                o.add("area", a);
            }
            return o;
        }
    }

    private static Set<String> parseCsvLower(String args, String field) {
        String raw = McpHttpServer.parseStringField(args, field);
        if (raw == null) return null;
        String stripped = raw.replace("[", "").replace("]", "").replace("\"", "").trim();
        if (stripped.isEmpty()) return null;
        Set<String> out = new HashSet<>();
        for (String tok : stripped.split(",")) {
            String t = tok.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    private static Set<Integer> parseIdSet(String args) {
        String raw = McpHttpServer.parseStringField(args, "ids");
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

    private static int[] parseTile(String args) {
        String raw = McpHttpServer.parseStringField(args, "tile");
        if (raw == null) return null;
        String stripped = raw.replace("\"", "").trim();
        String[] parts = stripped.split(",");
        if (parts.length < 2) return null;
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int p = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 0;
            return new int[]{x, y, p};
        } catch (NumberFormatException e) { return null; }
    }

    private static int[] parseArea(String args) {
        String raw = McpHttpServer.parseStringField(args, "area");
        if (raw == null) return null;
        String stripped = raw.replace("\"", "").trim();
        String[] parts = stripped.split(",");
        if (parts.length < 4) return null;
        try {
            int x1 = Integer.parseInt(parts[0].trim());
            int y1 = Integer.parseInt(parts[1].trim());
            int x2 = Integer.parseInt(parts[2].trim());
            int y2 = Integer.parseInt(parts[3].trim());
            int p  = parts.length >= 5 ? Integer.parseInt(parts[4].trim()) : 0;
            return new int[]{x1, y1, x2, y2, p};
        } catch (NumberFormatException e) { return null; }
    }
}
