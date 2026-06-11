package dev.runelite.mcp.api;

import dev.runelite.mcp.api.snapshot.*;

import java.util.List;

/**
 * Reads game state as immutable snapshots. Client-agnostic.
 * Implementations translate a specific client's API into these snapshot types.
 */
public interface WorldReader {

    // Player
    PlayerSnapshot getLocalPlayer();
    List<PlayerSnapshot> getOtherPlayers();

    // Entities
    List<NpcSnapshot> getNpcs();
    List<ObjectSnapshot> getObjects();
    List<GroundItemSnapshot> getGroundItems();

    // Scene-level effects — projectiles in flight (magic/ranged shots) and
    // graphics objects (rockfalls, fire walls, AoE telegraphs). Default to
    // empty lists so synthetic/test clients without scene data still work.
    default List<ProjectileSnapshot> getProjectiles() { return java.util.Collections.emptyList(); }
    default List<GraphicsObjectSnapshot> getGraphicsObjects() { return java.util.Collections.emptyList(); }

    // Containers
    List<ItemSnapshot> getInventory();
    List<ItemSnapshot> getEquipment();
    List<ItemSnapshot> getBankItems();
    /** Read any item container by its InventoryID (e.g. seed vault 626, looting bag 516). Empty when absent. */
    default List<ItemSnapshot> getItemContainerById(int containerId) { return java.util.Collections.emptyList(); }

    /**
     * Resolve an item ID to its in-game display name (e.g. 385 → "Shark").
     * Returns {@code null} when the client doesn't expose item compositions
     * (synthetic / test backends) or the ID is unknown. Used by the
     * bank interface to type into the bank search box when an item isn't
     * currently visible in the pane.
     */
    default String getItemName(int itemId) { return null; }

    // Skills
    int getSkillLevel(int skillId);
    int getBoostedLevel(int skillId);
    int getSkillXp(int skillId);

    // Vars
    int getVarbitValue(int varbitId);
    int getVarpValue(int varpId);
    /** Client-side integer var (VarClientInt) — interface/session state not in varbits/varps. */
    default int getVarcIntValue(int varcId) { return 0; }
    /** Client-side string var (VarClientStr) — e.g. typed search text. Null when unset/unsupported. */
    default String getVarcStrValue(int varcId) { return null; }

    // Widgets
    boolean isWidgetVisible(int group, int child);
    String getWidgetText(int group, int child);
    WidgetSnapshot getWidget(int group, int child);

    // Dialogue
    DialogueSnapshot getDialogue();

    // Interface state
    InterfaceSnapshot getInterfaces();

    // Game state
    int getGameTick();
    int getGameState();
    int getEnergy();
    int getWeight();
    /** Current world number (client.getWorld). -1 when unsupported. */
    default int getWorld() { return -1; }
    /** Active WorldType flag names (MEMBERS, PVP, SEASONAL/Leagues, etc.). Empty when none/unsupported. */
    default List<String> getWorldTypes() { return java.util.Collections.emptyList(); }
    /**
     * Project a world tile to canvas pixels at the current camera pose. Returns {@code [px, py]},
     * or {@code null} when the tile is off-screen / not rendered. Live-only (no meaning in buffer history).
     */
    default int[] worldPointToScreen(int worldX, int worldY, int plane) { return null; }

    // Scene base — world coords of the loaded scene's (0,0) corner. Needed
    // alongside player.worldX so recordings can disambiguate instance-local
    // vs template-world coordinate spaces during post-hoc analysis. Default
    // returns 0 for tests / synthetic clients that don't track a scene.
    default int getSceneBaseX() { return 0; }
    default int getSceneBaseY() { return 0; }
    /** True iff the player is currently inside an OSRS instance (custom-built scene). */
    default boolean isInInstance() { return false; }
    /** Loaded map region ids for the current scene (client.getMapRegions). Empty when unsupported. */
    default int[] getMapRegions() { return new int[0]; }
    /**
     * Per-chunk instance template map: {@code [plane][chunkX][chunkY]} packed ints, or null when
     * not in an instance. Each entry encodes the rotation + template world chunk the scene chunk
     * was copied from; {@link dev.runelite.mcp.InstanceGeometry} decodes/translates it.
     */
    default int[][][] getInstanceTemplateChunks() { return null; }

    // Camera / viewport
    /** Camera yaw in OSRS units (0-2047). 0 faces south; increases counter-clockwise. */
    default int getCameraYaw() { return 0; }
    /** Camera pitch in OSRS units. ~128 (top-down) to ~383 (horizontal). */
    default int getCameraPitch() { return 0; }
    /** Camera position in scene/local coordinates (for yaw-delta computations). */
    default int getCameraX() { return 0; }
    default int getCameraY() { return 0; }
    default int getCameraZ() { return 0; }

    /**
     * Whether a world point projects onto the visible canvas at the current camera pose.
     * Returns false if the point is behind the camera, clipped, or occluded by the viewport edge.
     */
    default boolean isWorldPointOnScreen(int worldX, int worldY, int plane) { return false; }

    /**
     * Fraction of the entity's clickbox (in screen pixels) currently within the canvas.
     * Returns 0.0 when the entity is not being rendered, 1.0 when its bounding rect is
     * fully inside the canvas. Intermediate values indicate partial visibility.
     *
     * @param type         entity type: "npc", "object", "player"
     * @param identifier   npc index, object id, or player index (unused for other types)
     * @param worldX,worldY,plane   world tile, required for objects whose bounds need a tile anchor
     */
    default float getEntityScreenCoverage(String type, int identifier,
                                          int worldX, int worldY, int plane) {
        return isWorldPointOnScreen(worldX, worldY, plane) ? 1.0f : 0.0f;
    }

    /**
     * Maximum of the entity's screen-space bounding-box width and height as a
     * fraction of canvas size. 0 if the entity isn't being rendered, 1.0 when
     * the bounds span a full canvas dimension. Values &gt; 1.0 mean the entity
     * extends past the canvas in at least one axis — rotation alone can't
     * frame it, only zooming out can.
     */
    default float getEntityMaxBoundFraction(String type, int identifier,
                                            int worldX, int worldY, int plane) {
        return 0f;
    }

    /** Canvas dimensions (for mapping yaw deltas to pixel drag distances). */
    default int getCanvasWidth() { return 0; }
    default int getCanvasHeight() { return 0; }

    /**
     * OSRS 3D camera zoom. Higher values = more zoomed in. Typical range
     * is ~300 (far out) to ~1500 (very close). Returned directly from the
     * RuneLite Client — used by the framing logic to prefer zoom-out over
     * rotation when the camera is already tight.
     */
    default int getCameraZoom() { return 0; }
}
