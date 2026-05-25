package dev.runelite.mcp;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ring buffer of in-game actions captured via {@code MenuOptionClicked}.
 *
 * <p>What this catches:
 * <ul>
 *   <li>User clicks on entities, widgets, ground, etc.</li>
 *   <li>Plugin / macro actions invoked via {@code Client.invokeMenuAction} or {@code Client.menuAction}
 *       (the public menu API) — these post {@code MenuOptionClicked} through the same dispatch.</li>
 * </ul>
 *
 * <p>What this does NOT catch:
 * <ul>
 *   <li>Actions that bypass the menu and send packets directly to the server
 *       (e.g. {@code PacketReflection.sendPacket} raw-packet paths). Capturing those requires
 *       bytecode injection at the obfuscated client level, which is out of scope for a sideloaded plugin.</li>
 * </ul>
 */
public class ActionLog {

    public static final class ActionRecord {
        public final int tick;
        public final long timestampMs;
        public final String option;        // color tags stripped
        public final String target;        // color tags stripped
        public final String menuActionName; // e.g. "NPC_FIRST_OPTION"
        public final int opcodeId;          // MenuAction.getId()
        public final int identifier;        // npc index, object id, slot, etc.
        public final int param0;
        public final int param1;
        public final int itemId;

        ActionRecord(int tick, long timestampMs, String option, String target,
                     String menuActionName, int opcodeId, int identifier,
                     int param0, int param1, int itemId) {
            this.tick = tick;
            this.timestampMs = timestampMs;
            this.option = option;
            this.target = target;
            this.menuActionName = menuActionName;
            this.opcodeId = opcodeId;
            this.identifier = identifier;
            this.param0 = param0;
            this.param1 = param1;
            this.itemId = itemId;
        }
    }

    private final Client client;
    private final Object lock = new Object();
    private ActionRecord[] buffer;
    private int head;
    private int count;

    public ActionLog(Client client, int size) {
        this.client = client;
        this.buffer = new ActionRecord[Math.max(1, size)];
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e) {
        if (e == null) return;
        MenuAction ma = e.getMenuAction();
        ActionRecord r = new ActionRecord(
            client.getTickCount(),
            System.currentTimeMillis(),
            strip(e.getMenuOption()),
            strip(e.getMenuTarget()),
            ma != null ? ma.name() : "UNKNOWN",
            ma != null ? ma.getId() : -1,
            e.getId(),
            e.getParam0(),
            e.getParam1(),
            e.getItemId()
        );
        synchronized (lock) {
            buffer[head] = r;
            head = (head + 1) % buffer.length;
            count = Math.min(count + 1, buffer.length);
        }
    }

    public void resize(int newSize) {
        if (newSize < 1) newSize = 1;
        synchronized (lock) {
            if (newSize == buffer.length) return;
            int keep = Math.min(count, newSize);
            ActionRecord[] preserved = new ActionRecord[keep];
            int start = (head - count + buffer.length) % buffer.length;
            int toSkip = count - keep;
            for (int i = 0; i < keep; i++) {
                preserved[i] = buffer[(start + toSkip + i) % buffer.length];
            }
            buffer = new ActionRecord[newSize];
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

    /** Returns the last n records, newest-last (chronological). */
    public List<ActionRecord> getLastN(int n) {
        synchronized (lock) {
            int k = Math.min(n, count);
            if (k <= 0) return Collections.emptyList();
            List<ActionRecord> out = new ArrayList<>(k);
            int start = (head - k + buffer.length) % buffer.length;
            for (int i = 0; i < k; i++) {
                out.add(buffer[(start + i) % buffer.length]);
            }
            return out;
        }
    }

    private static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").trim();
    }
}
