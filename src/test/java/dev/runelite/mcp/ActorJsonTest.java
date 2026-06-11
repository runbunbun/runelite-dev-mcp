package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.runelite.mcp.api.snapshot.HitsplatData;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorJsonTest {
    @Test
    void playerJsonIncludesNestedHpAndInteraction() {
        PlayerSnapshot player = player("Local", 3200, 3201, 0,
            true, "npc", 42, "Goblin");

        JsonObject json = ActorJson.player(player);

        JsonObject hp = json.getAsJsonObject("hp");
        assertEquals(83, hp.get("current").getAsInt());
        assertEquals(99, hp.get("max").getAsInt());
        assertEquals(7, hp.get("ratio").getAsInt());
        assertEquals(10, hp.get("scale").getAsInt());

        JsonObject interacting = json.getAsJsonObject("interacting");
        assertTrue(interacting.get("active").getAsBoolean());
        assertEquals("npc", interacting.get("type").getAsString());
        assertEquals(42, interacting.get("index").getAsInt());
        assertEquals("Goblin", interacting.get("name").getAsString());

        assertTrue(json.get("animating").getAsBoolean());
        assertEquals(4151, json.get("weaponId").getAsInt());
        assertEquals("PROTECT_FROM_MELEE",
            json.getAsJsonArray("activePrayers").get(0).getAsString());
        assertEquals(1, json.getAsJsonArray("recentHitsplats").size());
    }

    @Test
    void npcDetailIncludesCombatContextActionsAndHitsplats() {
        NpcSnapshot npc = npc(12, 101, "Guard", true, "player", 0, "Local");

        JsonObject json = ActorJson.npcDetail(npc);

        JsonObject hp = json.getAsJsonObject("hp");
        assertEquals(4, hp.get("ratio").getAsInt());
        assertEquals(8, hp.get("scale").getAsInt());

        JsonObject interacting = json.getAsJsonObject("interacting");
        assertTrue(interacting.get("active").getAsBoolean());
        assertEquals("player", interacting.get("type").getAsString());
        assertEquals("Local", interacting.get("name").getAsString());

        assertEquals(2, json.get("size").getAsInt());
        assertEquals(1024, json.get("orientation").getAsInt());
        assertTrue(json.get("inCombat").getAsBoolean());

        JsonArray actions = json.getAsJsonArray("actions");
        assertEquals("Attack", actions.get(0).getAsString());
        assertEquals(1, json.getAsJsonArray("recentHitsplats").size());
    }

    @Test
    void npcSummaryOmitsNoisyDetailFields() {
        JsonObject json = ActorJson.npcSummary(npc(12, 101, "Guard", false, null, -1, null));

        assertFalse(json.has("actions"));
        assertFalse(json.has("recentHitsplats"));
        assertTrue(json.has("hp"));
        assertTrue(json.has("interacting"));
    }

    static PlayerSnapshot player(String name, int x, int y, int plane,
                                 boolean interacting, String interactingType,
                                 int interactingIndex, String interactingName) {
        return new PlayerSnapshot(
            name, x, y, plane,
            126, 1234,
            true, true, false,
            83, 99, 7, 10,
            55, 77,
            8600, 50, true,
            false, false,
            true, false, false,
            false, false,
            1, 2, 4151,
            interacting, interactingIndex, interactingType, interactingName,
            Collections.singletonList("PROTECT_FROM_MELEE"),
            Collections.singletonList(new HitsplatData(12, 1, 2))
        );
    }

    static NpcSnapshot npc(int index, int id, String name,
                           boolean interacting, String interactingType,
                           int interactingIndex, String interactingName) {
        return new NpcSnapshot(
            index, id, name, 21,
            3202, 3203, 0,
            4, 8, 5678,
            interacting, interactingIndex, interactingType, interactingName,
            2, 1024, -1, interacting,
            new String[]{"Attack", null, "Examine"},
            Arrays.asList(new HitsplatData(3, 0, 1))
        );
    }
}
