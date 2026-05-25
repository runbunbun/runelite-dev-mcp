package dev.runelite.mcp.api.snapshot;

public final class ItemSnapshot {
    public final int id;
    public final String name;
    public final int quantity;
    public final int slot;
    public final String[] actions;

    public ItemSnapshot(int id, String name, int quantity, int slot, String[] actions) {
        this.id = id; this.name = name; this.quantity = quantity;
        this.slot = slot; this.actions = actions;
    }
}
