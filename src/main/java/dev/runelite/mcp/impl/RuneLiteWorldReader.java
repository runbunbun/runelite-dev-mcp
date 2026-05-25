package dev.runelite.mcp.impl;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import dev.runelite.mcp.api.WorldReader;
import dev.runelite.mcp.api.snapshot.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldReader backed by the RuneLite Client API.
 * All methods return immutable snapshots — no references to live client objects leak out.
 */
public class RuneLiteWorldReader implements WorldReader {

    private final Client client;
    private final SceneScanner sceneScanner;
    // Lazily-initialized resolver used solely by getEntityScreenCoverage —
    // a separate instance is maintained by ExecutorPlugin for click dispatch.
    private volatile ClickboxResolver coverageResolver;

    // Ground item cache
    private final ConcurrentHashMap<String, GroundItemSnapshot> groundItems = new ConcurrentHashMap<>();

    // Hitsplat buffer — populated from HitsplatApplied events in the
    // plugin layer, read at snapshot-capture time to populate each
    // actor's recentHitsplats. Keyed by Actor identity so NPCs and
    // players are buffered independently. WeakHashMap avoids holding
    // Actor references past their natural lifetime (despawn).
    private static final int HITSPLAT_MAX_AGE_TICKS = 10;
    private static final class TickedHit {
        final int amount, type, tickApplied;
        TickedHit(int a, int t, int tick) { amount = a; type = t; tickApplied = tick; }
    }
    private final WeakHashMap<Actor, LinkedList<TickedHit>> hitsplatBuf = new WeakHashMap<>();

    public RuneLiteWorldReader(Client client) {
        this.client = client;
        this.sceneScanner = new SceneScanner(client);
    }

    public void addGroundItem(TileItem item, Tile tile) {
        WorldPoint wp = tile.getWorldLocation();
        String key = item.getId() + ":" + wp.getX() + ":" + wp.getY() + ":" + wp.getPlane();
        ItemComposition comp = client.getItemDefinition(item.getId());
        String name = comp != null ? comp.getName() : "Unknown";
        groundItems.put(key, new GroundItemSnapshot(
            item.getId(), name, item.getQuantity(), wp.getX(), wp.getY(), wp.getPlane()));
    }

    public void removeGroundItem(TileItem item, Tile tile) {
        WorldPoint wp = tile.getWorldLocation();
        String key = item.getId() + ":" + wp.getX() + ":" + wp.getY() + ":" + wp.getPlane();
        groundItems.remove(key);
    }

    /**
     * Funnels {@code HitsplatApplied} into the per-actor buffer that backs each
     * {@code PlayerSnapshot}/{@code NpcSnapshot}'s {@code recentHitsplats}. Registered on
     * RuneLite's EventBus by {@link dev.runelite.mcp.DevMcpPlugin} at startUp.
     */
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied e) {
        Hitsplat h = e.getHitsplat();
        if (h == null) return;
        recordHitsplat(e.getActor(), h.getAmount(), h.getHitsplatType(), client.getTickCount());
    }

    /** Ages out stale entries from the per-actor hitsplat buffer once per tick. */
    @Subscribe
    public void onGameTick(GameTick e) {
        pruneHitsplats(client.getTickCount());
    }

    public void recordHitsplat(Actor actor, int amount, int type, int currentTick) {
        if (actor == null) return;
        synchronized (hitsplatBuf) {
            hitsplatBuf.computeIfAbsent(actor, k -> new LinkedList<>())
                .add(new TickedHit(amount, type, currentTick));
        }
    }

    public void pruneHitsplats(int currentTick) {
        synchronized (hitsplatBuf) {
            Iterator<Map.Entry<Actor, LinkedList<TickedHit>>> it = hitsplatBuf.entrySet().iterator();
            while (it.hasNext()) {
                LinkedList<TickedHit> list = it.next().getValue();
                list.removeIf(h -> currentTick - h.tickApplied > HITSPLAT_MAX_AGE_TICKS);
                if (list.isEmpty()) it.remove();
            }
        }
    }

    private List<HitsplatData> readHitsplats(Actor actor) {
        synchronized (hitsplatBuf) {
            LinkedList<TickedHit> list = hitsplatBuf.get(actor);
            if (list == null || list.isEmpty()) return Collections.emptyList();
            int currentTick = client.getTickCount();
            List<HitsplatData> out = new ArrayList<>(list.size());
            for (TickedHit h : list) {
                out.add(new HitsplatData(h.amount, h.type, currentTick - h.tickApplied));
            }
            return out;
        }
    }

    @Override
    public PlayerSnapshot getLocalPlayer() {
        Player p = client.getLocalPlayer();
        if (p == null) return null;
        return toPlayerSnapshot(p);
    }

    @Override
    public List<PlayerSnapshot> getOtherPlayers() {
        List<PlayerSnapshot> result = new ArrayList<>();
        for (Player p : client.getPlayers()) {
            if (p != null && p != client.getLocalPlayer()) {
                result.add(toPlayerSnapshot(p));
            }
        }
        return result;
    }

    @Override
    public List<NpcSnapshot> getNpcs() {
        List<NpcSnapshot> result = new ArrayList<>();
        for (NPC npc : client.getNpcs()) {
            if (npc == null) continue;
            NPCComposition comp = npc.getTransformedComposition();
            if (comp == null) comp = npc.getComposition();
            String name = comp != null ? comp.getName() : null;
            String[] actions = comp != null ? comp.getActions() : new String[0];
            int combatLevel = comp != null ? comp.getCombatLevel() : 0;
            int size = comp != null ? comp.getSize() : 1;

            WorldPoint wp = npc.getWorldLocation();
            Actor interacting = npc.getInteracting();
            int interactingIdx = -1;
            String interactingType = null;
            String interactingName = null;
            if (interacting instanceof NPC) {
                interactingIdx = ((NPC) interacting).getIndex();
                interactingType = "npc";
                interactingName = ((NPC) interacting).getName();
            } else if (interacting instanceof Player) {
                interactingIdx = client.getPlayers().indexOf((Player) interacting);
                interactingType = "player";
                interactingName = ((Player) interacting).getName();
            }

            boolean inCombat = npc.getInteracting() != null
                && npc.getInteracting() instanceof Player;
            // NPC.getOverheadIcon() is not on the runelite-api 1.12.x surface; skip for NPCs.
            int overheadIcon = -1;

            result.add(new NpcSnapshot(
                npc.getIndex(), npc.getId(), name, combatLevel,
                wp.getX(), wp.getY(), wp.getPlane(),
                npc.getHealthRatio(), npc.getHealthScale(),
                npc.getAnimation(),
                npc.getInteracting() != null, interactingIdx,
                interactingType, interactingName,
                size, npc.getOrientation(), overheadIcon, inCombat,
                actions, readHitsplats(npc)
            ));
        }
        return result;
    }

    @Override
    public List<ObjectSnapshot> getObjects() {
        return sceneScanner.scanObjects();
    }

    @Override
    public List<GroundItemSnapshot> getGroundItems() {
        return new ArrayList<>(groundItems.values());
    }

    // Cap how much per-tick scene-effect data we capture. Normal scenes have
    // far fewer than this; bosses with heavy multi-projectile patterns
    // (Hueycoatl, Verzik) may approach but rarely exceed.
    private static final int MAX_CAPTURED_PROJECTILES = 16;
    private static final int MAX_CAPTURED_GRAPHICS_OBJECTS = 16;
    private static final int CYCLES_PER_TICK = 30;

    @Override
    public List<ProjectileSnapshot> getProjectiles() {
        Player local = client.getLocalPlayer();
        List<ProjectileSnapshot> result = new ArrayList<>();
        for (Projectile p : client.getProjectiles()) {
            if (p == null) continue;
            int spotanim = p.getId();

            WorldPoint sourceWp = p.getSourcePoint();
            int sx = sourceWp != null ? sourceWp.getX() : -1;
            int sy = sourceWp != null ? sourceWp.getY() : -1;
            int sPlane = sourceWp != null ? sourceWp.getPlane() : -1;

            Actor sourceActor = p.getSourceActor();
            int sourceIdx = -1;
            String sourceType = null;
            boolean sourceIsLocal = false;
            if (sourceActor instanceof NPC) {
                sourceIdx = ((NPC) sourceActor).getIndex();
                sourceType = "npc";
            } else if (sourceActor instanceof Player) {
                sourceIdx = client.getPlayers().indexOf((Player) sourceActor);
                sourceType = "player";
                sourceIsLocal = (sourceActor == local);
            }

            WorldPoint targetWp = p.getTargetPoint();
            int tx = targetWp != null ? targetWp.getX() : -1;
            int ty = targetWp != null ? targetWp.getY() : -1;
            int tPlane = targetWp != null ? targetWp.getPlane() : -1;

            Actor targetActor = p.getTargetActor();
            int targetIdx = -1;
            String targetType = null;
            boolean targetIsLocal = false;
            if (targetActor instanceof NPC) {
                targetIdx = ((NPC) targetActor).getIndex();
                targetType = "npc";
            } else if (targetActor instanceof Player) {
                targetIdx = client.getPlayers().indexOf((Player) targetActor);
                targetType = "player";
                targetIsLocal = (targetActor == local);
            }

            int remCycles = Math.max(0, p.getRemainingCycles());
            int ticksToImpact = (remCycles + CYCLES_PER_TICK - 1) / CYCLES_PER_TICK;

            result.add(new ProjectileSnapshot(
                spotanim,
                sx, sy, sPlane,
                sourceIdx, sourceType, sourceIsLocal,
                tx, ty, tPlane,
                targetIdx, targetType, targetIsLocal,
                remCycles, ticksToImpact,
                p.getStartHeight(), p.getEndHeight(), p.getSlope()
            ));
        }
        // Sort: player-target first, then ascending ticks-to-impact.
        result.sort((a, b) -> {
            if (a.targetIsLocalPlayer != b.targetIsLocalPlayer) {
                return a.targetIsLocalPlayer ? -1 : 1;
            }
            return Integer.compare(a.ticksToImpact, b.ticksToImpact);
        });
        if (result.size() > MAX_CAPTURED_PROJECTILES) {
            return new ArrayList<>(result.subList(0, MAX_CAPTURED_PROJECTILES));
        }
        return result;
    }

    @Override
    public List<GraphicsObjectSnapshot> getGraphicsObjects() {
        int currentCycle = client.getGameCycle();
        List<GraphicsObjectSnapshot> result = new ArrayList<>();
        for (GraphicsObject g : client.getGraphicsObjects()) {
            if (g == null) continue;
            LocalPoint lp = g.getLocation();
            if (lp == null) continue;
            WorldPoint wp = WorldPoint.fromLocal(client, lp);
            if (wp == null) continue;

            int startCycle = g.getStartCycle();
            int ageCycles = Math.max(0, currentCycle - startCycle);
            int ageTicks = ageCycles / CYCLES_PER_TICK;

            int frame = -1;
            try {
                frame = g.getAnimationFrame();
            } catch (Throwable t) {
                // Some RuneLite versions return -1 / throw on a null Animation;
                // a missing frame is fine — model can still use (id, age).
            }

            result.add(new GraphicsObjectSnapshot(
                g.getId(),
                wp.getX(), wp.getY(), wp.getPlane(),
                g.getZ(), startCycle, ageTicks, frame, g.finished()
            ));
        }
        // Sort: not-finished first, then by Chebyshev distance to player,
        // then by ascending age.
        Player local = client.getLocalPlayer();
        WorldPoint here = local != null ? local.getWorldLocation() : null;
        final int hx = here != null ? here.getX() : 0;
        final int hy = here != null ? here.getY() : 0;
        result.sort((a, b) -> {
            if (a.finished != b.finished) return a.finished ? 1 : -1;
            int da = Math.max(Math.abs(a.worldX - hx), Math.abs(a.worldY - hy));
            int db = Math.max(Math.abs(b.worldX - hx), Math.abs(b.worldY - hy));
            if (da != db) return Integer.compare(da, db);
            return Integer.compare(a.ageTicks, b.ageTicks);
        });
        if (result.size() > MAX_CAPTURED_GRAPHICS_OBJECTS) {
            return new ArrayList<>(result.subList(0, MAX_CAPTURED_GRAPHICS_OBJECTS));
        }
        return result;
    }

    @Override
    public List<ItemSnapshot> getInventory() {
        return readContainer(InventoryID.INVENTORY);
    }

    @Override
    public List<ItemSnapshot> getEquipment() {
        return readContainer(InventoryID.EQUIPMENT);
    }

    @Override
    public List<ItemSnapshot> getBankItems() {
        return readContainer(InventoryID.BANK);
    }

    @Override
    public String getItemName(int itemId) {
        if (itemId <= 0) return null;
        try {
            ItemComposition comp = client.getItemDefinition(itemId);
            if (comp == null) return null;
            String name = comp.getName();
            // RuneLite returns "null" (literal) for non-cached / unknown IDs.
            return (name == null || "null".equalsIgnoreCase(name)) ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getSkillLevel(int skillId) {
        Skill skill = Skill.values()[skillId];
        return client.getRealSkillLevel(skill);
    }

    @Override
    public int getBoostedLevel(int skillId) {
        Skill skill = Skill.values()[skillId];
        return client.getBoostedSkillLevel(skill);
    }

    @Override
    public int getSkillXp(int skillId) {
        Skill skill = Skill.values()[skillId];
        return client.getSkillExperience(skill);
    }

    @Override
    public int getVarbitValue(int varbitId) {
        return client.getVarbitValue(varbitId);
    }

    @Override
    public int getVarpValue(int varpId) {
        return client.getVarpValue(varpId);
    }

    @Override
    public boolean isWidgetVisible(int group, int child) {
        Widget w = client.getWidget(group, child);
        return w != null && !w.isHidden();
    }

    @Override
    public String getWidgetText(int group, int child) {
        Widget w = client.getWidget(group, child);
        return w != null ? w.getText() : null;
    }

    @Override
    public WidgetSnapshot getWidget(int group, int child) {
        Widget w = client.getWidget(group, child);
        if (w == null) return null;
        return new WidgetSnapshot(
            group, child, !w.isHidden(), w.getText(),
            w.getItemId(), w.getItemQuantity(), w.getActions()
        );
    }

    @Override
    public DialogueSnapshot getDialogue() {
        // Check standard dialogue widgets
        Widget npcDialog = client.getWidget(231, 4);  // NPC dialogue text
        Widget playerDialog = client.getWidget(217, 4);  // Player dialogue
        Widget optionsWidget = client.getWidget(233, 0);  // Options
        Widget continueWidget = client.getWidget(231, 5);  // Continue button

        boolean open = (npcDialog != null && !npcDialog.isHidden())
            || (playerDialog != null && !playerDialog.isHidden())
            || (optionsWidget != null && !optionsWidget.isHidden());

        if (!open) return DialogueSnapshot.closed();

        List<String> options = new ArrayList<>();
        if (optionsWidget != null && !optionsWidget.isHidden()) {
            Widget[] children = optionsWidget.getDynamicChildren();
            if (children != null) {
                for (Widget child : children) {
                    if (child != null && child.getText() != null && !child.getText().isEmpty()) {
                        options.add(child.getText());
                    }
                }
            }
        }

        boolean canContinue = continueWidget != null && !continueWidget.isHidden();
        String text = npcDialog != null ? npcDialog.getText() : null;
        String speaker = null;
        Widget nameWidget = client.getWidget(231, 3);
        if (nameWidget != null) speaker = nameWidget.getText();

        return new DialogueSnapshot(open, canContinue, !options.isEmpty(),
            options, text, speaker);
    }

    @Override
    public InterfaceSnapshot getInterfaces() {
        boolean bankOpen = isWidgetVisible(12, 1);
        boolean shopOpen = isWidgetVisible(300, 0);
        boolean geOpen = isWidgetVisible(465, 0);
        boolean tradeOpen = isWidgetVisible(335, 0);
        boolean prodOpen = isWidgetVisible(270, 0);

        // Active tab: check the root widget of each tab-panel. Exactly one
        // sidebar tab content widget should be visible at any time. Order
        // reflects the common-case hit rate (inventory is by far most common).
        String activeTab = null;
        if (isWidgetVisible(149, 0))      activeTab = "inventory";
        else if (isWidgetVisible(541, 0)) activeTab = "prayer";
        else if (isWidgetVisible(593, 0)) activeTab = "combat";
        else if (isWidgetVisible(387, 0)) activeTab = "equipment";
        else if (isWidgetVisible(218, 0)) activeTab = "magic";
        else if (isWidgetVisible(320, 0)) activeTab = "skills";
        else if (isWidgetVisible(399, 0)) activeTab = "quests";

        return new InterfaceSnapshot(bankOpen, shopOpen, geOpen, tradeOpen, prodOpen,
            "STANDARD", activeTab);
    }

    @Override
    public int getGameTick() {
        return client.getTickCount();
    }

    @Override
    public int getGameState() {
        return client.getGameState().getState();
    }

    @Override
    public int getEnergy() {
        return client.getEnergy();
    }

    @Override
    public int getWeight() {
        return client.getWeight();
    }

    @Override
    public int getSceneBaseX() {
        return client.getBaseX();
    }

    @Override
    public int getSceneBaseY() {
        return client.getBaseY();
    }

    @Override
    public boolean isInInstance() {
        return client.isInInstancedRegion();
    }

    // --- Camera / viewport ---

    @Override
    public int getCameraYaw() {
        return client.getCameraYaw();
    }

    @Override
    public int getCameraPitch() {
        return client.getCameraPitch();
    }

    @Override
    public int getCameraX() {
        return client.getCameraX();
    }

    @Override
    public int getCameraY() {
        return client.getCameraY();
    }

    @Override
    public int getCameraZ() {
        return client.getCameraZ();
    }

    @Override
    public boolean isWorldPointOnScreen(int worldX, int worldY, int plane) {
        LocalPoint lp = LocalPoint.fromWorld(client, worldX, worldY);
        if (lp == null) return false;
        net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
        if (p == null) return false;
        int w = client.getCanvasWidth();
        int h = client.getCanvasHeight();
        // Tight margin so edge hits don't count as visible.
        int margin = 16;
        return p.getX() >= margin && p.getY() >= margin
            && p.getX() <= w - margin && p.getY() <= h - margin;
    }

    @Override
    public int getCanvasWidth() {
        return client.getCanvasWidth();
    }

    @Override
    public int getCanvasHeight() {
        return client.getCanvasHeight();
    }

    @Override
    public int getCameraZoom() {
        return client.get3dZoom();
    }

    @Override
    public float getEntityScreenCoverage(String type, int identifier,
                                         int worldX, int worldY, int plane) {
        java.awt.Rectangle bounds = resolveEntityBounds(type, identifier, worldX, worldY, plane);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return 0.0f;
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        int x0 = Math.max(0, bounds.x);
        int y0 = Math.max(0, bounds.y);
        int x1 = Math.min(cw, bounds.x + bounds.width);
        int y1 = Math.min(ch, bounds.y + bounds.height);
        float visible = Math.max(0, x1 - x0) * (float) Math.max(0, y1 - y0);
        float total = (float) bounds.width * bounds.height;
        return total > 0 ? visible / total : 0.0f;
    }

    @Override
    public float getEntityMaxBoundFraction(String type, int identifier,
                                           int worldX, int worldY, int plane) {
        java.awt.Rectangle bounds = resolveEntityBounds(type, identifier, worldX, worldY, plane);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return 0f;
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        if (cw <= 0 || ch <= 0) return 0f;
        float wFrac = bounds.width / (float) cw;
        float hFrac = bounds.height / (float) ch;
        return Math.max(wFrac, hFrac);
    }

    private java.awt.Rectangle resolveEntityBounds(String type, int identifier,
                                                   int worldX, int worldY, int plane) {
        if (coverageResolver == null) coverageResolver = new ClickboxResolver(client);
        java.util.Optional<java.awt.Rectangle> box;
        switch (type == null ? "" : type) {
            case "npc":        box = coverageResolver.npcClickbox(identifier); break;
            case "object":     box = coverageResolver.objectClickbox(identifier, worldX, worldY, plane); break;
            case "player":     box = coverageResolver.playerClickbox(identifier); break;
            case "groundItem": box = coverageResolver.groundItemClickbox(worldX, worldY); break;
            default: return null;
        }
        return box.orElse(null);
    }

    // --- Private helpers ---

    private PlayerSnapshot toPlayerSnapshot(Player p) {
        WorldPoint wp = p.getWorldLocation();
        Actor interacting = p.getInteracting();
        int interactingIdx = -1;
        String interactingType = null;
        String interactingName = null;
        if (interacting instanceof NPC) {
            interactingIdx = ((NPC) interacting).getIndex();
            interactingType = "npc";
            interactingName = ((NPC) interacting).getName();
        } else if (interacting instanceof Player) {
            interactingIdx = client.getPlayers().indexOf((Player) interacting);
            interactingType = "player";
            interactingName = ((Player) interacting).getName();
        }

        // Equipment container for weapon ID
        int weaponId = -1;
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equip != null) {
            Item weapon = equip.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
            if (weapon != null) weaponId = weapon.getId();
        }

        // Health/prayer/run/spec from skills and vars
        int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        int prayerPts = client.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        int runEnergy = client.getEnergy();
        int specEnergy = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10;
        // VarPlayer.RUN isn't on the runelite-api 1.12.x surface; use the raw id.
        boolean runEnabled = client.getVarpValue(173) == 1;

        // Status effects from varps + varbits
        int poisonVarp = client.getVarpValue(VarPlayer.POISON);
        boolean poisoned = poisonVarp > 0 && poisonVarp < 1000000;
        boolean venomed = poisonVarp >= 1000000;
        // Negative POISON varp = immunity timer counting up to 0. Threshold -38 separates
        // ordinary poison immunity from venom immunity (set by anti-venom / anti-venom+).
        boolean antiPoisoned = poisonVarp < 0;
        boolean antiVenomed = poisonVarp <= -38;
        boolean staminaBoosted = client.getVarbitValue(Varbits.STAMINA_EFFECT) == 1;
        boolean antifired = client.getVarbitValue(Varbits.ANTIFIRE) > 0;
        // SUPERANTIFIRE isn't on the runelite-api 1.12.x Varbits surface; use the raw id.
        boolean superAntifired = client.getVarbitValue(3984) > 0;

        // Active prayers — check each prayer's varbit
        List<String> activePrayers = new ArrayList<>();
        for (Prayer prayer : Prayer.values()) {
            if (client.isPrayerActive(prayer)) {
                activePrayers.add(prayer.name());
            }
        }

        return new PlayerSnapshot(
            p.getName(),
            wp.getX(), wp.getY(), wp.getPlane(),
            p.getCombatLevel(), p.getAnimation(),
            client.getLocalDestinationLocation() != null, // moving (has a walk destination)
            p.getAnimation() != -1,                        // animating
            client.getLocalDestinationLocation() == null && p.getAnimation() == -1, // idle
            currentHp, maxHp,
            p.getHealthRatio(), p.getHealthScale(),
            prayerPts, maxPrayer,
            runEnergy, specEnergy, runEnabled,
            poisoned, venomed,
            staminaBoosted, antifired, superAntifired, antiPoisoned, antiVenomed,
            p.getOverheadIcon() != null ? p.getOverheadIcon().ordinal() : -1,
            p.getSkullIcon(),
            weaponId,
            interacting != null, interactingIdx, interactingType, interactingName,
            activePrayers,
            readHitsplats(p)
        );
    }

    private List<ItemSnapshot> readContainer(InventoryID containerId) {
        ItemContainer container = client.getItemContainer(containerId);
        if (container == null) return Collections.emptyList();

        Item[] items = container.getItems();
        List<ItemSnapshot> result = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            if (item == null || item.getId() == -1) continue;

            ItemComposition comp = client.getItemDefinition(item.getId());
            String name = comp != null ? comp.getName() : "Unknown";
            String[] actions = comp != null ? comp.getInventoryActions() : new String[0];

            result.add(new ItemSnapshot(
                item.getId(), name, item.getQuantity(), i, actions
            ));
        }
        return result;
    }
}
