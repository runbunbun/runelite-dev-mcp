package dev.runelite.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeOfferStateTest {

    @Test
    void buyStatesAreBuy() {
        assertTrue(McpToolHandler.isBuyState("BUYING"));
        assertTrue(McpToolHandler.isBuyState("BOUGHT"));
        assertTrue(McpToolHandler.isBuyState("CANCELLED_BUY"));
    }

    @Test
    void sellStatesAreNotBuy() {
        assertFalse(McpToolHandler.isBuyState("SELLING"));
        assertFalse(McpToolHandler.isBuyState("SOLD"));
        assertFalse(McpToolHandler.isBuyState("CANCELLED_SELL"));
    }

    @Test
    void unknownOrNullIsNotBuy() {
        assertFalse(McpToolHandler.isBuyState("EMPTY"));
        assertFalse(McpToolHandler.isBuyState(null));
    }
}
