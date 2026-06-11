package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.runelite.mcp.api.WorldReader;
import dev.runelite.mcp.api.snapshot.DialogueSnapshot;
import dev.runelite.mcp.api.snapshot.GroundItemSnapshot;
import dev.runelite.mcp.api.snapshot.InterfaceSnapshot;
import dev.runelite.mcp.api.snapshot.ItemSnapshot;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.ObjectSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;
import dev.runelite.mcp.api.snapshot.WidgetSnapshot;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Read-only MCP tool handler. Surfaces game state queries (state, npc, obj, inv, equip,
 * ground, bank, dialog, widget, var, collision, screenshot, menu, chat, loginstate, prayer) to MCP
 * clients as structured JSON. All responses are gson-built {@link JsonObject}s carrying a
 * top-level {@code _meta.gameTick} and tool-specific payload fields.
 *
 * <p>All side-effecting tools (move, combat, click, key, record, capture, chat-send,
 * NPC/object/widget interactions) are intentionally excluded — this server only reads.
 */
public class McpToolHandler {

    private final Client client;
    private final ClientThread clientThread;
    private final WorldReader world;
    private final StateBuffer stateBuffer;
    private final ActionLog actionLog;
    private final DrawManager drawManager;

    public McpToolHandler(Client client, ClientThread clientThread,
                          WorldReader world, StateBuffer stateBuffer, ActionLog actionLog,
                          DrawManager drawManager) {
        this.client = client;
        this.clientThread = clientThread;
        this.world = world;
        this.stateBuffer = stateBuffer;
        this.actionLog = actionLog;
        this.drawManager = drawManager;
    }

    /** Return the tool schema definitions as a {@link JsonArray}. */
    public JsonArray getToolSchemas() {
        JsonArray tools = new JsonArray();
        tools.add(toolSchema("state", "[g] Query game state",
            prop("inc", "string", "Include sections (comma-separated): player,resources,inventory,equipment,npcs,players,skills,instance,projectiles,graphics,camera,world")));
        tools.add(toolSchema("npc", "[g] Query NPCs near the player",
            prop("n", "string", "Name filter"),
            prop("i", "string", "ID filter"),
            prop("r", "number", "Radius")));
        tools.add(toolSchema("obj", "[g] Query game objects in the scene",
            prop("n", "string", "Name filter"),
            prop("i", "string", "ID filter")));
        tools.add(toolSchema("inv", "[g] Inventory snapshot",
            prop("m", "string", "Mode: q (default) | s")));
        tools.add(toolSchema("equip", "[g] Equipped items"));
        tools.add(toolSchema("ground", "[g] Ground items near the player",
            prop("n", "string", "Name filter")));
        tools.add(toolSchema("bank", "[g] Bank state (open + contents when open)",
            prop("n", "string", "Name filter (substring, case-insensitive)")));
        tools.add(toolSchema("dialog", "[g] Dialogue state"));
        tools.add(toolSchema("widget", "[g] Widget query: m=get | pick (under cursor)",
            prop("m", "string", "Mode: get | pick"),
            prop("g", "number", "Group"),
            prop("c", "number", "Child")));
        tools.add(toolSchema("loginstate", "[g] Client login state (LOGGED_IN, LOGIN_SCREEN, AUTHENTICATOR, etc.)"));
        tools.add(toolSchema("prayer", "[g] Active prayers + prayer point pool (current/max)"));
        tools.add(toolSchema("var", "[g] Read a var by id: v=varbit (default) | p=varp | ci=varc-int | cs=varc-string",
            prop("m", "string", "Mode: v (varbit) | p (varp) | ci (varc int) | cs (varc string)"),
            prop("id", "number", "Var ID"),
            prop("varbitId", "number", "Deprecated alias for id (varbit mode)")));
        tools.add(toolSchema("collision",
            "[g] Collision flags for the local player tile or a world tile/radius",
            prop("x", "number", "World X (defaults to local player X)"),
            prop("y", "number", "World Y (defaults to local player Y)"),
            prop("plane", "number", "Plane (defaults to local player plane)"),
            prop("radius", "number", "World-tile radius around center, capped at 8; default 0")));
        tools.add(toolSchema("container", "[g] Read any item container by InventoryID (id=<int> or name: inventory|equipment|bank|looting_bag|seed_vault|group_storage)",
            prop("id", "string", "Container InventoryID (numeric) or a known name")));
        tools.add(toolSchema("def", "[g] Look up a cache definition by id (resolve ids the agent sees to names/actions/stats)",
            prop("type", "string", "item | npc | obj"),
            prop("id", "number", "Definition id")));
        tools.add(toolSchema("screenshot", "[g] Capture the game viewport as an inline PNG image"));
        tools.add(toolSchema("menu", "[g] Query right-click menu entries currently at cursor"));
        tools.add(toolSchema("chat", "[g] Read recent chat messages",
            prop("lines", "number", "Lines to read (default 10)")));
        tools.add(toolSchema("buffer",
            "[g] Delta-encoded state buffer. t>0 = absolute tick (full snapshot); t<0 = last |t| ticks (sparse deltas with added/removed/changed). Default t=-5.",
            prop("t", "number", "Tick: positive = absolute tick #; negative = last N ticks; default -5"),
            prop("types", "string", "Entity types CSV: npc,obj,ground,player,otherplayer,skills,hits,projectile,graphics (default all)"),
            prop("names", "string", "Name filter CSV (case-insensitive)"),
            prop("ids", "string", "ID filter CSV"),
            prop("tile", "string", "Tile filter: \"x,y,plane\""),
            prop("area", "string", "Area filter: \"x1,y1,x2,y2,plane\"")));
        tools.add(toolSchema("actions",
            "[g] Recent MenuOptionClicked actions performed (user clicks + plugin/macro actions via public menu API). Newest-last.",
            prop("t", "number", "Last N actions to return (default 50)"),
            prop("option", "string", "Substring filter on action option (case-insensitive)"),
            prop("target", "string", "Substring filter on action target (case-insensitive)"),
            prop("opcodes", "string", "MenuAction opcode IDs CSV"),
            prop("ids", "string", "Identifier (npc index / object id / slot) CSV"),
            prop("since", "number", "Only include actions at or after this tick")));
        return tools;
    }

    /**
     * Dispatch a tool call and return the result as a JSON string.
     * Executes on the client thread since RuneLite's Client is not thread-safe.
     */
    public String handleToolCall(String toolName, String args) {
        if ("loginstate".equals(toolName)) {
            try { return handleToolCallInner(toolName, args); }
            catch (Exception e) { return errorResponse(e.getMessage()); }
        }
        if ("buffer".equals(toolName)) {
            // buffer reads from StateBuffer (already thread-safe); no client-thread hop needed.
            try { return handleBuffer(args); }
            catch (Exception e) { return errorResponse(e.getMessage()); }
        }
        if ("actions".equals(toolName)) {
            // actions reads from ActionLog (already thread-safe); no client-thread hop needed.
            try { return handleActions(args); }
            catch (Exception e) { return errorResponse(e.getMessage()); }
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try {
                future.complete(handleToolCallInner(toolName, args));
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                JsonObject root = makeRoot();
                root.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                root.addProperty("trace", sw.toString());
                future.complete(root.toString());
            }
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return errorResponse("Timeout waiting for client thread");
        }
    }

    private String handleToolCallInner(String toolName, String args) {
        switch (toolName) {
            case "state":           return handleState(args);
            case "npc":             return handleNpc(args);
            case "obj":             return handleObj(args);
            case "inv":             return handleInv(args);
            case "equip":           return handleEquip();
            case "ground":          return handleGround(args);
            case "bank":            return handleBank(args);
            case "dialog":          return handleDialog();
            case "widget":          return handleWidget(args);
            case "loginstate":      return handleLoginState();
            case "prayer":          return handlePrayer();
            case "var":             return handleVar(args);
            case "collision":       return handleCollision(args);
            case "container":       return handleContainer(args);
            case "def":             return handleDef(args);
            case "menu":            return handleMenu();
            case "chat":            return handleChatRead(args);
            default:                return errorResponse("Unknown tool: " + toolName);
        }
    }

    // ========== Tool Implementations ==========

    private String handleState(String args) {
        String inc = McpHttpServer.parseStringField(args, "inc");
        PlayerSnapshot p = world.getLocalPlayer();
        if (p == null) return errorResponse("Not logged in");

        Set<String> sections = inc != null
            ? new HashSet<>(Arrays.asList(inc.split(",")))
            : Set.of("player", "resources");

        JsonObject root = makeRoot();
        if (sections.contains("player")) root.add("player", playerJson(p));
        if (sections.contains("resources")) root.add("resources", resourcesJson(p));
        if (sections.contains("inventory")) root.add("inventory", containerJson(world.getInventory(), 28));
        if (sections.contains("equipment")) root.add("equipment", containerJson(world.getEquipment(), -1));
        if (sections.contains("npcs")) {
            List<NpcSnapshot> npcs = world.getNpcs();
            JsonArray arr = new JsonArray();
            for (NpcSnapshot n : npcs.stream()
                .sorted(Comparator.comparingInt(n ->
                    Math.abs(n.worldX - p.worldX) + Math.abs(n.worldY - p.worldY)))
                .limit(10)
                .collect(Collectors.toList())) {
                arr.add(ActorJson.npcSummary(n));
            }
            JsonObject o = new JsonObject();
            o.addProperty("total", npcs.size());
            o.add("items", arr);
            root.add("npcs", o);
        }
        if (sections.contains("players")) {
            List<PlayerSnapshot> players = world.getOtherPlayers();
            JsonArray arr = new JsonArray();
            for (PlayerSnapshot other : players.stream()
                .sorted(Comparator.comparingInt(other ->
                    Math.abs(other.worldX - p.worldX) + Math.abs(other.worldY - p.worldY)))
                .limit(10)
                .collect(Collectors.toList())) {
                arr.add(playerJson(other));
            }
            JsonObject o = new JsonObject();
            o.addProperty("total", players.size());
            o.add("items", arr);
            root.add("players", o);
        }
        if (sections.contains("skills")) root.add("skills", skillsJson());
        if (sections.contains("instance")) {
            root.add("instance", InstanceGeometry.toJson(
                world.isInInstance(), world.getSceneBaseX(), world.getSceneBaseY(),
                p.plane, p.worldX, p.worldY,
                world.getMapRegions(), world.getInstanceTemplateChunks()));
        }
        if (sections.contains("projectiles")) {
            JsonArray arr = new JsonArray();
            for (var pr : world.getProjectiles()) arr.add(SceneJson.projectile(pr));
            root.add("projectiles", arr);
        }
        if (sections.contains("graphics")) {
            JsonArray arr = new JsonArray();
            for (var g : world.getGraphicsObjects()) arr.add(SceneJson.graphicsObject(g));
            root.add("graphics", arr);
        }
        if (sections.contains("camera")) root.add("camera", cameraJson());
        if (sections.contains("world")) {
            JsonObject w = new JsonObject();
            w.addProperty("world", world.getWorld());
            JsonArray types = new JsonArray();
            for (String t : world.getWorldTypes()) types.add(t);
            w.add("types", types);
            root.add("world", w);
        }
        return root.toString();
    }

    /** Projected canvas point [px,py] for a world tile, or null when off-screen. */
    private JsonArray screenArray(int worldX, int worldY, int plane) {
        int[] s = world.worldPointToScreen(worldX, worldY, plane);
        if (s == null) return null;
        JsonArray a = new JsonArray();
        a.add(s[0]); a.add(s[1]);
        return a;
    }

    private JsonObject cameraJson() {
        JsonObject o = new JsonObject();
        o.addProperty("yaw", world.getCameraYaw());
        o.addProperty("pitch", world.getCameraPitch());
        o.addProperty("zoom", world.getCameraZoom());
        JsonArray pos = new JsonArray();
        pos.add(world.getCameraX()); pos.add(world.getCameraY()); pos.add(world.getCameraZ());
        o.add("pos", pos);
        JsonArray canvas = new JsonArray();
        canvas.add(world.getCanvasWidth()); canvas.add(world.getCanvasHeight());
        o.add("canvas", canvas);
        return o;
    }

    private String handleNpc(String args) {
        List<NpcSnapshot> npcs = world.getNpcs();
        PlayerSnapshot local = world.getLocalPlayer();
        if (local == null) return errorResponse("Not logged in");

        String name = extractName(args);
        Set<Integer> ids = extractIds(args);
        if (name != null || ids != null) {
            final String nameF = name;
            final Set<Integer> idsF = ids;
            npcs = npcs.stream()
                .filter(n -> {
                    boolean nameMatch = nameF != null && n.name != null && n.name.equalsIgnoreCase(nameF);
                    boolean idMatch = idsF != null && idsF.contains(n.id);
                    return nameMatch || idMatch;
                })
                .collect(Collectors.toList());
        }

        int total = npcs.size();
        int limit = 15;
        List<NpcSnapshot> sorted = npcs.stream()
            .sorted(Comparator.comparingInt(n ->
                Math.abs(n.worldX - local.worldX) + Math.abs(n.worldY - local.worldY)))
            .limit(limit)
            .collect(Collectors.toList());

        JsonObject root = makeRoot();
        root.addProperty("total", total);
        root.addProperty("shown", sorted.size());
        JsonArray items = new JsonArray();
        for (NpcSnapshot n : sorted) {
            JsonObject o = npcDetail(n);
            o.addProperty("dist", Math.abs(n.worldX - local.worldX) + Math.abs(n.worldY - local.worldY));
            JsonArray screen = screenArray(n.worldX, n.worldY, n.plane);
            if (screen != null) o.add("screen", screen);
            items.add(o);
        }
        root.add("items", items);
        return root.toString();
    }

    private String handleObj(String args) {
        List<ObjectSnapshot> objects = world.getObjects();
        PlayerSnapshot local = world.getLocalPlayer();
        if (local == null) return errorResponse("Not logged in");

        String name = extractName(args);
        Set<Integer> ids = extractIds(args);
        if (name != null || ids != null) {
            final String nameF = name;
            final Set<Integer> idsF = ids;
            objects = objects.stream()
                .filter(o -> {
                    boolean nameMatch = nameF != null && o.name != null && o.name.equalsIgnoreCase(nameF);
                    boolean idMatch = idsF != null && idsF.contains(o.id);
                    return nameMatch || idMatch;
                })
                .collect(Collectors.toList());
        }

        int total = objects.size();
        int limit = 15;
        List<ObjectSnapshot> sorted = objects.stream()
            .sorted(Comparator.comparingInt(o ->
                Math.abs(o.worldX - local.worldX) + Math.abs(o.worldY - local.worldY)))
            .limit(limit)
            .collect(Collectors.toList());

        JsonObject root = makeRoot();
        root.addProperty("total", total);
        root.addProperty("shown", sorted.size());
        JsonArray items = new JsonArray();
        for (ObjectSnapshot o : sorted) {
            JsonObject j = new JsonObject();
            j.addProperty("id", o.id);
            if (o.name != null) j.addProperty("name", o.name);
            j.add("pos", posArray(o.worldX, o.worldY, o.plane));
            if (o.objectType != null) j.addProperty("type", o.objectType);
            if (o.actions != null) j.add("actions", actionsArray(o.actions));
            JsonArray screen = screenArray(o.worldX, o.worldY, o.plane);
            if (screen != null) j.add("screen", screen);
            items.add(j);
        }
        root.add("items", items);
        return root.toString();
    }

    private String handleInv(String args) {
        String mode = McpHttpServer.parseStringField(args, "m");
        if (mode == null) mode = "q";
        List<ItemSnapshot> inv = world.getInventory();

        JsonObject root = makeRoot();
        root.addProperty("total", inv.size());
        root.addProperty("free", 28 - inv.size());
        if (!"s".equals(mode)) {
            JsonArray items = new JsonArray();
            for (ItemSnapshot it : inv) items.add(itemDetail(it));
            root.add("items", items);
        }
        return root.toString();
    }

    private String handleEquip() {
        List<ItemSnapshot> equip = world.getEquipment();
        JsonObject root = makeRoot();
        JsonArray items = new JsonArray();
        for (ItemSnapshot it : equip) items.add(itemDetail(it));
        root.add("items", items);
        return root.toString();
    }

    private String handleGround(String args) {
        List<GroundItemSnapshot> items = world.getGroundItems();
        String name = extractName(args);
        if (name != null) {
            final String nameF = name;
            items = items.stream()
                .filter(it -> it.name != null && it.name.equalsIgnoreCase(nameF))
                .collect(Collectors.toList());
        }

        PlayerSnapshot localP = world.getLocalPlayer();
        if (localP != null) {
            final PlayerSnapshot lp = localP;
            items = items.stream()
                .sorted(Comparator.comparingInt(it ->
                    Math.abs(it.worldX - lp.worldX) + Math.abs(it.worldY - lp.worldY)))
                .collect(Collectors.toList());
        }

        int total = items.size();
        int limit = 15;
        List<GroundItemSnapshot> shown = items.stream().limit(limit).collect(Collectors.toList());

        JsonObject root = makeRoot();
        root.addProperty("total", total);
        root.addProperty("shown", shown.size());
        JsonArray arr = new JsonArray();
        for (GroundItemSnapshot it : shown) {
            JsonObject j = new JsonObject();
            j.addProperty("id", it.id);
            if (it.name != null) j.addProperty("name", it.name);
            j.addProperty("quantity", it.quantity);
            j.add("pos", posArray(it.worldX, it.worldY, it.plane));
            JsonArray screen = screenArray(it.worldX, it.worldY, it.plane);
            if (screen != null) j.add("screen", screen);
            arr.add(j);
        }
        root.add("items", arr);
        return root.toString();
    }

    private String handleBank(String args) {
        InterfaceSnapshot iface = world.getInterfaces();
        JsonObject root = makeRoot();
        root.addProperty("open", iface.bankOpen);
        if (!iface.bankOpen) return root.toString();

        List<ItemSnapshot> items = world.getBankItems();
        int total = items.size();
        String name = extractName(args);
        if (name != null) {
            final String needle = name.toLowerCase();
            items = items.stream()
                .filter(it -> it.name != null && it.name.toLowerCase().contains(needle))
                .collect(Collectors.toList());
            root.addProperty("filter", name);
        }
        root.addProperty("total", total);
        root.addProperty("matched", items.size());

        int limit = 50;
        JsonArray arr = new JsonArray();
        for (ItemSnapshot it : items.stream().limit(limit).collect(Collectors.toList())) {
            arr.add(itemDetail(it));
        }
        root.add("items", arr);
        if (items.size() > limit) root.addProperty("truncated", items.size() - limit);
        return root.toString();
    }

    private String handleDialog() {
        DialogueSnapshot d = world.getDialogue();
        JsonObject root = makeRoot();
        root.addProperty("open", d.open);
        root.addProperty("canContinue", d.canContinue);
        root.addProperty("hasOptions", d.hasOptions);
        if (d.text != null) root.addProperty("text", d.text);
        if (d.speakerName != null) root.addProperty("speaker", d.speakerName);
        if (d.options != null) {
            JsonArray a = new JsonArray();
            for (String o : d.options) a.add(o);
            root.add("options", a);
        }
        return root.toString();
    }

    private String handleWidget(String args) {
        String mode = McpHttpServer.parseStringField(args, "m");
        if (mode == null) mode = "get";

        if ("pick".equals(mode) || "p".equals(mode)) {
            return pickWidgetAtCursor();
        }

        String gStr = McpHttpServer.parseStringField(args, "g");
        String cStr = McpHttpServer.parseStringField(args, "c");
        if (gStr == null) return errorResponse("Missing widget group (g)");
        int group = Integer.parseInt(gStr);
        int child = cStr != null ? Integer.parseInt(cStr) : 0;

        if ("get".equals(mode) || "v".equals(mode)) {
            WidgetSnapshot w = world.getWidget(group, child);
            JsonObject root = makeRoot();
            root.addProperty("group", group);
            root.addProperty("child", child);
            if (w == null) {
                root.addProperty("found", false);
                return root.toString();
            }
            root.addProperty("found", true);
            root.addProperty("visible", w.visible);
            if (w.text != null) root.addProperty("text", w.text);
            root.addProperty("itemId", w.itemId);
            root.addProperty("itemQuantity", w.itemQuantity);
            if (w.actions != null) root.add("actions", actionsArray(w.actions));
            return root.toString();
        }
        return errorResponse("Unknown widget mode: " + mode + " (read-only: get|pick)");
    }

    private String handleLoginState() {
        JsonObject root = makeRoot();
        root.addProperty("loginState", client.getGameState().name());
        root.addProperty("stateId", client.getGameState().getState());
        return root.toString();
    }

    private String handlePrayer() {
        PlayerSnapshot p = world.getLocalPlayer();
        if (p == null) return errorResponse("Not logged in");
        JsonObject root = makeRoot();
        JsonObject points = new JsonObject();
        points.addProperty("current", p.prayerPoints);
        points.addProperty("max", p.maxPrayerPoints);
        root.add("points", points);
        JsonArray active = new JsonArray();
        if (p.activePrayers != null) for (String pr : p.activePrayers) active.add(pr);
        root.add("active", active);
        return root.toString();
    }

    private String handleVar(String args) {
        String mode = McpHttpServer.parseStringField(args, "m");
        if (mode == null) mode = "v";
        // Accept id=<int>; fall back to the legacy varbitId param for varbit callers.
        String idStr = McpHttpServer.parseStringField(args, "id");
        if (idStr == null) idStr = McpHttpServer.parseStringField(args, "varbitId");
        if (idStr == null) return errorResponse("Missing var id (id=<int>)");
        int id;
        try { id = Integer.parseInt(idStr.trim()); }
        catch (NumberFormatException e) { return errorResponse("Invalid var id: " + idStr); }

        JsonObject root = makeRoot();
        root.addProperty("mode", mode);
        root.addProperty("id", id);
        switch (mode) {
            case "v":
                root.addProperty("value", world.getVarbitValue(id));
                root.addProperty("varbitId", id); // back-compat alias
                break;
            case "p":
                root.addProperty("value", world.getVarpValue(id));
                break;
            case "ci":
                root.addProperty("value", world.getVarcIntValue(id));
                break;
            case "cs": {
                String s = world.getVarcStrValue(id);
                if (s == null) root.add("value", com.google.gson.JsonNull.INSTANCE);
                else root.addProperty("value", s);
                break;
            }
            default:
                return errorResponse("Unknown var mode: " + mode
                    + " (use v=varbit | p=varp | ci=varc-int | cs=varc-string)");
        }
        return root.toString();
    }

    private String handleCollision(String args) {
        PlayerSnapshot local = world.getLocalPlayer();
        int centerX = parseIntArg(args, "x", local != null ? local.worldX : null);
        int centerY = parseIntArg(args, "y", local != null ? local.worldY : null);
        int plane = parseIntArg(args, "plane", local != null ? local.plane : client.getPlane());
        int radius = parseIntArg(args, "radius", 0);
        radius = Math.max(0, Math.min(radius, 8));

        if (centerX < 0 || centerY < 0) {
            return errorResponse("Missing x/y and no logged-in local player");
        }

        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null) {
            return errorResponse("World view unavailable");
        }
        CollisionData[] collisionData = worldView.getCollisionMaps();
        if (collisionData == null) {
            return errorResponse("Collision maps unavailable");
        }
        if (plane < 0 || plane >= collisionData.length || collisionData[plane] == null) {
            return errorResponse("Collision map unavailable for plane " + plane);
        }

        int[][] flags = collisionData[plane].getFlags();
        if (flags == null) {
            return errorResponse("Collision flags unavailable for plane " + plane);
        }
        int baseX = worldView.getBaseX();
        int baseY = worldView.getBaseY();
        int centerSceneX = centerX - baseX;
        int centerSceneY = centerY - baseY;
        if (!isSceneTile(centerSceneX, centerSceneY, flags)) {
            return errorResponse("Center tile is outside the loaded scene: " + centerX + "," + centerY + "," + plane);
        }

        JsonObject root = makeRoot();
        root.add("center", posArray(centerX, centerY, plane));
        root.add("centerScene", sceneArray(centerSceneX, centerSceneY));
        root.addProperty("radius", radius);

        JsonArray tiles = new JsonArray();
        for (int wx = centerX - radius; wx <= centerX + radius; wx++) {
            for (int wy = centerY - radius; wy <= centerY + radius; wy++) {
                int sx = wx - baseX;
                int sy = wy - baseY;
                if (!isSceneTile(sx, sy, flags)) {
                    continue;
                }
                tiles.add(collisionTileJson(wx, wy, plane, sx, sy, flags));
            }
        }
        root.add("tiles", tiles);
        return root.toString();
    }

    // ========== Containers ==========

    private String handleContainer(String args) {
        String idArg = McpHttpServer.parseStringField(args, "id");
        if (idArg == null) return errorResponse("container needs id=<int|name>");
        Integer cid = resolveContainerId(idArg.trim());
        if (cid == null) return errorResponse("Unknown container: " + idArg
            + " (use a numeric InventoryID or: inventory|equipment|bank|looting_bag|seed_vault|group_storage)");

        List<ItemSnapshot> items = world.getItemContainerById(cid);
        JsonObject root = makeRoot();
        root.addProperty("id", cid);
        root.addProperty("total", items.size());
        JsonArray arr = new JsonArray();
        for (ItemSnapshot it : items) arr.add(itemDetail(it));
        root.add("items", arr);
        return root.toString();
    }

    /** Resolve a container arg (numeric InventoryID or a known name) to its int id. Null if unknown. */
    static Integer resolveContainerId(String arg) {
        if (arg == null || arg.isEmpty()) return null;
        try { return Integer.parseInt(arg); } catch (NumberFormatException ignored) {}
        switch (arg.toLowerCase().replace("-", "_").replace(" ", "_")) {
            case "inventory": case "inv":           return 93;
            case "equipment": case "equip": case "worn": return 94;
            case "bank":                            return 95;
            case "looting_bag": case "lootingbag":  return 516;
            case "seed_vault": case "seedvault":    return 626;
            case "group_storage": case "gim":       return 659;
            default: return null;
        }
    }

    // ========== Cache definition lookup ==========

    private String handleDef(String args) {
        String type = McpHttpServer.parseStringField(args, "type");
        String idStr = McpHttpServer.parseStringField(args, "id");
        if (type == null || idStr == null) return errorResponse("def needs type=item|npc|obj and id=<int>");
        int id;
        try { id = Integer.parseInt(idStr.trim()); }
        catch (NumberFormatException e) { return errorResponse("Invalid id: " + idStr); }

        JsonObject root = makeRoot();
        root.addProperty("type", type);
        root.addProperty("id", id);
        switch (type.toLowerCase()) {
            case "item": {
                net.runelite.api.ItemComposition c = client.getItemDefinition(id);
                if (c == null || c.getName() == null) { root.addProperty("found", false); return root.toString(); }
                root.addProperty("found", true);
                root.addProperty("name", c.getName());
                root.addProperty("members", c.isMembers());
                root.addProperty("stackable", c.isStackable());
                root.addProperty("tradeable", c.isTradeable());
                root.addProperty("price", c.getPrice());
                root.addProperty("haPrice", c.getHaPrice());
                if (c.getLinkedNoteId() != -1) root.addProperty("linkedNoteId", c.getLinkedNoteId());
                JsonArray a = nonEmptyActionsArray(c.getInventoryActions());
                if (a.size() > 0) root.add("actions", a);
                break;
            }
            case "npc": {
                net.runelite.api.NPCComposition c = client.getNpcDefinition(id);
                if (c == null || c.getName() == null) { root.addProperty("found", false); return root.toString(); }
                root.addProperty("found", true);
                root.addProperty("name", c.getName());
                root.addProperty("combatLevel", c.getCombatLevel());
                root.addProperty("size", c.getSize());
                JsonArray a = nonEmptyActionsArray(c.getActions());
                if (a.size() > 0) root.add("actions", a);
                break;
            }
            case "obj": case "object": {
                net.runelite.api.ObjectComposition c = client.getObjectDefinition(id);
                if (c == null || c.getName() == null) { root.addProperty("found", false); return root.toString(); }
                root.addProperty("found", true);
                root.addProperty("name", c.getName());
                JsonArray size = new JsonArray();
                size.add(c.getSizeX()); size.add(c.getSizeY());
                root.add("size", size);
                JsonArray a = nonEmptyActionsArray(c.getActions());
                if (a.size() > 0) root.add("actions", a);
                break;
            }
            default:
                return errorResponse("Unknown def type: " + type + " (use item|npc|obj)");
        }
        return root.toString();
    }

    // ========== Widget Picker ==========

    private String pickWidgetAtCursor() {
        net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
        int mx = mousePos.getX();
        int my = mousePos.getY();

        JsonObject root = makeRoot();
        JsonArray mouse = new JsonArray();
        mouse.add(mx); mouse.add(my);
        root.add("mouse", mouse);

        // Walk the live widget tree from the currently-loaded interface roots instead of
        // scanning a hardcoded group list. Catches dynamically-loaded interfaces (bank
        // search, prayer book swaps, etc.) without maintaining a stale id list, and only
        // recurses into containers whose bounds actually contain the cursor.
        JsonArray widgets = new JsonArray();
        net.runelite.api.widgets.Widget[] roots = client.getWidgetRoots();
        if (roots != null) {
            for (net.runelite.api.widgets.Widget w : roots) {
                collectWidgetsAtPoint(w, mx, my, widgets);
            }
        }
        root.add("widgets", widgets);
        return root.toString();
    }

    /** Depth-first collect every visible widget whose bounds contain (mx, my). Flat output. */
    private void collectWidgetsAtPoint(net.runelite.api.widgets.Widget w, int mx, int my, JsonArray out) {
        if (w == null || w.isHidden()) return;
        Rectangle b = w.getBounds();
        if (b.width <= 0 || b.height <= 0) return;
        if (!b.contains(mx, my)) return;

        JsonObject wj = new JsonObject();
        int id = w.getId();
        wj.addProperty("group", id >>> 16);
        wj.addProperty("child", id & 0xFFFF);
        // Dynamic children share the parent's id; index disambiguates them. -1 for static.
        int idx = w.getIndex();
        if (idx >= 0) wj.addProperty("index", idx);
        wj.add("bounds", boundsArray(b));
        if (w.getText() != null && !w.getText().isEmpty()) wj.addProperty("text", w.getText());
        if (w.getName() != null && !w.getName().isEmpty()) wj.addProperty("name", w.getName());
        if (w.getItemId() > 0) wj.addProperty("itemId", w.getItemId());
        if (w.getActions() != null) {
            JsonArray a = nonEmptyActionsArray(w.getActions());
            if (a.size() > 0) wj.add("actions", a);
        }
        out.add(wj);

        recurseKids(w.getStaticChildren(), mx, my, out);
        recurseKids(w.getDynamicChildren(), mx, my, out);
        recurseKids(w.getNestedChildren(), mx, my, out);
    }

    private void recurseKids(net.runelite.api.widgets.Widget[] kids, int mx, int my, JsonArray out) {
        if (kids == null) return;
        for (net.runelite.api.widgets.Widget c : kids) collectWidgetsAtPoint(c, mx, my, out);
    }

    // ========== Screenshot ==========

    /**
     * Captures the rendered game frame via {@link DrawManager#requestNextFrameListener},
     * which delivers the actual canvas pixels — independent of whether other windows are
     * covering the RuneLite client on screen. Returns raw PNG bytes; throws on timeout or
     * encode failure. {@link McpHttpServer#handleToolsCall} calls this directly and wraps
     * the bytes as an MCP {@code image} content item (or routes exceptions through the
     * standard error envelope).
     */
    public byte[] captureScreenshot() throws Exception {
        CompletableFuture<BufferedImage> future = new CompletableFuture<>();
        drawManager.requestNextFrameListener(image -> {
            try {
                BufferedImage bi;
                if (image instanceof BufferedImage) {
                    bi = (BufferedImage) image;
                } else {
                    bi = new BufferedImage(image.getWidth(null), image.getHeight(null),
                        BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = bi.createGraphics();
                    try { g.drawImage(image, 0, 0, null); } finally { g.dispose(); }
                }
                future.complete(bi);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        BufferedImage img = future.get(2, TimeUnit.SECONDS);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    // ========== Menu ==========

    private String handleMenu() {
        MenuEntry[] entries = client.getMenuEntries();
        JsonObject root = makeRoot();
        if (entries == null || entries.length == 0) {
            root.add("entries", new JsonArray());
            return root.toString();
        }
        JsonArray arr = new JsonArray();
        // Last entry is the default left-click; emit newest-first (index decreasing) to match
        // the historical text-output convention.
        for (int i = entries.length - 1; i >= 0; i--) {
            MenuEntry e = entries[i];
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("option", e.getOption() != null ? e.getOption() : "");
            String target = e.getTarget() != null ? e.getTarget() : "";
            o.addProperty("target", target.replaceAll("<[^>]+>", "").trim());
            o.addProperty("type", e.getType().name());
            o.addProperty("identifier", e.getIdentifier());
            o.addProperty("param0", e.getParam0());
            int p1 = e.getParam1();
            JsonArray widget = new JsonArray();
            widget.add(p1 >> 16); widget.add(p1 & 0xFFFF);
            o.add("widget", widget);
            if (i == entries.length - 1) o.addProperty("defaultClick", true);
            arr.add(o);
        }
        root.add("entries", arr);
        return root.toString();
    }

    // ========== Chat ==========

    private String handleChatRead(String args) {
        String linesStr = McpHttpServer.parseStringField(args, "lines");
        int maxLines = linesStr != null ? Integer.parseInt(linesStr) : 10;
        JsonObject root = makeRoot();
        JsonArray messages = new JsonArray();
        try {
            for (MessageNode node : client.getMessages()) {
                if (node == null) continue;
                String text = node.getValue();
                if (text == null || text.isEmpty()) continue;
                text = text.replaceAll("<[^>]+>", "").trim();
                JsonObject m = new JsonObject();
                m.addProperty("type", node.getType().name());
                String sender = node.getName();
                if (sender != null && !sender.isEmpty()) {
                    m.addProperty("sender", sender.replaceAll("<[^>]+>", "").trim());
                }
                m.addProperty("text", text);
                messages.add(m);
                if (messages.size() >= maxLines) break;
            }
        } catch (Exception e) {
            root.addProperty("error", "Reading chat: " + e.getMessage());
            return root.toString();
        }
        root.add("messages", messages);
        return root.toString();
    }

    // ========== Buffer / Actions ==========

    private String handleBuffer(String args) {
        return new BufferQueryHandler(stateBuffer, client.getTickCount()).handle(args);
    }

    private String handleActions(String args) {
        return new ActionsQueryHandler(actionLog, client.getTickCount()).handle(args);
    }

    // ========== Snapshot → JSON helpers ==========

    private static JsonObject playerJson(PlayerSnapshot p) {
        return ActorJson.player(p);
    }

    private static JsonObject resourcesJson(PlayerSnapshot p) {
        JsonObject o = new JsonObject();
        JsonArray hp = new JsonArray();
        hp.add(p.currentHealth); hp.add(p.maxHealth);
        o.add("hp", hp);
        JsonArray prayer = new JsonArray();
        prayer.add(p.prayerPoints); prayer.add(p.maxPrayerPoints);
        o.add("prayer", prayer);
        o.addProperty("runEnergy", p.runEnergy);
        o.addProperty("specialAttack", p.specialAttackEnergy);
        o.addProperty("runEnabled", p.runEnabled);
        // Status effects derived from POISON varp + stamina/antifire varbits in
        // RuneLiteWorldReader. All flags emitted (true and false) so absence is
        // unambiguously "not captured" rather than "false".
        JsonObject status = new JsonObject();
        status.addProperty("poisoned", p.poisoned);
        status.addProperty("venomed", p.venomed);
        status.addProperty("antiPoisoned", p.antiPoisoned);
        status.addProperty("antiVenomed", p.antiVenomed);
        status.addProperty("staminaBoosted", p.staminaBoosted);
        status.addProperty("antifired", p.antifired);
        status.addProperty("superAntifired", p.superAntifired);
        o.add("status", status);
        return o;
    }

    private static JsonObject containerJson(List<ItemSnapshot> items, int capacity) {
        JsonObject o = new JsonObject();
        o.addProperty("total", items.size());
        if (capacity > 0) o.addProperty("free", capacity - items.size());
        JsonArray arr = new JsonArray();
        for (ItemSnapshot it : items) arr.add(itemDetail(it));
        o.add("items", arr);
        return o;
    }

    private static JsonObject itemDetail(ItemSnapshot it) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", it.slot);
        o.addProperty("id", it.id);
        if (it.name != null) o.addProperty("name", it.name);
        o.addProperty("quantity", it.quantity);
        if (it.actions != null) {
            JsonArray a = nonEmptyActionsArray(it.actions);
            if (a.size() > 0) o.add("actions", a);
        }
        return o;
    }

    private static JsonObject npcSummary(NpcSnapshot n) {
        return ActorJson.npcSummary(n);
    }

    private static JsonObject npcDetail(NpcSnapshot n) {
        return ActorJson.npcDetail(n);
    }

    private JsonObject skillsJson() {
        JsonObject o = new JsonObject();
        Skill[] skills = Skill.values();
        for (int i = 0; i < skills.length; i++) {
            if (skills[i] == Skill.OVERALL) continue;
            int real = world.getSkillLevel(i);
            int boosted = world.getBoostedLevel(i);
            if (real <= 1 && boosted <= 1) continue;
            JsonObject s = new JsonObject();
            s.addProperty("real", real);
            s.addProperty("boosted", boosted);
            o.add(skills[i].getName(), s);
        }
        return o;
    }

    private static JsonArray posArray(int x, int y, int plane) {
        JsonArray a = new JsonArray();
        a.add(x); a.add(y); a.add(plane);
        return a;
    }

    private static JsonArray boundsArray(Rectangle r) {
        JsonArray a = new JsonArray();
        a.add(r.x); a.add(r.y); a.add(r.width); a.add(r.height);
        return a;
    }

    private static JsonArray sceneArray(int x, int y) {
        JsonArray a = new JsonArray();
        a.add(x); a.add(y);
        return a;
    }

    private static JsonArray actionsArray(String[] actions) {
        JsonArray a = new JsonArray();
        for (String s : actions) a.add(s);
        return a;
    }

    private static JsonArray nonEmptyActionsArray(String[] actions) {
        JsonArray a = new JsonArray();
        for (String s : actions) if (s != null && !s.isEmpty()) a.add(s);
        return a;
    }

    // ========== Arg / response helpers ==========

    private static String extractName(String args) {
        String raw = McpHttpServer.parseStringField(args, "n");
        if (raw == null) return null;
        return raw.replace("[", "").replace("]", "").replace("\"", "").trim();
    }

    private static Set<Integer> extractIds(String args) {
        String raw = McpHttpServer.parseStringField(args, "i");
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

    private static int parseIntArg(String args, String key, Integer fallback) {
        String raw = McpHttpServer.parseStringField(args, key);
        if (raw == null || raw.isBlank()) {
            return fallback != null ? fallback : -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback != null ? fallback : -1;
        }
    }

    private static JsonObject collisionTileJson(int worldX, int worldY, int plane,
                                                int sceneX, int sceneY, int[][] flags) {
        int raw = flags[sceneX][sceneY];
        JsonObject tile = new JsonObject();
        tile.add("pos", posArray(worldX, worldY, plane));
        tile.add("scene", sceneArray(sceneX, sceneY));
        tile.addProperty("raw", raw);
        tile.addProperty("rawHex", "0x" + Integer.toHexString(raw));
        tile.add("movement", movementFlagsJson(raw));
        tile.add("lineOfSight", lineOfSightFlagsJson(raw));
        tile.add("travel", travelJson(sceneX, sceneY, flags));
        return tile;
    }

    private static JsonObject movementFlagsJson(int raw) {
        JsonObject movement = new JsonObject();
        movement.addProperty("northWest", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST));
        movement.addProperty("north", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_NORTH));
        movement.addProperty("northEast", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST));
        movement.addProperty("east", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_EAST));
        movement.addProperty("southEast", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST));
        movement.addProperty("south", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH));
        movement.addProperty("southWest", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST));
        movement.addProperty("west", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_WEST));
        movement.addProperty("object", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_OBJECT));
        movement.addProperty("floorDecoration", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION));
        movement.addProperty("floor", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_FLOOR));
        movement.addProperty("full", has(raw, CollisionDataFlag.BLOCK_MOVEMENT_FULL));
        return movement;
    }

    private static JsonObject lineOfSightFlagsJson(int raw) {
        JsonObject lineOfSight = new JsonObject();
        lineOfSight.addProperty("north", has(raw, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH));
        lineOfSight.addProperty("east", has(raw, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST));
        lineOfSight.addProperty("south", has(raw, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH));
        lineOfSight.addProperty("west", has(raw, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST));
        lineOfSight.addProperty("full", has(raw, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL));
        return lineOfSight;
    }

    private static JsonObject travelJson(int sceneX, int sceneY, int[][] flags) {
        JsonObject travel = new JsonObject();
        travel.addProperty("north", canTravel(sceneX, sceneY, 0, 1,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, flags));
        travel.addProperty("east", canTravel(sceneX, sceneY, 1, 0,
            CollisionDataFlag.BLOCK_MOVEMENT_EAST, CollisionDataFlag.BLOCK_MOVEMENT_WEST, flags));
        travel.addProperty("south", canTravel(sceneX, sceneY, 0, -1,
            CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, CollisionDataFlag.BLOCK_MOVEMENT_NORTH, flags));
        travel.addProperty("west", canTravel(sceneX, sceneY, -1, 0,
            CollisionDataFlag.BLOCK_MOVEMENT_WEST, CollisionDataFlag.BLOCK_MOVEMENT_EAST, flags));
        return travel;
    }

    private static boolean canTravel(int sceneX, int sceneY, int dx, int dy,
                                     int fromFlag, int toFlag, int[][] flags) {
        int toX = sceneX + dx;
        int toY = sceneY + dy;
        if (!isSceneTile(toX, toY, flags)) {
            return false;
        }
        int fromRaw = flags[sceneX][sceneY];
        int toRaw = flags[toX][toY];
        return (fromRaw & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | fromFlag)) == 0
            && (toRaw & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | toFlag)) == 0;
    }

    private static boolean has(int raw, int flag) {
        return (raw & flag) != 0;
    }

    private static boolean isSceneTile(int sceneX, int sceneY, int[][] flags) {
        return sceneX >= 0 && sceneY >= 0
            && sceneX < Constants.SCENE_SIZE && sceneY < Constants.SCENE_SIZE
            && sceneX < flags.length && flags[sceneX] != null && sceneY < flags[sceneX].length;
    }

    private JsonObject makeRoot() {
        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("gameTick", client.getTickCount());
        root.add("_meta", meta);
        return root;
    }

    private String errorResponse(String message) {
        JsonObject root = makeRoot();
        root.addProperty("error", message);
        return root.toString();
    }

    // ========== Tool schema builders ==========

    private static JsonObject toolSchema(String name, String description, JsonObject... props) {
        JsonObject schema = new JsonObject();
        schema.addProperty("name", name);
        schema.addProperty("description", description);
        JsonObject input = new JsonObject();
        input.addProperty("type", "object");
        if (props.length > 0) {
            JsonObject properties = new JsonObject();
            for (JsonObject p : props) {
                for (String key : p.keySet()) properties.add(key, p.get(key));
            }
            input.add("properties", properties);
        }
        schema.add("inputSchema", input);
        return schema;
    }

    private static JsonObject prop(String name, String type, String description) {
        JsonObject p = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("description", description);
        p.add(name, body);
        return p;
    }
}
