package dev.runelite.mcp;

import com.google.gson.JsonObject;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionToolHelpersTest {
    @Test
    void movementFlagsDecodeRuneLiteCollisionBits() throws Exception {
        JsonObject movement = invokeJson("movementFlagsJson", CollisionDataFlag.BLOCK_MOVEMENT_NORTH);

        assertTrue(movement.get("north").getAsBoolean());
        assertFalse(movement.get("object").getAsBoolean());
        assertFalse(movement.get("east").getAsBoolean());
        assertFalse(movement.get("full").getAsBoolean());

        JsonObject objectMovement = invokeJson("movementFlagsJson", CollisionDataFlag.BLOCK_MOVEMENT_OBJECT);
        assertTrue(objectMovement.get("object").getAsBoolean());
        assertTrue(objectMovement.get("full").getAsBoolean());
    }

    @Test
    void lineOfSightFlagsDecodeRuneLiteCollisionBits() throws Exception {
        JsonObject lineOfSight = invokeJson("lineOfSightFlagsJson",
            CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST | CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL);

        assertTrue(lineOfSight.get("east").getAsBoolean());
        assertTrue(lineOfSight.get("full").getAsBoolean());
        assertFalse(lineOfSight.get("north").getAsBoolean());
    }

    @Test
    void travelChecksCurrentAndDestinationTileFlags() throws Exception {
        int[][] flags = new int[Constants.SCENE_SIZE][Constants.SCENE_SIZE];
        JsonObject open = invokeTravelJson(10, 10, flags);
        assertTrue(open.get("north").getAsBoolean());
        assertTrue(open.get("east").getAsBoolean());

        flags[10][10] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        JsonObject blockedFromCurrent = invokeTravelJson(10, 10, flags);
        assertFalse(blockedFromCurrent.get("north").getAsBoolean());
        assertTrue(blockedFromCurrent.get("east").getAsBoolean());

        flags[11][10] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        JsonObject blockedByDestination = invokeTravelJson(10, 10, flags);
        assertFalse(blockedByDestination.get("east").getAsBoolean());
    }

    private static JsonObject invokeJson(String methodName, int raw) throws Exception {
        Method method = McpToolHandler.class.getDeclaredMethod(methodName, int.class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(null, raw);
    }

    private static JsonObject invokeTravelJson(int sceneX, int sceneY, int[][] flags) throws Exception {
        Method method = McpToolHandler.class.getDeclaredMethod("travelJson", int.class, int.class, int[][].class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(null, sceneX, sceneY, flags);
    }
}
