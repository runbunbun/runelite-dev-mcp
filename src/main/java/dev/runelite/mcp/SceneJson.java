package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.runelite.mcp.api.snapshot.GraphicsObjectSnapshot;
import dev.runelite.mcp.api.snapshot.ProjectileSnapshot;

/**
 * Shared JSON renderers for scene-level transient effects — projectiles in flight
 * (ranged/magic shots, cannonballs, boss attacks) and graphics objects (ground
 * spotanims: AoE telegraphs, rockfalls, splashes). Used by both the point-in-time
 * {@code state} sections and the per-tick {@code buffer}.
 */
public final class SceneJson {

    private SceneJson() {}

    /**
     * One projectile. {@code source}/{@code target} each carry a world {@code pos} plus,
     * when the endpoint is an actor rather than a fixed tile, {@code actorIndex} /
     * {@code actorType} ("npc"|"player") and {@code local:true} for the local player.
     * {@code ticksToImpact} is the headline field — ticks until the shot lands.
     */
    public static JsonObject projectile(ProjectileSnapshot p) {
        JsonObject o = new JsonObject();
        o.addProperty("spotanim", p.spotanimId);
        o.add("source", endpoint(p.sourceX, p.sourceY, p.sourcePlane,
            p.sourceActorIndex, p.sourceActorType, p.sourceIsLocalPlayer));
        o.add("target", endpoint(p.targetX, p.targetY, p.targetPlane,
            p.targetActorIndex, p.targetActorType, p.targetIsLocalPlayer));
        o.addProperty("ticksToImpact", p.ticksToImpact);
        o.addProperty("remainingCycles", p.remainingCycles);
        o.addProperty("slope", p.slope);
        o.addProperty("startHeight", p.startHeight);
        o.addProperty("endHeight", p.endHeight);
        return o;
    }

    private static JsonObject endpoint(int x, int y, int plane, int actorIndex,
                                       String actorType, boolean local) {
        JsonObject e = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(x); pos.add(y); pos.add(plane);
        e.add("pos", pos);
        if (actorIndex >= 0) e.addProperty("actorIndex", actorIndex);
        if (actorType != null) e.addProperty("actorType", actorType);
        if (local) e.addProperty("local", true);
        return e;
    }

    /**
     * One ground graphics object. {@code id} is the spotanim id, {@code pos} its world tile,
     * {@code ageTicks} how long it has been alive (AoE markers are often keyed off age), and
     * {@code finished:true} marks it as expiring this tick.
     */
    public static JsonObject graphicsObject(GraphicsObjectSnapshot g) {
        JsonObject o = new JsonObject();
        o.addProperty("id", g.graphicsId);
        JsonArray pos = new JsonArray();
        pos.add(g.worldX); pos.add(g.worldY); pos.add(g.plane);
        o.add("pos", pos);
        o.addProperty("z", g.z);
        o.addProperty("ageTicks", g.ageTicks);
        o.addProperty("frame", g.animationFrame);
        if (g.finished) o.addProperty("finished", true);
        return o;
    }
}
