package dev.runelite.mcp.impl;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Optional;

/**
 * Resolves screen-space clickbox rectangles for game entities.
 * Used by the ActionDispatcher to tell the InputPipeline where to click.
 */
public class ClickboxResolver {

    private final Client client;

    // Inventory widget: group 149, child 0
    private static final int INVENTORY_WIDGET_GROUP = 149;
    private static final int INVENTORY_WIDGET_CHILD = 0;
    private static final int SLOTS_PER_ROW = 4;
    private static final int SLOT_WIDTH = 42;
    private static final int SLOT_HEIGHT = 36;

    public ClickboxResolver(Client client) {
        this.client = client;
    }

    public Optional<Rectangle> npcClickbox(int npcIndex) {
        for (NPC npc : client.getNpcs()) {
            if (npc != null && npc.getIndex() == npcIndex) {
                return shapeToRect(npc.getConvexHull());
            }
        }
        return Optional.empty();
    }

    /** Alias for {@link #npcClickbox} kept for callers that previously used a tighter rect. */
    public Optional<Rectangle> npcClickboxTight(int npcIndex) {
        return npcClickbox(npcIndex);
    }

    public Optional<Rectangle> objectClickbox(int objectId, int worldX, int worldY, int plane) {
        Tile[][][] tiles = client.getScene().getTiles();
        if (plane < 0 || plane >= tiles.length) return Optional.empty();

        LocalPoint lp = LocalPoint.fromWorld(client, worldX, worldY);
        if (lp == null) return Optional.empty();

        int sceneX = lp.getSceneX();
        int sceneY = lp.getSceneY();
        if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[plane].length
            || sceneY >= tiles[plane][sceneX].length) return Optional.empty();

        Tile tile = tiles[plane][sceneX][sceneY];
        if (tile == null) return Optional.empty();

        // Search all object types on this tile
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj != null && obj.getId() == objectId) {
                    return shapeToRect(obj.getClickbox());
                }
            }
        }
        WallObject wall = tile.getWallObject();
        if (wall != null && wall.getId() == objectId) {
            return shapeToRect(wall.getClickbox());
        }
        GroundObject ground = tile.getGroundObject();
        if (ground != null && ground.getId() == objectId) {
            return shapeToRect(ground.getClickbox());
        }
        DecorativeObject deco = tile.getDecorativeObject();
        if (deco != null && deco.getId() == objectId) {
            return shapeToRect(deco.getClickbox());
        }

        return Optional.empty();
    }

    public Optional<Rectangle> playerClickbox(int playerIndex) {
        for (Player p : client.getPlayers()) {
            if (p != null && client.getPlayers().indexOf(p) == playerIndex) {
                return shapeToRect(p.getConvexHull());
            }
        }
        return Optional.empty();
    }

    public Optional<Rectangle> groundItemClickbox(int worldX, int worldY) {
        // Ground items render as ~16-px sprites at tile center, regardless of
        // camera distance. Use the tile polygon as an upper bound on the
        // clickable region and clamp to a 24×24 ceiling so near tiles don't
        // produce an over-wide click envelope around a small sprite.
        LocalPoint lp = LocalPoint.fromWorld(client, worldX, worldY);
        if (lp == null) return Optional.empty();
        net.runelite.api.Point screen = Perspective.localToCanvas(client, lp, client.getPlane());
        if (screen == null) return Optional.empty();

        int w = 24, h = 24;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly != null) {
            Rectangle pb = poly.getBounds();
            if (pb.width > 0 && pb.height > 0) {
                w = Math.min(24, Math.max(8, pb.width));
                h = Math.min(24, Math.max(8, pb.height));
            }
        }
        return Optional.of(new Rectangle(
            screen.getX() - w / 2, screen.getY() - h / 2, w, h));
    }

    public Optional<Rectangle> inventorySlotBounds(int slot) {
        Widget inventoryWidget = client.getWidget(INVENTORY_WIDGET_GROUP, INVENTORY_WIDGET_CHILD);
        if (inventoryWidget == null || inventoryWidget.isHidden()) return Optional.empty();

        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || slot >= children.length) return Optional.empty();

        Widget item = children[slot];
        if (item == null) return Optional.empty();

        return Optional.of(item.getBounds());
    }

    /**
     * Layout-bounds variant of {@link #inventorySlotBounds}: returns the
     * slot's bounds whether the inventory panel is currently shown or
     * hidden, so the caller can queue a mouse trajectory to the slot in
     * the same tick they queue an inventory tab-switch — pipelined.
     */
    public Optional<Rectangle> inventorySlotLayoutBounds(int slot) {
        Widget inventoryWidget = client.getWidget(INVENTORY_WIDGET_GROUP, INVENTORY_WIDGET_CHILD);
        if (inventoryWidget == null) return Optional.empty();
        Widget[] children = inventoryWidget.getDynamicChildren();
        if (children == null || slot < 0 || slot >= children.length) return Optional.empty();
        Widget item = children[slot];
        if (item == null) return Optional.empty();
        Rectangle b = item.getBounds();
        if (b == null || b.width <= 0 || b.height <= 0) return Optional.empty();
        return Optional.of(b);
    }

    /**
     * Get bank item clickbox by slot index in the bank container (widget 12.12).
     * Slot comes from ItemContainer BANK via WorldReader.getBankItems().
     */
    public Optional<Rectangle> bankSlotBounds(int slot) {
        Widget bankContainer = client.getWidget(12, 12);
        if (bankContainer == null || bankContainer.isHidden()) return Optional.empty();
        Widget[] children = bankContainer.getDynamicChildren();
        if (children == null || slot < 0 || slot >= children.length) return Optional.empty();
        Widget child = children[slot];
        if (child == null || child.isHidden()) return Optional.empty();
        Rectangle bounds = child.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) return Optional.empty();
        // Check item is visible within scrollable container viewport
        Rectangle containerBounds = bankContainer.getBounds();
        if (!containerBounds.intersects(bounds)) return Optional.empty();
        return Optional.of(bounds);
    }

    /**
     * Get bank deposit inventory item clickbox by slot (widget 15.3).
     */
    public Optional<Rectangle> bankDepositSlotBounds(int slot) {
        Widget depositPanel = client.getWidget(15, 3);
        if (depositPanel == null || depositPanel.isHidden()) return Optional.empty();
        Widget[] children = depositPanel.getDynamicChildren();
        if (children == null || slot < 0 || slot >= children.length) return Optional.empty();
        Widget child = children[slot];
        if (child == null || child.isHidden()) return Optional.empty();
        Rectangle bounds = child.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) return Optional.empty();
        return Optional.of(bounds);
    }

    public Optional<Rectangle> widgetClickbox(int group, int child) {
        Widget widget = client.getWidget(group, child);
        if (widget == null || widget.isHidden()) return Optional.empty();
        return Optional.of(widget.getBounds());
    }

    /**
     * Layout-bounds variant: returns the widget's bounds whether the parent
     * panel is currently visible or hidden. The OSRS layout pass sets these
     * once and they don't change with visibility — so for callers that are
     * about to OPEN the panel (e.g. F-key tab switch) and need to queue a
     * mouse trajectory toward the icon BEFORE the panel becomes visible,
     * this avoids a 30-50 ms wait for the layout to "settle" (it already has).
     */
    public Optional<Rectangle> widgetLayoutBounds(int group, int child) {
        Widget widget = client.getWidget(group, child);
        if (widget == null) return Optional.empty();
        Rectangle b = widget.getBounds();
        if (b == null || b.width <= 0 || b.height <= 0) return Optional.empty();
        return Optional.of(b);
    }

    public Optional<Rectangle> tileClickbox(int worldX, int worldY) {
        // Use the actual projected tile polygon as the click region so the
        // envelope scales with camera distance/angle. Distant tiles get a
        // small envelope; near tiles get a wide one — matches human click
        // variance for walk clicks. Falls back to a scaled rect around the
        // tile centre when the poly isn't available (tile clipped behind UI
        // etc.).
        LocalPoint lp = LocalPoint.fromWorld(client, worldX, worldY);
        if (lp == null) return Optional.empty();
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly != null) {
            Rectangle bounds = poly.getBounds();
            if (bounds.width > 0 && bounds.height > 0) {
                return Optional.of(bounds);
            }
        }
        net.runelite.api.Point screen = Perspective.localToCanvas(client, lp, client.getPlane());
        if (screen == null) return Optional.empty();
        return Optional.of(new Rectangle(screen.getX() - 15, screen.getY() - 10, 30, 20));
    }

    public Optional<Rectangle> minimapClickbox(int worldX, int worldY) {
        LocalPoint lp = LocalPoint.fromWorld(client, worldX, worldY);
        if (lp == null) return Optional.empty();
        net.runelite.api.Point minimap = Perspective.localToMinimap(client, lp);
        if (minimap == null) return Optional.empty();
        return Optional.of(new Rectangle(minimap.getX() - 3, minimap.getY() - 3, 7, 7));
    }

    private static Optional<Rectangle> shapeToRect(Shape shape) {
        if (shape == null) return Optional.empty();
        Rectangle bounds = shape.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) return Optional.empty();
        return Optional.of(bounds);
    }
}
