package dev.runelite.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerResolveTest {

    @Test
    void resolvesKnownNamesCaseAndSeparatorInsensitive() {
        assertEquals(93, McpToolHandler.resolveContainerId("inventory"));
        assertEquals(94, McpToolHandler.resolveContainerId("EQUIPMENT"));
        assertEquals(94, McpToolHandler.resolveContainerId("worn"));
        assertEquals(95, McpToolHandler.resolveContainerId("bank"));
        assertEquals(516, McpToolHandler.resolveContainerId("looting-bag"));
        assertEquals(626, McpToolHandler.resolveContainerId("Seed Vault"));
        assertEquals(659, McpToolHandler.resolveContainerId("group_storage"));
    }

    @Test
    void passesThroughNumericIds() {
        assertEquals(572, McpToolHandler.resolveContainerId("572"));
        assertEquals(93, McpToolHandler.resolveContainerId("93"));
    }

    @Test
    void returnsNullForUnknownOrEmpty() {
        assertNull(McpToolHandler.resolveContainerId("not_a_container"));
        assertNull(McpToolHandler.resolveContainerId(""));
        assertNull(McpToolHandler.resolveContainerId(null));
    }
}
