package dev.runelite.mcp.api.snapshot;

public final class ObjectSnapshot {
    public final int id;
    public final String name;
    public final int worldX, worldY, plane;
    public final String[] actions;
    /** One of: GAME_OBJECT, WALL_OBJECT, GROUND_OBJECT, DECORATIVE_OBJECT */
    public final String objectType;

    public ObjectSnapshot(int id, String name, int worldX, int worldY, int plane,
                          String[] actions, String objectType) {
        this.id = id; this.name = name;
        this.worldX = worldX; this.worldY = worldY; this.plane = plane;
        this.actions = actions; this.objectType = objectType;
    }
}
