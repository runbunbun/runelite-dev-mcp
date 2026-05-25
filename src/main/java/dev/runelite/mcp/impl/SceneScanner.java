package dev.runelite.mcp.impl;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import dev.runelite.mcp.api.snapshot.ObjectSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans the RuneLite scene for TileObjects (game objects, wall objects,
 * ground objects, decorative objects). RuneLite doesn't expose a flat list
 * of objects — we must iterate tiles across all planes.
 */
public class SceneScanner {

    private final Client client;

    public SceneScanner(Client client) {
        this.client = client;
    }

    public List<ObjectSnapshot> scanObjects() {
        List<ObjectSnapshot> results = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();

        for (int plane = 0; plane < tiles.length; plane++) {
            if (tiles[plane] == null) continue;
            for (int x = 0; x < tiles[plane].length; x++) {
                if (tiles[plane][x] == null) continue;
                for (int y = 0; y < tiles[plane][x].length; y++) {
                    Tile tile = tiles[plane][x][y];
                    if (tile == null) continue;
                    scanTile(tile, results);
                }
            }
        }
        return results;
    }

    private void scanTile(Tile tile, List<ObjectSnapshot> out) {
        // Game objects
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null) {
            for (GameObject obj : gameObjects) {
                if (obj == null) continue;
                ObjectSnapshot snap = toSnapshot(obj, "GAME_OBJECT");
                if (snap != null) out.add(snap);
            }
        }

        // Wall object
        WallObject wall = tile.getWallObject();
        if (wall != null) {
            ObjectSnapshot snap = toSnapshot(wall, "WALL_OBJECT");
            if (snap != null) out.add(snap);
        }

        // Ground object
        GroundObject ground = tile.getGroundObject();
        if (ground != null) {
            ObjectSnapshot snap = toSnapshot(ground, "GROUND_OBJECT");
            if (snap != null) out.add(snap);
        }

        // Decorative object
        DecorativeObject deco = tile.getDecorativeObject();
        if (deco != null) {
            ObjectSnapshot snap = toSnapshot(deco, "DECORATIVE_OBJECT");
            if (snap != null) out.add(snap);
        }
    }

    private ObjectSnapshot toSnapshot(TileObject obj, String type) {
        ObjectComposition comp = client.getObjectDefinition(obj.getId());
        if (comp == null) return null;

        // Handle transforms (e.g., tree stumps)
        ObjectComposition transformed = comp.getImpostorIds() != null
            ? comp.getImpostor() : comp;
        if (transformed == null) transformed = comp;

        WorldPoint wp = obj.getWorldLocation();
        return new ObjectSnapshot(
            obj.getId(),
            transformed.getName(),
            wp.getX(), wp.getY(), wp.getPlane(),
            transformed.getActions(),
            type
        );
    }
}
