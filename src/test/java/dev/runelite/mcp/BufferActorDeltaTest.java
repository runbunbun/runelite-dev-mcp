package dev.runelite.mcp;

import com.google.gson.JsonObject;
import dev.runelite.mcp.api.snapshot.HitsplatData;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferActorDeltaTest {
    @Test
    void playerDeltaIncludesEnrichedActorFields() throws Exception {
        PlayerSnapshot before = player(false, false, true,
            7, 10, "npc", 1, "Goblin", 1, 2, 100);
        PlayerSnapshot after = player(true, true, false,
            6, 10, "player", 2, "Rival", 3, 4, 200);

        JsonObject delta = invokePlayerDelta(before, after);

        assertPair(delta, "animating", false, true);
        assertPair(delta, "moving", false, true);
        assertPair(delta, "idle", true, false);
        assertPair(delta, "healthRatio", 7, 6);
        assertPair(delta, "healthScale", 10, 10, false);
        assertPair(delta, "interactingType", "npc", "player");
        assertPair(delta, "interactingIndex", 1, 2);
        assertPair(delta, "interactingName", "Goblin", "Rival");
        assertPair(delta, "overheadIcon", 1, 3);
        assertPair(delta, "skullIcon", 2, 4);
        assertPair(delta, "weaponId", 100, 200);
    }

    @Test
    void npcDeltaIncludesEnrichedActorFields() throws Exception {
        NpcSnapshot before = npc(4, 8, false, null, -1, null, 1, 512, false);
        NpcSnapshot after = npc(2, 8, true, "player", 0, "Local", 2, 1024, true);

        JsonObject delta = invokeNpcDelta(before, after);

        assertPair(delta, "healthRatio", 4, 2);
        assertPair(delta, "healthScale", 8, 8, false);
        assertPair(delta, "interacting", false, true);
        assertPair(delta, "interactingType", null, "player");
        assertPair(delta, "interactingIndex", -1, 0);
        assertPair(delta, "interactingName", null, "Local");
        assertPair(delta, "size", 1, 2);
        assertPair(delta, "orientation", 512, 1024);
        assertPair(delta, "inCombat", false, true);
    }

    private static JsonObject invokePlayerDelta(PlayerSnapshot before, PlayerSnapshot after) throws Exception {
        Method method = BufferQueryHandler.class.getDeclaredMethod(
            "playerDelta", PlayerSnapshot.class, PlayerSnapshot.class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(new BufferQueryHandler(null, 0), before, after);
    }

    private static JsonObject invokeNpcDelta(NpcSnapshot before, NpcSnapshot after) throws Exception {
        Method method = BufferQueryHandler.class.getDeclaredMethod(
            "npcDelta", NpcSnapshot.class, NpcSnapshot.class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(new BufferQueryHandler(null, 0), before, after);
    }

    private static PlayerSnapshot player(boolean moving, boolean animating, boolean idle,
                                         int healthRatio, int healthScale,
                                         String interactingType, int interactingIndex,
                                         String interactingName,
                                         int overheadIcon, int skullIcon, int weaponId) {
        return new PlayerSnapshot(
            "Player", 3200, 3201, 0,
            100, animating ? 123 : -1,
            moving, animating, idle,
            50, 99, healthRatio, healthScale,
            20, 77,
            5000, 25, true,
            false, false,
            false, false, false,
            false, false,
            overheadIcon, skullIcon, weaponId,
            interactingType != null, interactingIndex, interactingType, interactingName,
            Collections.emptyList(),
            Collections.singletonList(new HitsplatData(1, 0, 0))
        );
    }

    private static NpcSnapshot npc(int healthRatio, int healthScale,
                                   boolean interacting, String interactingType,
                                   int interactingIndex, String interactingName,
                                   int size, int orientation, boolean inCombat) {
        return new NpcSnapshot(
            12, 101, "Guard", 21,
            3202, 3203, 0,
            healthRatio, healthScale, interacting ? 777 : -1,
            interacting, interactingIndex, interactingType, interactingName,
            size, orientation, -1, inCombat,
            new String[]{"Attack", "Examine"},
            Collections.emptyList()
        );
    }

    private static void assertPair(JsonObject delta, String key, int before, int after) {
        assertPair(delta, key, before, after, true);
    }

    private static void assertPair(JsonObject delta, String key, int before, int after, boolean shouldExist) {
        if (!shouldExist) {
            assertTrue(!delta.has(key));
            return;
        }
        assertEquals(before, delta.getAsJsonArray(key).get(0).getAsInt());
        assertEquals(after, delta.getAsJsonArray(key).get(1).getAsInt());
    }

    private static void assertPair(JsonObject delta, String key, boolean before, boolean after) {
        assertEquals(before, delta.getAsJsonArray(key).get(0).getAsBoolean());
        assertEquals(after, delta.getAsJsonArray(key).get(1).getAsBoolean());
    }

    private static void assertPair(JsonObject delta, String key, String before, String after) {
        assertEquals(before, delta.getAsJsonArray(key).get(0).isJsonNull()
            ? null : delta.getAsJsonArray(key).get(0).getAsString());
        assertEquals(after, delta.getAsJsonArray(key).get(1).isJsonNull()
            ? null : delta.getAsJsonArray(key).get(1).getAsString());
    }
}
