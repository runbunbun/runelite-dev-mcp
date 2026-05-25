package dev.runelite.mcp.api.snapshot;

import java.util.List;

public final class NpcSnapshot {
    public final int index;
    public final int id;
    public final String name;
    public final int combatLevel;
    public final int worldX, worldY, plane;
    public final int healthRatio, healthScale;
    public final int animation;
    public final boolean interacting;
    public final int interactingIndex;
    public final String interactingType; // "player", "npc", or null
    public final String interactingName; // name of target entity (for interaction graph)
    public final int size;
    public final int orientation;
    public final int overheadIcon;
    public final boolean inCombat;
    public final String[] actions;
    public final List<HitsplatData> recentHitsplats;

    public NpcSnapshot(int index, int id, String name, int combatLevel,
                       int worldX, int worldY, int plane,
                       int healthRatio, int healthScale, int animation,
                       boolean interacting, int interactingIndex,
                       String interactingType, String interactingName,
                       int size, int orientation, int overheadIcon, boolean inCombat,
                       String[] actions, List<HitsplatData> recentHitsplats) {
        this.index = index; this.id = id; this.name = name;
        this.combatLevel = combatLevel;
        this.worldX = worldX; this.worldY = worldY; this.plane = plane;
        this.healthRatio = healthRatio; this.healthScale = healthScale;
        this.animation = animation;
        this.interacting = interacting; this.interactingIndex = interactingIndex;
        this.interactingType = interactingType; this.interactingName = interactingName;
        this.size = size; this.orientation = orientation;
        this.overheadIcon = overheadIcon; this.inCombat = inCombat;
        this.actions = actions;
        this.recentHitsplats = recentHitsplats;
    }
}
