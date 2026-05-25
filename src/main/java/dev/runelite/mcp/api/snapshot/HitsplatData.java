package dev.runelite.mcp.api.snapshot;

public final class HitsplatData {
    public final int amount;
    public final int type;
    public final int ticksAgo;

    public HitsplatData(int amount, int type, int ticksAgo) {
        this.amount = amount;
        this.type = type;
        this.ticksAgo = ticksAgo;
    }
}
