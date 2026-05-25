package dev.runelite.mcp;

import dev.runelite.mcp.api.WorldReader;
import dev.runelite.mcp.api.snapshot.*;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.MessageNode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.DrawManager;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Read-only MCP tool handler. Surfaces game state queries (state, npc, obj, inv, equip,
 * ground, bank, dialog, widget, var, screenshot, menu, chat, loginstate) to MCP clients.
 *
 * All side-effecting tools (move, combat, prayer, click, key, record, capture, chat-send,
 * NPC/object/widget interactions) are intentionally excluded — this server only reads.
 */
public class McpToolHandler {

    private static final Logger log = Logger.getLogger(McpToolHandler.class.getName());

    private final Client client;
    private final ClientThread clientThread;
    private final ConfigManager configManager;
    private final WorldReader world;
    private final StateBuffer stateBuffer;
    private final ActionLog actionLog;
    private final DrawManager drawManager;

    public McpToolHandler(Client client, ClientThread clientThread, ConfigManager configManager,
                          WorldReader world, StateBuffer stateBuffer, ActionLog actionLog,
                          DrawManager drawManager) {
        this.client = client;
        this.clientThread = clientThread;
        this.configManager = configManager;
        this.world = world;
        this.stateBuffer = stateBuffer;
        this.actionLog = actionLog;
        this.drawManager = drawManager;
    }

    /** Return JSON array of tool schema definitions. */
    public String getToolSchemas() {
        return "[\n" +
            toolSchema("state", "[g] Query game state",
                prop("inc", "string", "Include sections (comma-separated): player,resources,inventory,equipment,npcs,skills")) + ",\n" +
            toolSchema("npc", "[g] Query NPCs near the player",
                prop("n", "string", "Name filter") + "," +
                prop("i", "string", "ID filter") + "," +
                prop("r", "number", "Radius")) + ",\n" +
            toolSchema("obj", "[g] Query game objects in the scene",
                prop("n", "string", "Name filter") + "," +
                prop("i", "string", "ID filter")) + ",\n" +
            toolSchema("inv", "[g] Inventory snapshot",
                prop("m", "string", "Mode: q (default) | s")) + ",\n" +
            toolSchema("equip", "[g] Equipped items", "") + ",\n" +
            toolSchema("ground", "[g] Ground items near the player",
                prop("n", "string", "Name filter")) + ",\n" +
            toolSchema("bank", "[g] Bank state (open + contents when open)",
                prop("n", "string", "Name filter (substring, case-insensitive)")) + ",\n" +
            toolSchema("dialog", "[g] Dialogue state", "") + ",\n" +
            toolSchema("widget", "[g] Widget query: m=get | pick (under cursor)",
                prop("m", "string", "Mode: get | pick") + "," +
                prop("g", "number", "Group") + "," +
                prop("c", "number", "Child")) + ",\n" +
            toolSchema("loginstate", "[g] Client login state (LOGGED_IN, LOGIN_SCREEN, AUTHENTICATOR, etc.)", "") + ",\n" +
            toolSchema("var", "[g] Varbit value: m=v varbitId=<int>",
                prop("m", "string", "Mode: v") + "," +
                prop("varbitId", "number", "Varbit ID")) + ",\n" +
            toolSchema("screenshot", "[g] Capture the game viewport as an inline PNG image", "") + ",\n" +
            toolSchema("menu", "[g] Query right-click menu entries currently at cursor", "") + ",\n" +
            toolSchema("chat", "[g] Read recent chat messages",
                prop("lines", "number", "Lines to read (default 10)")) + ",\n" +
            toolSchema("buffer",
                "[g] Delta-encoded state buffer. t>0 = absolute tick (full snapshot); t<0 = last |t| ticks (sparse deltas with added/removed/changed). Default t=-5.",
                prop("t", "number", "Tick: positive = absolute tick #; negative = last N ticks; default -5") + "," +
                prop("types", "string", "Entity types CSV: npc,obj,ground,player,otherplayer,skills (default all)") + "," +
                prop("names", "string", "Name filter CSV (case-insensitive)") + "," +
                prop("ids", "string", "ID filter CSV") + "," +
                prop("tile", "string", "Tile filter: \"x,y,plane\"") + "," +
                prop("area", "string", "Area filter: \"x1,y1,x2,y2,plane\"")) + ",\n" +
            toolSchema("actions",
                "[g] Recent MenuOptionClicked actions performed (user clicks + plugin/macro actions via public menu API). Newest-last.",
                prop("t", "number", "Last N actions to return (default 50)") + "," +
                prop("option", "string", "Substring filter on action option (case-insensitive)") + "," +
                prop("target", "string", "Substring filter on action target (case-insensitive)") + "," +
                prop("opcodes", "string", "MenuAction opcode IDs CSV") + "," +
                prop("ids", "string", "Identifier (npc index / object id / slot) CSV") + "," +
                prop("since", "number", "Only include actions at or after this tick")) +
            "\n]";
    }

    /**
     * Dispatch a tool call and return the result as a JSON string.
     * Executes on the client thread since RuneLite's Client is not thread-safe.
     */
    public String handleToolCall(String toolName, String args) {
        if ("screenshot".equals(toolName)) {
            try { return handleScreenshot(); }
            catch (Exception e) { return meta() + "Error: " + e.getMessage(); }
        }
        if ("loginstate".equals(toolName)) {
            try { return handleToolCallInner(toolName, args); }
            catch (Exception e) { return meta() + "Error: " + e.getMessage(); }
        }
        if ("buffer".equals(toolName)) {
            // buffer reads from StateBuffer (already thread-safe); no client-thread hop needed.
            try { return handleBuffer(args); }
            catch (Exception e) { return meta() + "Error: " + e.getMessage(); }
        }
        if ("actions".equals(toolName)) {
            // actions reads from ActionLog (already thread-safe); no client-thread hop needed.
            try { return handleActions(args); }
            catch (Exception e) { return meta() + "Error: " + e.getMessage(); }
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try {
                future.complete(handleToolCallInner(toolName, args));
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                future.complete(meta() + "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "\\n" + sw.toString().replace("\n", "\\n").replace("\"", "'"));
            }
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return meta() + "Error: Timeout waiting for client thread";
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
            case "var":             return handleVar(args);
            case "menu":            return handleMenu();
            case "chat":            return handleChatRead(args);
            default:
                return meta() + "Unknown tool: " + toolName;
        }
    }

    // ========== Tool Implementations ==========

    private String handleState(String args) {
        String inc = McpHttpServer.parseStringField(args, "inc");
        PlayerSnapshot p = world.getLocalPlayer();
        if (p == null) return meta() + "Not logged in";

        StringBuilder sb = new StringBuilder();
        sb.append(meta());

        Set<String> sections = inc != null
            ? new HashSet<>(Arrays.asList(inc.split(",")))
            : Set.of("player", "resources");

        if (sections.contains("player")) {
            sb.append(String.format("Player: %s pos=(%d,%d,%d) anim=%d idle=%s moving=%s\\n",
                p.name, p.worldX, p.worldY, p.plane, p.animation, p.idle, p.moving));
        }
        if (sections.contains("resources")) {
            sb.append(String.format("HP: %d/%d Prayer: %d/%d Run: %d Spec: %d\\n",
                p.currentHealth, p.maxHealth, p.prayerPoints, p.maxPrayerPoints,
                p.runEnergy / 100, p.specialAttackEnergy));
        }
        if (sections.contains("inventory")) {
            List<ItemSnapshot> inv = world.getInventory();
            sb.append("Inventory (").append(inv.size()).append("/28):\\n");
            for (ItemSnapshot item : inv) {
                sb.append(String.format("  [%d] %s x%d\\n", item.slot, item.name, item.quantity));
            }
        }
        if (sections.contains("equipment")) {
            List<ItemSnapshot> equip = world.getEquipment();
            sb.append("Equipment:\\n");
            for (ItemSnapshot item : equip) {
                sb.append(String.format("  [%d] %s\\n", item.slot, item.name));
            }
        }
        if (sections.contains("npcs")) {
            List<NpcSnapshot> npcs = world.getNpcs();
            sb.append("NPCs (").append(npcs.size()).append("):\\n");
            for (NpcSnapshot npc : npcs.stream().limit(10).collect(Collectors.toList())) {
                sb.append(String.format("  %s (id=%d idx=%d) pos=(%d,%d) hp=%d/%d\\n",
                    npc.name, npc.id, npc.index, npc.worldX, npc.worldY,
                    npc.healthRatio, npc.healthScale));
            }
        }
        if (sections.contains("skills")) {
            sb.append("Skills:\\n");
            String[] skillNames = {"Attack","Defence","Strength","Hitpoints","Ranged","Prayer",
                "Magic","Cooking","Woodcutting","Fletching","Fishing","Firemaking","Crafting",
                "Smithing","Mining","Herblore","Agility","Thieving","Slayer","Farming",
                "Runecraft","Hunter","Construction"};
            for (int i = 0; i < 23; i++) {
                int lvl = world.getSkillLevel(i);
                int boosted = world.getBoostedLevel(i);
                if (lvl > 1 || boosted > 1) {
                    sb.append(String.format("  %s: %d/%d\\n", skillNames[i], boosted, lvl));
                }
            }
        }

        return sb.toString();
    }

    private String handleNpc(String args) {
        List<NpcSnapshot> npcs = world.getNpcs();
        PlayerSnapshot local = world.getLocalPlayer();
        if (local == null) return meta() + "Not logged in";

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
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder(meta());
        if (total > limit) {
            sb.append("Found ").append(total).append(" NPCs (showing ").append(limit).append(" nearest)\\n");
        } else {
            sb.append("Found ").append(total).append(" NPCs\\n");
        }
        for (NpcSnapshot npc : sorted.stream().limit(limit).collect(Collectors.toList())) {
            int dist = Math.abs(npc.worldX - local.worldX) + Math.abs(npc.worldY - local.worldY);
            sb.append(String.format("  %s id=%d idx=%d pos=(%d,%d) dist=%d hp=%d/%d actions=%s\\n",
                npc.name, npc.id, npc.index, npc.worldX, npc.worldY, dist,
                npc.healthRatio, npc.healthScale,
                npc.actions != null ? Arrays.toString(npc.actions) : "[]"));
        }
        return sb.toString();
    }

    private String handleObj(String args) {
        List<ObjectSnapshot> objects = world.getObjects();
        PlayerSnapshot local = world.getLocalPlayer();
        if (local == null) return meta() + "Not logged in";

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
        StringBuilder sb = new StringBuilder(meta());
        if (total > limit) {
            sb.append("Found ").append(total).append(" objects (showing ").append(limit).append(" nearest)\\n");
        } else {
            sb.append("Found ").append(total).append(" objects\\n");
        }
        for (ObjectSnapshot obj : objects.stream()
                .sorted(Comparator.comparingInt(o ->
                    Math.abs(o.worldX - local.worldX) + Math.abs(o.worldY - local.worldY)))
                .limit(limit)
                .collect(Collectors.toList())) {
            sb.append(String.format("  %s id=%d pos=(%d,%d) actions=%s\\n",
                obj.name, obj.id, obj.worldX, obj.worldY,
                obj.actions != null ? Arrays.toString(obj.actions) : "[]"));
        }
        return sb.toString();
    }

    private String handleInv(String args) {
        String mode = McpHttpServer.parseStringField(args, "m");
        if (mode == null) mode = "q";
        List<ItemSnapshot> inv = world.getInventory();

        if ("s".equals(mode)) {
            return meta() + String.format("Inventory: %d/28 items, %d free slots",
                inv.size(), 28 - inv.size());
        }

        StringBuilder sb = new StringBuilder(meta());
        sb.append("Inventory (").append(inv.size()).append("/28):\\n");
        for (ItemSnapshot item : inv) {
            sb.append(String.format("  [%d] %s (id=%d) x%d actions=%s\\n",
                item.slot, item.name, item.id, item.quantity,
                item.actions != null ? Arrays.toString(item.actions) : "[]"));
        }
        return sb.toString();
    }

    private String handleEquip() {
        List<ItemSnapshot> equip = world.getEquipment();
        StringBuilder sb = new StringBuilder(meta());
        sb.append("Equipment:\\n");
        for (ItemSnapshot item : equip) {
            sb.append(String.format("  [%d] %s (id=%d)\\n", item.slot, item.name, item.id));
        }
        return sb.toString();
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
        List<GroundItemSnapshot> sorted = items;
        if (localP != null) {
            final PlayerSnapshot lp = localP;
            sorted = items.stream()
                .sorted(Comparator.comparingInt(it ->
                    Math.abs(it.worldX - lp.worldX) + Math.abs(it.worldY - lp.worldY)))
                .collect(Collectors.toList());
        }

        int total = sorted.size();
        int limit = 15;
        StringBuilder sb = new StringBuilder(meta());
        if (total > limit) {
            sb.append("Ground items (").append(total).append(", showing ").append(limit).append(" nearest):\\n");
        } else {
            sb.append("Ground items (").append(total).append("):\\n");
        }
        for (GroundItemSnapshot item : sorted.stream().limit(limit).collect(Collectors.toList())) {
            sb.append(String.format("  %s (id=%d) x%d pos=(%d,%d)\\n",
                item.name, item.id, item.quantity, item.worldX, item.worldY));
        }
        return sb.toString();
    }

    private String handleBank(String args) {
        InterfaceSnapshot iface = world.getInterfaces();
        StringBuilder sb = new StringBuilder(meta());
        sb.append("Bank open: ").append(iface.bankOpen).append("\\n");
        if (iface.bankOpen) {
            List<ItemSnapshot> items = world.getBankItems();
            int total = items.size();

            String name = extractName(args);
            if (name != null) {
                final String needle = name.toLowerCase();
                items = items.stream()
                    .filter(it -> it.name != null && it.name.toLowerCase().contains(needle))
                    .collect(Collectors.toList());
                sb.append("Bank items (").append(items.size())
                    .append(" matching '").append(name).append("' of ").append(total).append("):\\n");
            } else {
                sb.append("Bank items (").append(total).append("):\\n");
            }

            for (ItemSnapshot item : items.stream().limit(50).collect(Collectors.toList())) {
                sb.append(String.format("  [%d] %s (id=%d) x%d\\n",
                    item.slot, item.name, item.id, item.quantity));
            }
            if (items.size() > 50) sb.append("  ... (").append(items.size() - 50).append(" more)\\n");
        }
        return sb.toString();
    }

    private String handleDialog() {
        DialogueSnapshot d = world.getDialogue();
        return meta() + String.format("Dialogue open=%s canContinue=%s options=%s text=%s",
            d.open, d.canContinue, d.options, d.text);
    }

    private String handleWidget(String args) {
        String mode = McpHttpServer.parseStringField(args, "m");
        if (mode == null) mode = "get";

        if ("pick".equals(mode) || "p".equals(mode)) {
            return pickWidgetAtCursor();
        }

        String gStr = McpHttpServer.parseStringField(args, "g");
        String cStr = McpHttpServer.parseStringField(args, "c");
        if (gStr == null) return meta() + "Missing widget group";
        int group = Integer.parseInt(gStr);
        int child = cStr != null ? Integer.parseInt(cStr) : 0;

        if ("get".equals(mode) || "v".equals(mode)) {
            WidgetSnapshot w = world.getWidget(group, child);
            if (w == null) return meta() + "Widget not found: " + group + "." + child;
            return meta() + String.format("Widget %d.%d visible=%s text=%s itemId=%d",
                w.group, w.child, w.visible, w.text, w.itemId);
        }

        return meta() + "Unknown widget mode: " + mode + " (read-only: get|pick)";
    }

    private String handleLoginState() {
        int state = client.getGameState().getState();
        String name = client.getGameState().name();
        return meta() + String.format("{\\\"loginState\\\":\\\"%s\\\",\\\"stateId\\\":%d}", name, state);
    }

    private String handleVar(String args) {
        String mode = McpHttpServer.parseStringField(args, "m");
        if (mode == null) mode = "v";

        if ("v".equals(mode)) {
            String idStr = McpHttpServer.parseStringField(args, "varbitId");
            if (idStr == null) return meta() + "Missing varbitId";
            int id = Integer.parseInt(idStr);
            int value = world.getVarbitValue(id);
            return meta() + "Varbit " + id + " = " + value;
        }

        return meta() + "Var mode " + mode + " - basic implementation";
    }

    // ========== Widget Picker ==========

    private String pickWidgetAtCursor() {
        net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
        int mx = mousePos.getX();
        int my = mousePos.getY();

        StringBuilder sb = new StringBuilder(meta());
        sb.append("Mouse canvas pos: (").append(mx).append(",").append(my).append(")\\n");
        sb.append("Widgets at cursor:\\n");

        int found = 0;
        int[] groups = {149, 541, 548, 160, 162, 163, 593, 387, 320, 218, 116, 182, 399, 161, 164};
        for (int g : groups) {
            for (int c = 0; c < 120; c++) {
                net.runelite.api.widgets.Widget w = client.getWidget(g, c);
                if (w == null || w.isHidden()) continue;
                Rectangle bounds = w.getBounds();
                if (bounds.width <= 0 || bounds.height <= 0) continue;
                if (bounds.contains(mx, my)) {
                    String text = w.getText();
                    String name = w.getName();
                    int itemId = w.getItemId();
                    String[] actions = w.getActions();
                    String actionStr = "";
                    if (actions != null) {
                        List<String> nonNull = new ArrayList<>();
                        for (String a : actions) { if (a != null && !a.isEmpty()) nonNull.add(a); }
                        actionStr = nonNull.toString();
                    }
                    sb.append(String.format("  %d.%d bounds=(%d,%d,%d,%d) text='%s' name='%s' itemId=%d actions=%s\\n",
                        g, c, bounds.x, bounds.y, bounds.width, bounds.height,
                        text != null ? text : "", name != null ? name : "",
                        itemId, actionStr));
                    found++;

                    net.runelite.api.widgets.Widget[] children = w.getDynamicChildren();
                    if (children != null) {
                        for (net.runelite.api.widgets.Widget dc : children) {
                            if (dc == null || dc.isHidden()) continue;
                            Rectangle db = dc.getBounds();
                            if (db.width <= 0 || db.height <= 0) continue;
                            if (db.contains(mx, my)) {
                                String dText = dc.getText();
                                String dName = dc.getName();
                                String[] dActions = dc.getActions();
                                String dActionStr = "";
                                if (dActions != null) {
                                    List<String> nonNull = new ArrayList<>();
                                    for (String a : dActions) { if (a != null && !a.isEmpty()) nonNull.add(a); }
                                    dActionStr = nonNull.toString();
                                }
                                sb.append(String.format("    → child %d bounds=(%d,%d,%d,%d) text='%s' name='%s' actions=%s\\n",
                                    dc.getIndex(), db.x, db.y, db.width, db.height,
                                    dText != null ? dText : "", dName != null ? dName : "",
                                    dActionStr));
                            }
                        }
                    }
                }
            }
        }

        if (found == 0) {
            sb.append("  (no widgets found at cursor - may be game viewport)");
        }
        return sb.toString();
    }

    // ========== Screenshot ==========

    /**
     * Captures the rendered game frame via {@link DrawManager#requestNextFrameListener},
     * which delivers the actual canvas pixels — independent of whether other windows are
     * covering the RuneLite client on screen. Returns the raw base64-encoded PNG.
     * {@link McpHttpServer#handleToolsCall} special-cases the {@code screenshot} tool name
     * and wraps this payload in an MCP {@code image} content item so the client renders
     * it inline rather than as a base64 text blob.
     * Returns a string starting with {@code "Error:"} on failure.
     */
    private String handleScreenshot() {
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

        try {
            BufferedImage img = future.get(2, TimeUnit.SECONDS);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (java.util.concurrent.TimeoutException e) {
            return "Error: Timed out waiting for next rendered frame";
        } catch (Exception e) {
            return "Error capturing screenshot: " + e.getMessage();
        }
    }

    // ========== Menu ==========

    private String handleMenu() {
        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) {
            return meta() + "No menu entries at cursor position";
        }

        StringBuilder sb = new StringBuilder(meta());
        sb.append("Menu entries (").append(entries.length).append(", last=default left-click):\\n");
        for (int i = entries.length - 1; i >= 0; i--) {
            MenuEntry e = entries[i];
            String option = e.getOption() != null ? e.getOption() : "";
            String entryTarget = e.getTarget() != null ? e.getTarget() : "";
            entryTarget = entryTarget.replaceAll("<[^>]+>", "").trim();
            int p0 = e.getParam0(), p1 = e.getParam1();
            int wg = p1 >> 16, wc = p1 & 0xFFFF;
            sb.append(String.format("  [%d] %s %s (type=%s id=%d p0=%d widget=%d.%d)%s\\n",
                i, option, entryTarget, e.getType(), e.getIdentifier(), p0, wg, wc,
                i == entries.length - 1 ? " ← LEFT-CLICK" : ""));
        }
        return sb.toString();
    }

    // ========== Chat ==========

    private String handleChatRead(String args) {
        String linesStr = McpHttpServer.parseStringField(args, "lines");
        int maxLines = linesStr != null ? Integer.parseInt(linesStr) : 10;

        List<String> messages = new ArrayList<>();
        try {
            for (MessageNode node : client.getMessages()) {
                if (node == null) continue;
                String type = node.getType().name();
                String sender = node.getName();
                String text = node.getValue();
                if (text == null || text.isEmpty()) continue;
                text = text.replaceAll("<[^>]+>", "").trim();
                if (sender != null && !sender.isEmpty()) {
                    sender = sender.replaceAll("<[^>]+>", "").trim();
                    messages.add("[" + type + "] " + sender + ": " + text);
                } else {
                    messages.add("[" + type + "] " + text);
                }
                if (messages.size() >= maxLines) break;
            }
        } catch (Exception e) {
            return meta() + "Error reading chat: " + e.getMessage();
        }

        if (messages.isEmpty()) {
            return meta() + "No chat messages";
        }

        StringBuilder sb = new StringBuilder(meta());
        sb.append("Chat (").append(messages.size()).append(" messages):\\n");
        for (int i = messages.size() - 1; i >= 0; i--) {
            sb.append("  ").append(messages.get(i)).append("\\n");
        }
        return sb.toString();
    }

    // ========== Helpers ==========

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
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
        return out.isEmpty() ? null : out;
    }

    private String meta() {
        return "{\\\"_meta\\\":{\\\"gameTick\\\":" + client.getTickCount() + "}} ";
    }

    // ========== Buffer ==========

    private String handleBuffer(String args) {
        return new BufferQueryHandler(stateBuffer, client.getTickCount()).handle(args);
    }

    // ========== Actions ==========

    private String handleActions(String args) {
        return new ActionsQueryHandler(actionLog, client.getTickCount()).handle(args);
    }

    private static String toolSchema(String name, String description, String properties) {
        return "{\"name\":\"" + McpHttpServer.escapeJsonString(name) +
            "\",\"description\":\"" + McpHttpServer.escapeJsonString(description) +
            "\",\"inputSchema\":{\"type\":\"object\"" +
            (properties.isEmpty() ? "" : ",\"properties\":{" + properties + "}") + "}}";
    }

    private static String prop(String name, String type, String description) {
        return "\"" + McpHttpServer.escapeJsonString(name) +
            "\":{\"type\":\"" + McpHttpServer.escapeJsonString(type) +
            "\",\"description\":\"" + McpHttpServer.escapeJsonString(description) + "\"}";
    }
}
