package dev.runelite.mcp.api.snapshot;

public final class InterfaceSnapshot {
    public final boolean bankOpen;
    public final boolean shopOpen;
    public final boolean grandExchangeOpen;
    public final boolean tradeOpen;
    public final boolean productionOpen;
    public final String activeSpellbook; // "STANDARD", "ANCIENT", "LUNAR", "ARCEUUS"
    public final String activeTab;       // "combat", "prayer", "inventory", "equipment", "magic"

    public InterfaceSnapshot(boolean bankOpen, boolean shopOpen, boolean grandExchangeOpen,
                             boolean tradeOpen, boolean productionOpen,
                             String activeSpellbook, String activeTab) {
        this.bankOpen = bankOpen; this.shopOpen = shopOpen;
        this.grandExchangeOpen = grandExchangeOpen;
        this.tradeOpen = tradeOpen; this.productionOpen = productionOpen;
        this.activeSpellbook = activeSpellbook; this.activeTab = activeTab;
    }
}
