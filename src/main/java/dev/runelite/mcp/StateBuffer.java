package dev.runelite.mcp;

import dev.runelite.mcp.api.WorldReader;
import dev.runelite.mcp.api.snapshot.GroundItemSnapshot;
import dev.runelite.mcp.api.snapshot.NpcSnapshot;
import dev.runelite.mcp.api.snapshot.ObjectSnapshot;
import dev.runelite.mcp.api.snapshot.PlayerSnapshot;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
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

        TickSnapshot(int tick, long timestampMs,
                     PlayerSnapshot player,
                     List<NpcSnapshot> npcs,
                     List<ObjectSnapshot> objects,
                     List<GroundItemSnapshot> groundItems,
                     List<PlayerSnapshot> otherPlayers) {
            this.tick = tick;
            this.timestampMs = timestampMs;
            this.player = player;
            this.npcs = npcs != null ? npcs : Collections.emptyList();
            this.objects = objects != null ? objects : Collections.emptyList();
            this.groundItems = groundItems != null ? groundItems : Collections.emptyList();
            this.otherPlayers = otherPlayers != null ? otherPlayers : Collections.emptyList();
        }
    }

    private final Client client;
    private final WorldReader world;
    private final Object lock = new Object();
    private TickSnapshot[] buffer;
    private int head;   // next slot to write
    private int count;  // valid entries (capped at buffer.length)

    public StateBuffer(Client client, WorldReader world, int size) {
        this.client = client;
        this.world = world;
        this.buffer = new TickSnapshot[Math.max(1, size)];
    }

    @Subscribe
    public void onGameTick(GameTick e) {
        TickSnapshot snap = new TickSnapshot(
            client.getTickCount(),
            System.currentTimeMillis(),
            world.getLocalPlayer(),
            world.getNpcs(),
            world.getObjects(),
            world.getGroundItems(),
            world.getOtherPlayers()
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
