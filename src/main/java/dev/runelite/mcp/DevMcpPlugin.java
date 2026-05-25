package dev.runelite.mcp;

import com.google.inject.Inject;
import com.google.inject.Provides;
import dev.runelite.mcp.api.WorldReader;
import dev.runelite.mcp.impl.RuneLiteWorldReader;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only MCP (Model Context Protocol) server for RuneLite.
 * Exposes the live game state to AI assistants over JSON-RPC + REST.
 *
 * Port, state-buffer size, and action-log size are configurable via the RuneLite plugin panel.
 */
@PluginDescriptor(
    name = "RuneLite Dev MCP",
    description = "Read-only MCP server exposing game state to AI assistants",
    tags = {"mcp", "ai", "claude", "development"}
)
public class DevMcpPlugin extends Plugin {

    private static final Logger log = Logger.getLogger(DevMcpPlugin.class.getName());

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private EventBus eventBus;
    @Inject private DrawManager drawManager;
    @Inject private DevMcpConfig config;

    private WorldReader world;
    private StateBuffer stateBuffer;
    private ActionLog actionLog;
    private McpHttpServer httpServer;

    @Provides
    DevMcpConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(DevMcpConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        world = new RuneLiteWorldReader(client);
        stateBuffer = new StateBuffer(client, world, config.bufferSize());
        actionLog = new ActionLog(client, config.actionLogSize());
        eventBus.register(stateBuffer);
        eventBus.register(actionLog);

        startServer(config.port());
        log.info("RuneLite Dev MCP started — port=" + config.port()
            + " bufferSize=" + config.bufferSize()
            + " actionLogSize=" + config.actionLogSize());
    }

    @Override
    protected void shutDown() {
        stopServer();
        if (actionLog != null) {
            eventBus.unregister(actionLog);
            actionLog = null;
        }
        if (stateBuffer != null) {
            eventBus.unregister(stateBuffer);
            stateBuffer = null;
        }
        world = null;
        log.info("RuneLite Dev MCP stopped");
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!DevMcpConfig.GROUP.equals(event.getGroup())) return;

        switch (event.getKey()) {
            case "port":
                log.info("MCP port changed to " + config.port() + " — restarting server");
                stopServer();
                startServer(config.port());
                break;
            case "bufferSize":
                if (stateBuffer != null) {
                    stateBuffer.resize(config.bufferSize());
                    log.info("State buffer resized to " + config.bufferSize() + " ticks");
                }
                break;
            case "actionLogSize":
                if (actionLog != null) {
                    actionLog.resize(config.actionLogSize());
                    log.info("Action log resized to " + config.actionLogSize() + " records");
                }
                break;
            default:
                // ignore
        }
    }

    private void startServer(int port) {
        try {
            httpServer = new McpHttpServer(port, client, clientThread, configManager,
                world, stateBuffer, actionLog, drawManager);
            httpServer.start();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to start MCP HTTP server on port " + port, e);
            httpServer = null;
        }
    }

    private void stopServer() {
        if (httpServer != null) {
            try { httpServer.stop(); }
            catch (Exception e) { log.log(Level.WARNING, "Error stopping HTTP server", e); }
            httpServer = null;
        }
    }
}
