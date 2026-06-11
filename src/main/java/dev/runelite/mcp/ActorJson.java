package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.runelite.mcp.api.snapshot.HitsplatData;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;

import java.util.List;

final class ActorJson {
    private ActorJson() {
    }

    static JsonObject player(PlayerSnapshot p) {
        JsonObject o = new JsonObject();
        if (p.name != null) o.addProperty("name", p.name);
        o.add("pos", posArray(p.worldX, p.worldY, p.plane));
        o.addProperty("combatLevel", p.combatLevel);
        o.addProperty("animation", p.animation);
        o.addProperty("idle", p.idle);
        o.addProperty("moving", p.moving);
        o.addProperty("animating", p.animating);
        o.add("hp", playerHp(p));

        // Preserve legacy scalar fields used by buffer consumers.
        o.addProperty("currentHealth", p.currentHealth);
        o.addProperty("maxHealth", p.maxHealth);
        o.addProperty("healthRatio", p.healthRatio);
        o.addProperty("healthScale", p.healthScale);
        o.addProperty("prayerPoints", p.prayerPoints);
        o.addProperty("maxPrayerPoints", p.maxPrayerPoints);
        o.addProperty("runEnergy", p.runEnergy);
        o.addProperty("specialAttackEnergy", p.specialAttackEnergy);
        o.addProperty("runEnabled", p.runEnabled);

        o.addProperty("overheadIcon", p.overheadIcon);
        o.addProperty("skullIcon", p.skullIcon);
        o.addProperty("weaponId", p.weaponId);
        o.add("interacting", interaction(p.interacting, p.interactingType,
            p.interactingIndex, p.interactingName));
        if (p.interactingName != null) o.addProperty("interactingName", p.interactingName);
        if (p.interactingType != null) o.addProperty("interactingType", p.interactingType);
        if (p.interactingIndex >= 0) o.addProperty("interactingIndex", p.interactingIndex);
        o.add("activePrayers", strings(p.activePrayers));
        o.add("recentHitsplats", hitsplats(p.recentHitsplats));
        return o;
    }

    static JsonObject npcSummary(NpcSnapshot n) {
        JsonObject o = npcBase(n);
        o.addProperty("animation", n.animation);
        o.addProperty("size", n.size);
        o.addProperty("inCombat", n.inCombat);
        o.add("interacting", interaction(n.interacting, n.interactingType,
            n.interactingIndex, n.interactingName));
        if (n.interactingName != null) o.addProperty("interactingName", n.interactingName);
        if (n.interactingType != null) o.addProperty("interactingType", n.interactingType);
        if (n.interactingIndex >= 0) o.addProperty("interactingIndex", n.interactingIndex);
        return o;
    }

    static JsonObject npcDetail(NpcSnapshot n) {
        JsonObject o = npcSummary(n);
        o.addProperty("orientation", n.orientation);
        o.addProperty("overheadIcon", n.overheadIcon);
        if (n.actions != null) {
            JsonArray a = nonEmptyActions(n.actions);
            if (a.size() > 0) o.add("actions", a);
        }
        o.add("recentHitsplats", hitsplats(n.recentHitsplats));
        return o;
    }

    static JsonArray posArray(int x, int y, int plane) {
        JsonArray a = new JsonArray();
        a.add(x); a.add(y); a.add(plane);
        return a;
    }

    private static JsonObject npcBase(NpcSnapshot n) {
        JsonObject o = new JsonObject();
        o.addProperty("index", n.index);
        o.addProperty("id", n.id);
        if (n.name != null) o.addProperty("name", n.name);
        o.addProperty("combatLevel", n.combatLevel);
        o.add("pos", posArray(n.worldX, n.worldY, n.plane));
        o.add("hp", npcHp(n));

        // Preserve legacy scalar fields used by buffer consumers.
        o.addProperty("healthRatio", n.healthRatio);
        o.addProperty("healthScale", n.healthScale);
        return o;
    }

    private static JsonObject playerHp(PlayerSnapshot p) {
        JsonObject hp = new JsonObject();
        hp.addProperty("current", p.currentHealth);
        hp.addProperty("max", p.maxHealth);
        hp.addProperty("ratio", p.healthRatio);
        hp.addProperty("scale", p.healthScale);
        return hp;
    }

    private static JsonObject npcHp(NpcSnapshot n) {
        JsonObject hp = new JsonObject();
        hp.addProperty("ratio", n.healthRatio);
        hp.addProperty("scale", n.healthScale);
        return hp;
    }

    private static JsonObject interaction(boolean active, String type, int index, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("active", active);
        if (type != null) o.addProperty("type", type);
        if (index >= 0) o.addProperty("index", index);
        if (name != null) o.addProperty("name", name);
        return o;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        if (values == null) return a;
        for (String value : values) {
            if (value != null) a.add(value);
        }
        return a;
    }

    private static JsonArray hitsplats(List<HitsplatData> hits) {
        JsonArray a = new JsonArray();
        if (hits == null) return a;
        for (HitsplatData hit : hits) {
            if (hit == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("amount", hit.amount);
            o.addProperty("type", hit.type);
            o.addProperty("ticksAgo", hit.ticksAgo);
            a.add(o);
        }
        return a;
    }

    private static JsonArray nonEmptyActions(String[] actions) {
        JsonArray a = new JsonArray();
        for (String action : actions) {
            if (action != null && !action.isEmpty()) a.add(action);
        }
        return a;
    }
}
