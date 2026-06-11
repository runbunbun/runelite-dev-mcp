package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Pure instance/region coordinate translation, mirroring RuneLite's
 * {@code WorldPoint.fromLocalInstance}.
 *
 * <p>Inside an OSRS instance the 104x104 scene is assembled from 8x8 template chunks
 * copied (and rotated) from elsewhere in the world, so an actor's reported world
 * coordinates are arbitrary absolute numbers that don't correspond to any real-world
 * location. This translates an instance scene tile back to the template world tile it
 * was copied from, and packages the full chunk map so a consumer can translate any tile
 * itself. All math is pure (no client dependency) so it is unit-testable.
 */
public final class InstanceGeometry {

    static final int CHUNK_SIZE = 8;
    static final int SCENE_SIZE = 104;
    static final int CHUNKS_PER_SIDE = SCENE_SIZE / CHUNK_SIZE; // 13

    private InstanceGeometry() {}

    /** One decoded template-chunk reference. */
    public static final class Chunk {
        public final int rotation;
        public final int templateX;     // world X of the template chunk's SW corner
        public final int templateY;
        public final int templatePlane;

        Chunk(int rotation, int templateX, int templateY, int templatePlane) {
            this.rotation = rotation;
            this.templateX = templateX;
            this.templateY = templateY;
            this.templatePlane = templatePlane;
        }
    }

    /** Decode a packed instance-template-chunk int. Returns {@code null} for empty (-1) chunks. */
    public static Chunk decode(int packed) {
        if (packed == -1) return null;
        int rotation = packed >> 1 & 0x3;
        int templateChunkY = (packed >> 3 & 0x7FF) * CHUNK_SIZE;
        int templateChunkX = (packed >> 14 & 0x3FF) * CHUNK_SIZE;
        int templateChunkPlane = packed >> 24 & 0x3;
        return new Chunk(rotation, templateChunkX, templateChunkY, templateChunkPlane);
    }

    /**
     * Translate an instance scene tile (each axis 0..103) to the template world tile it was
     * copied from. Returns {@code [worldX, worldY, plane]}, or {@code null} when the chunk is
     * empty or indices are out of range. Mirrors {@code WorldPoint.fromLocalInstance}.
     */
    public static int[] sceneToTemplate(int[][][] chunks, int plane, int sceneX, int sceneY) {
        if (chunks == null) return null;
        int chunkX = sceneX / CHUNK_SIZE;
        int chunkY = sceneY / CHUNK_SIZE;
        if (plane < 0 || plane >= chunks.length) return null;
        if (chunkX < 0 || chunkX >= CHUNKS_PER_SIDE || chunkY < 0 || chunkY >= CHUNKS_PER_SIDE) return null;
        Chunk c = decode(chunks[plane][chunkX][chunkY]);
        if (c == null) return null;
        int x = c.templateX + (sceneX & (CHUNK_SIZE - 1));
        int y = c.templateY + (sceneY & (CHUNK_SIZE - 1));
        // Rotate the in-chunk offset back to the template's orientation (4 - rotation).
        return rotate(x, y, 4 - c.rotation, c.templatePlane);
    }

    /** Chunk-relative rotation, mirroring RuneLite's {@code WorldPoint.rotate}. */
    static int[] rotate(int x, int y, int rotation, int plane) {
        int chunkX = x & ~(CHUNK_SIZE - 1);
        int chunkY = y & ~(CHUNK_SIZE - 1);
        int lx = x & (CHUNK_SIZE - 1);
        int ly = y & (CHUNK_SIZE - 1);
        switch (rotation & 0x3) {
            case 1:  return new int[]{ chunkX + ly, chunkY + (CHUNK_SIZE - 1 - lx), plane };
            case 2:  return new int[]{ chunkX + (CHUNK_SIZE - 1 - lx), chunkY + (CHUNK_SIZE - 1 - ly), plane };
            case 3:  return new int[]{ chunkX + (CHUNK_SIZE - 1 - ly), chunkY + lx, plane };
            default: return new int[]{ x, y, plane };
        }
    }

    /**
     * Build the {@code instance} state section. Always emits {@code instanced}, {@code baseX/Y},
     * {@code plane} and {@code mapRegions}. When inside an instance, also emits the player's own
     * scene→template translation and the full non-empty chunk map.
     */
    public static JsonObject toJson(boolean instanced, int baseX, int baseY, int plane,
                                    int playerWorldX, int playerWorldY,
                                    int[] mapRegions, int[][][] chunks) {
        JsonObject o = new JsonObject();
        o.addProperty("instanced", instanced);
        o.addProperty("baseX", baseX);
        o.addProperty("baseY", baseY);
        o.addProperty("plane", plane);

        JsonArray regions = new JsonArray();
        if (mapRegions != null) for (int r : mapRegions) regions.add(r);
        o.add("mapRegions", regions);

        if (!instanced || chunks == null) return o;

        // Player's own translation: instance world coords -> scene tile -> template world tile.
        int sceneX = playerWorldX - baseX;
        int sceneY = playerWorldY - baseY;
        int[] tpl = sceneToTemplate(chunks, plane, sceneX, sceneY);
        if (tpl != null) {
            JsonObject pj = new JsonObject();
            JsonArray scene = new JsonArray();
            scene.add(sceneX);
            scene.add(sceneY);
            pj.add("scene", scene);
            JsonArray t = new JsonArray();
            t.add(tpl[0]);
            t.add(tpl[1]);
            t.add(tpl[2]);
            pj.add("template", t);
            o.add("player", pj);
        }

        // Full non-empty chunk map so a consumer can translate any tile itself.
        JsonArray chunkArr = new JsonArray();
        for (int z = 0; z < chunks.length; z++) {
            if (chunks[z] == null) continue;
            for (int cx = 0; cx < chunks[z].length; cx++) {
                if (chunks[z][cx] == null) continue;
                for (int cy = 0; cy < chunks[z][cx].length; cy++) {
                    Chunk c = decode(chunks[z][cx][cy]);
                    if (c == null) continue;
                    JsonObject cj = new JsonObject();
                    cj.addProperty("plane", z);
                    cj.addProperty("chunkX", cx);   // scene chunk index 0..12
                    cj.addProperty("chunkY", cy);
                    cj.addProperty("rotation", c.rotation);
                    cj.addProperty("templateX", c.templateX);
                    cj.addProperty("templateY", c.templateY);
                    cj.addProperty("templatePlane", c.templatePlane);
                    chunkArr.add(cj);
                }
            }
        }
        o.add("chunks", chunkArr);
        return o;
    }
}
