package dev.runelite.mcp;

import dev.runelite.mcp.api.WorldReader;
import dev.runelite.mcp.api.snapshot.GraphicsObjectSnapshot;
import dev.runelite.mcp.api.snapshot.GroundItemSnapshot;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.ObjectSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;
import dev.runelite.mcp.api.snapshot.ProjectileSnapshot;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ring buffer of per-tick {@link TickSnapshot}s captured from a {@link WorldReader}
 * on every RuneLite {@code GameTick} event. Backs the {@code buffer} MCP tool, which
 * emits delta-encoded views of recent state.
 *
 * <p>Snapshot objects from {@link WorldReader} are already immutable, so the buffer
 * retains list references without deep-copying. Capture happens on the client thread;
 * reads (from MCP threads) are synchronized.
 */
public class StateBuffer {

    public static final class TickSnapshot {
        public final int tick;
        public final long timestampMs;
        public final PlayerSnapshot player;
        public final List<NpcSnapshot> npcs;
        public final List<ObjectSnapshot> objects;
        public final List<GroundItemSnapshot> groundItems;
        public final List<PlayerSnapshot> otherPlayers;
        /** Per-skill total XP, indexed by {@link Skill#ordinal()}. Length matches {@code Skill.values().length}. */
        public final int[] skillXp;
        /** Per-skill real (XP-derived) level. Same indexing as {@link #skillXp}. */
        public final int[] skillRealLevel;
        /** Per-skill boosted (current effective) level. Same indexing as {@link #skillXp}. */
        public final int[] skillBoostedLevel;
        /** Hitsplats applied during this tick (any actor — local player, other players, NPCs). */
        public final List<TickHit> hits;
        /** Projectiles in flight on this tick (transient — like hits, not diffed across ticks). */
        public final List<ProjectileSnapshot> projectiles;
        /** Ground graphics objects present on this tick (AoE telegraphs, spotanims). */
        public final List<GraphicsObjectSnapshot> graphicsObjects;

        TickSnapshot(int tick, long timestampMs,
                     PlayerSnapshot player,
                     List<NpcSnapshot> npcs,
                     List<ObjectSnapshot> objects,
                     List<GroundItemSnapshot> groundItems,
                     List<PlayerSnapshot> otherPlayers,
                     int[] skillXp, int[] skillRealLevel, int[] skillBoostedLevel,
                     List<TickHit> hits,
                     List<ProjectileSnapshot> projectiles,
                     List<GraphicsObjectSnapshot> graphicsObjects) {
            this.tick = tick;
            this.timestampMs = timestampMs;
            this.player = player;
            this.npcs = npcs != null ? npcs : Collections.emptyList();
            this.objects = objects != null ? objects : Collections.emptyList();
            this.groundItems = groundItems != null ? groundItems : Collections.emptyList();
            this.otherPlayers = otherPlayers != null ? otherPlayers : Collections.emptyList();
            this.skillXp = skillXp != null ? skillXp : new int[0];
            this.skillRealLevel = skillRealLevel != null ? skillRealLevel : new int[0];
            this.skillBoostedLevel = skillBoostedLevel != null ? skillBoostedLevel : new int[0];
            this.hits = hits != null ? hits : Collections.emptyList();
            this.projectiles = projectiles != null ? projectiles : Collections.emptyList();
            this.graphicsObjects = graphicsObjects != null ? graphicsObjects : Collections.emptyList();
        }
    }

    /**
     * One hitsplat captured from a {@code HitsplatApplied} event. {@code kind} is
     * "npc" / "player" / "other"; {@code id} is the NPC composition id when known,
     * otherwise -1. {@code isLocal} is true when the local player was the recipient.
     */
    public static final class TickHit {
        public final String actorName;
        public final String kind;
        public final int id;
        public final boolean isLocal;
        public final int amount;
        public final int type;
        public final boolean mine;

        public TickHit(String actorName, String kind, int id, boolean isLocal,
                       int amount, int type, boolean mine) {
            this.actorName = actorName; this.kind = kind; this.id = id;
            this.isLocal = isLocal; this.amount = amount; this.type = type; this.mine = mine;
        }
    }

    private final Client client;
    private final WorldReader world;
    private final Object lock = new Object();
    private TickSnapshot[] buffer;
    private int head;   // next slot to write
    private int count;  // valid entries (capped at buffer.length)

    // Hitsplats arrive on the client thread between GameTicks; we accumulate them
    // here and drain into the next tick snapshot. Guarded by hitLock since both
    // the @Subscribe HitsplatApplied path and the GameTick path can touch it.
    private final List<TickHit> pendingHits = new ArrayList<>();
    private final Object hitLock = new Object();

    public StateBuffer(Client client, WorldReader world, int size) {
        this.client = client;
        this.world = world;
        this.buffer = new TickSnapshot[Math.max(1, size)];
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied e) {
        Actor actor = e.getActor();
        Hitsplat h = e.getHitsplat();
        if (actor == null || h == null) return;
        String kind;
        int id = -1;
        if (actor instanceof NPC) {
            kind = "npc";
            id = ((NPC) actor).getId();
        } else if (actor instanceof Player) {
            kind = "player";
        } else {
            kind = "other";
        }
        boolean isLocal = (actor == client.getLocalPlayer());
        TickHit hit = new TickHit(actor.getName(), kind, id, isLocal,
            h.getAmount(), h.getHitsplatType(), h.isMine());
        synchronized (hitLock) {
            pendingHits.add(hit);
        }
    }

    @Subscribe
    public void onGameTick(GameTick e) {
        Skill[] skills = Skill.values();
        int[] xp = new int[skills.length];
        int[] real = new int[skills.length];
        int[] boosted = new int[skills.length];
        for (int i = 0; i < skills.length; i++) {
            xp[i] = world.getSkillXp(i);
            real[i] = world.getSkillLevel(i);
            boosted[i] = world.getBoostedLevel(i);
        }
        List<TickHit> tickHits;
        synchronized (hitLock) {
            tickHits = pendingHits.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(pendingHits);
            pendingHits.clear();
        }
        TickSnapshot snap = new TickSnapshot(
            client.getTickCount(),
            System.currentTimeMillis(),
            world.getLocalPlayer(),
            world.getNpcs(),
            world.getObjects(),
            world.getGroundItems(),
            world.getOtherPlayers(),
            xp, real, boosted,
            tickHits,
            world.getProjectiles(),
            world.getGraphicsObjects()
        );
        synchronized (lock) {
            buffer[head] = snap;
            head = (head + 1) % buffer.length;
            count = Math.min(count + 1, buffer.length);
        }
    }

    public void resize(int newSize) {
        if (newSize < 1) newSize = 1;
        synchronized (lock) {
            if (newSize == buffer.length) return;
            int keep = Math.min(count, newSize);
            TickSnapshot[] preserved = new TickSnapshot[keep];
            int start = (head - count + buffer.length) % buffer.length;
            int toSkip = count - keep;
            for (int i = 0; i < keep; i++) {
                preserved[i] = buffer[(start + toSkip + i) % buffer.length];
            }
            buffer = new TickSnapshot[newSize];
            for (int i = 0; i < keep; i++) buffer[i] = preserved[i];
            head = keep % newSize;
            count = keep;
        }
    }

    public int capacity() {
        synchronized (lock) { return buffer.length; }
    }

    public int filled() {
        synchronized (lock) { return count; }
    }

    public TickSnapshot getByTick(int tick) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                TickSnapshot s = buffer[i];
                if (s != null && s.tick == tick) return s;
            }
            return null;
        }
    }

    /** Last n snapshots, oldest-first. */
    public List<TickSnapshot> getLastN(int n) {
        synchronized (lock) {
            int k = Math.min(n, count);
            if (k <= 0) return Collections.emptyList();
            List<TickSnapshot> out = new ArrayList<>(k);
            int start = (head - k + buffer.length) % buffer.length;
            for (int i = 0; i < k; i++) {
                out.add(buffer[(start + i) % buffer.length]);
            }
            return out;
        }
    }

    public TickSnapshot latest() {
        synchronized (lock) {
            if (count == 0) return null;
            return buffer[(head - 1 + buffer.length) % buffer.length];
        }
    }

    /** Tick range currently retained: [oldest, newest]. Returns null when empty. */
    public int[] range() {
        synchronized (lock) {
            if (count == 0) return null;
            TickSnapshot oldest = buffer[(head - count + buffer.length) % buffer.length];
            TickSnapshot newest = buffer[(head - 1 + buffer.length) % buffer.length];
            return new int[]{oldest.tick, newest.tick};
        }
    }
}
