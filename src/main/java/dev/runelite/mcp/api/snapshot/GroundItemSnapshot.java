package dev.runelite.mcp.api.snapshot;

public final class GroundItemSnapshot {
    public final int id;
    public final String name;
    public final int quantity;
    public final int worldX, worldY, plane;

    public GroundItemSnapshot(int id, String name, int quantity,
                              int worldX, int worldY, int plane) {
        this.id = id; this.name = name; this.quantity = quantity;
        this.worldX = worldX; this.worldY = worldY; this.plane = plane;
    }
}
