package dev.runelite.mcp.api.snapshot;

public final class ProjectileSnapshot {
    public final int spotanimId;
    public final int sourceX, sourceY, sourcePlane;
    public final int sourceActorIndex;
    public final String sourceActorType;
    public final boolean sourceIsLocalPlayer;
    public final int targetX, targetY, targetPlane;
    public final int targetActorIndex;
    public final String targetActorType;
    public final boolean targetIsLocalPlayer;
    public final int remainingCycles;
    public final int ticksToImpact;
    public final int startHeight;
    public final int endHeight;
    public final int slope;

    public ProjectileSnapshot(int spotanimId,
                              int sourceX, int sourceY, int sourcePlane,
                              int sourceActorIndex, String sourceActorType,
                              boolean sourceIsLocalPlayer,
                              int targetX, int targetY, int targetPlane,
                              int targetActorIndex, String targetActorType,
                              boolean targetIsLocalPlayer,
                              int remainingCycles, int ticksToImpact,
                              int startHeight, int endHeight, int slope) {
        this.spotanimId = spotanimId;
        this.sourceX = sourceX; this.sourceY = sourceY; this.sourcePlane = sourcePlane;
        this.sourceActorIndex = sourceActorIndex;
        this.sourceActorType = sourceActorType;
        this.sourceIsLocalPlayer = sourceIsLocalPlayer;
        this.targetX = targetX; this.targetY = targetY; this.targetPlane = targetPlane;
        this.targetActorIndex = targetActorIndex;
        this.targetActorType = targetActorType;
        this.targetIsLocalPlayer = targetIsLocalPlayer;
        this.remainingCycles = remainingCycles;
        this.ticksToImpact = ticksToImpact;
        this.startHeight = startHeight;
        this.endHeight = endHeight;
        this.slope = slope;
    }
}
