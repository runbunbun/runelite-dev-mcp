package dev.runelite.mcp.api.snapshot;

public final class WidgetSnapshot {
    public final int group;
    public final int child;
    public final boolean visible;
    public final String text;
    public final int itemId;
    public final int itemQuantity;
    public final String[] actions;

    public WidgetSnapshot(int group, int child, boolean visible, String text,
                          int itemId, int itemQuantity, String[] actions) {
        this.group = group; this.child = child; this.visible = visible;
        this.text = text; this.itemId = itemId; this.itemQuantity = itemQuantity;
        this.actions = actions;
    }
}
