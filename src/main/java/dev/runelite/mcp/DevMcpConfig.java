package dev.runelite.mcp;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(DevMcpConfig.GROUP)
public interface DevMcpConfig extends Config {

    String GROUP = "runelite-dev-mcp";

    @ConfigItem(
        keyName = "port",
        name = "Server port",
        description = "TCP port the MCP HTTP+JSON-RPC server listens on. Changing this restarts the server. Update your Claude MCP URL to match.",
        position = 1
    )
    @Range(min = 1024, max = 65535)
    default int port() {
        return 3000;
    }

    @ConfigItem(
        keyName = "bufferSize",
        name = "State buffer (ticks)",
        description = "Number of game ticks retained in the delta-encoded state buffer. 1 tick = 600ms. 200 ≈ 2 min, 600 ≈ 6 min.",
        position = 2
    )
    @Range(min = 10, max = 2000)
    default int bufferSize() {
        return 200;
    }

    @ConfigItem(
        keyName = "actionLogSize",
        name = "Action log (records)",
        description = "How many MenuOptionClicked records (user clicks + plugin/macro actions via the public menu API) to retain. Older entries are dropped.",
        position = 3
    )
    @Range(min = 10, max = 5000)
    default int actionLogSize() {
        return 500;
    }
}
