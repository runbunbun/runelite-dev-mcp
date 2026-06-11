package dev.runelite.mcp.api.snapshot;

/**
 * One Grand Exchange offer slot. Mirrors {@code GrandExchangeOffer} with the item name
 * resolved. {@code state} is the {@code GrandExchangeOfferState} name (BUYING/SELLING/
 * BOUGHT/SOLD/CANCELLED_BUY/CANCELLED_SELL); EMPTY slots are not emitted.
 */
public final class GeOfferSnapshot {
    public final int slot;
    public final String state;
    public final int itemId;
    public final String itemName;
    public final int price;          // offer price per item
    public final int totalQuantity;  // quantity the offer is for
    public final int quantitySold;   // quantity bought/sold so far
    public final int spent;          // total gp moved so far

    public GeOfferSnapshot(int slot, String state, int itemId, String itemName,
                           int price, int totalQuantity, int quantitySold, int spent) {
        this.slot = slot;
        this.state = state;
        this.itemId = itemId;
        this.itemName = itemName;
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.quantitySold = quantitySold;
        this.spent = spent;
    }
}
