package dev.runelite.mcp.api.snapshot;

public final class GraphicsObjectSnapshot {
    public final int graphicsId;
    public final int worldX, worldY, plane;
    public final int z;
    public final int startCycle;
    public final int ageTicks;
    public final int animationFrame;
    public final boolean finished;

    public GraphicsObjectSnapshot(int graphicsId,
                                  int worldX, int worldY, int plane,
                                  int z, int startCycle, int ageTicks,
                                  int animationFrame, boolean finished) {
        this.graphicsId = graphicsId;
        this.worldX = worldX; this.worldY = worldY; this.plane = plane;
        this.z = z;
        this.startCycle = startCycle;
        this.ageTicks = ageTicks;
        this.animationFrame = animationFrame;
        this.finished = finished;
    }
}
