package dev.runelite.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceGeometryTest {

    /** Pack a template-chunk reference the same way the client does. */
    private static int pack(int templateChunkX, int templateChunkY, int rotation, int plane) {
        // templateChunkX/Y are world coords (multiples of 8); store the chunk index.
        return ((plane & 0x3) << 24)
            | (((templateChunkX / 8) & 0x3FF) << 14)
            | (((templateChunkY / 8) & 0x7FF) << 3)
            | ((rotation & 0x3) << 1);
    }

    private static int[][][] singleChunk(int sceneChunkX, int sceneChunkY, int packed) {
        int[][][] chunks = new int[4][InstanceGeometry.CHUNKS_PER_SIDE][InstanceGeometry.CHUNKS_PER_SIDE];
        for (int z = 0; z < 4; z++)
            for (int x = 0; x < InstanceGeometry.CHUNKS_PER_SIDE; x++)
                for (int y = 0; y < InstanceGeometry.CHUNKS_PER_SIDE; y++)
                    chunks[z][x][y] = -1;
        chunks[0][sceneChunkX][sceneChunkY] = packed;
        return chunks;
    }

    @Test
    void decodeRoundTripsPackedChunk() {
        InstanceGeometry.Chunk c = InstanceGeometry.decode(pack(3200, 3264, 2, 1));
        assertEquals(2, c.rotation);
        assertEquals(3200, c.templateX);
        assertEquals(3264, c.templateY);
        assertEquals(1, c.templatePlane);
    }

    @Test
    void emptyChunkDecodesToNull() {
        assertNull(InstanceGeometry.decode(-1));
    }

    @Test
    void rotationZeroTranslatesToTemplatePlusOffset() {
        // Scene chunk (4,5) -> covers scene tiles x 32..39, y 40..47.
        int[][][] chunks = singleChunk(4, 5, pack(2560, 3000, 0, 0));
        // sceneX=34 (offset 2 in chunk), sceneY=43 (offset 3 in chunk)
        int[] tpl = InstanceGeometry.sceneToTemplate(chunks, 0, 34, 43);
        assertEquals(2560 + 2, tpl[0]);
        assertEquals(3000 + 3, tpl[1]);
        assertEquals(0, tpl[2]);
    }

    @Test
    void rotationIsAppliedWithinChunk() {
        // Same chunk + offset, rotation=1. With 4-rotation=3 applied to the in-chunk
        // offset (2,3): case 3 -> (chunkBase + (7 - y_off), chunkBase + x_off) = (+4, +2).
        int[][][] chunks = singleChunk(4, 5, pack(2560, 3000, 1, 0));
        int[] tpl = InstanceGeometry.sceneToTemplate(chunks, 0, 34, 43);
        assertEquals(2560 + (7 - 3), tpl[0]);
        assertEquals(3000 + 2, tpl[1]);
    }

    @Test
    void rotation180InvertsBothAxesWithinChunk() {
        int[][][] chunks = singleChunk(4, 5, pack(2560, 3000, 2, 0));
        int[] tpl = InstanceGeometry.sceneToTemplate(chunks, 0, 34, 43);
        assertEquals(2560 + (7 - 2), tpl[0]);
        assertEquals(3000 + (7 - 3), tpl[1]);
    }

    @Test
    void outOfRangeAndEmptyReturnNull() {
        int[][][] chunks = singleChunk(4, 5, pack(2560, 3000, 0, 0));
        assertNull(InstanceGeometry.sceneToTemplate(chunks, 0, 0, 0));   // empty chunk (0,0)
        assertNull(InstanceGeometry.sceneToTemplate(chunks, 0, -1, 5));  // negative
        assertNull(InstanceGeometry.sceneToTemplate(chunks, 9, 34, 43)); // plane out of range
        assertNull(InstanceGeometry.sceneToTemplate(null, 0, 34, 43));   // null chunks
    }

    @Test
    void nonInstancedJsonOmitsChunksAndPlayer() {
        JsonObject o = InstanceGeometry.toJson(false, 3200, 3200, 0, 3205, 3208,
            new int[]{12850, 12851}, null);
        assertFalse(o.get("instanced").getAsBoolean());
        assertEquals(3200, o.get("baseX").getAsInt());
        JsonArray regions = o.getAsJsonArray("mapRegions");
        assertEquals(2, regions.size());
        assertEquals(12850, regions.get(0).getAsInt());
        assertFalse(o.has("chunks"));
        assertFalse(o.has("player"));
    }

    @Test
    void instancedJsonIncludesPlayerTranslationAndChunkMap() {
        // base (2496,3008); player at instance world (2496+34, 3008+43) -> scene (34,43).
        int[][][] chunks = singleChunk(4, 5, pack(2560, 3000, 0, 0));
        JsonObject o = InstanceGeometry.toJson(true, 2496, 3008, 0,
            2496 + 34, 3008 + 43, new int[]{}, chunks);
        assertTrue(o.get("instanced").getAsBoolean());

        JsonObject player = o.getAsJsonObject("player");
        JsonArray scene = player.getAsJsonArray("scene");
        assertEquals(34, scene.get(0).getAsInt());
        assertEquals(43, scene.get(1).getAsInt());
        JsonArray template = player.getAsJsonArray("template");
        assertEquals(2562, template.get(0).getAsInt());
        assertEquals(3003, template.get(1).getAsInt());

        JsonArray chunkArr = o.getAsJsonArray("chunks");
        assertEquals(1, chunkArr.size());
        JsonObject cj = chunkArr.get(0).getAsJsonObject();
        assertEquals(4, cj.get("chunkX").getAsInt());
        assertEquals(5, cj.get("chunkY").getAsInt());
        assertEquals(2560, cj.get("templateX").getAsInt());
        assertEquals(3000, cj.get("templateY").getAsInt());
    }
}
