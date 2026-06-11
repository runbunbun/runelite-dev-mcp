package dev.runelite.mcp;

import com.google.gson.JsonObject;
import dev.runelite.mcp.api.snapshot.GraphicsObjectSnapshot;
import dev.runelite.mcp.api.snapshot.ProjectileSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneJsonTest {

    @Test
    void projectileFromLocalPlayerToNpcRendersEndpoints() {
        // local player (source) firing at an NPC (target), 3 ticks out.
        ProjectileSnapshot p = new ProjectileSnapshot(
            /*spotanim*/ 130,
            /*sourceX,Y,plane*/ 3200, 3200, 0,
            /*sourceActorIndex*/ 7, "player", /*local*/ true,
            /*targetX,Y,plane*/ 3210, 3205, 0,
            /*targetActorIndex*/ 49455, "npc", /*targetLocal*/ false,
            /*remainingCycles*/ 90, /*ticksToImpact*/ 3,
            /*startHeight*/ 40, /*endHeight*/ 0, /*slope*/ 10);

        JsonObject o = SceneJson.projectile(p);
        assertEquals(130, o.get("spotanim").getAsInt());
        assertEquals(3, o.get("ticksToImpact").getAsInt());

        JsonObject src = o.getAsJsonObject("source");
        assertEquals(3200, src.getAsJsonArray("pos").get(0).getAsInt());
        assertEquals(7, src.get("actorIndex").getAsInt());
        assertEquals("player", src.get("actorType").getAsString());
        assertTrue(src.get("local").getAsBoolean());

        JsonObject tgt = o.getAsJsonObject("target");
        assertEquals(49455, tgt.get("actorIndex").getAsInt());
        assertEquals("npc", tgt.get("actorType").getAsString());
        assertFalse(tgt.has("local")); // only emitted when true
    }

    @Test
    void projectileToFixedTileOmitsActorFields() {
        // a shot aimed at a ground tile — no target actor.
        ProjectileSnapshot p = new ProjectileSnapshot(
            55, 3200, 3200, 0, -1, null, false,
            3208, 3208, 0, -1, null, false,
            60, 2, 30, 0, 5);

        JsonObject tgt = SceneJson.projectile(p).getAsJsonObject("target");
        assertEquals(3208, tgt.getAsJsonArray("pos").get(0).getAsInt());
        assertFalse(tgt.has("actorIndex"));
        assertFalse(tgt.has("actorType"));
        assertFalse(tgt.has("local"));
    }

    @Test
    void graphicsObjectRendersIdPosAndAge() {
        GraphicsObjectSnapshot g = new GraphicsObjectSnapshot(
            /*graphicsId*/ 1456, /*worldX,Y,plane*/ 3220, 3300, 0,
            /*z*/ 0, /*startCycle*/ 1000, /*ageTicks*/ 2, /*frame*/ 4, /*finished*/ false);

        JsonObject o = SceneJson.graphicsObject(g);
        assertEquals(1456, o.get("id").getAsInt());
        assertEquals(3220, o.getAsJsonArray("pos").get(0).getAsInt());
        assertEquals(2, o.get("ageTicks").getAsInt());
        assertEquals(4, o.get("frame").getAsInt());
        assertFalse(o.has("finished")); // only emitted when true
    }

    @Test
    void finishedGraphicsObjectEmitsFlag() {
        GraphicsObjectSnapshot g = new GraphicsObjectSnapshot(
            1456, 3220, 3300, 0, 0, 1000, 5, -1, true);
        assertTrue(SceneJson.graphicsObject(g).get("finished").getAsBoolean());
    }
}
