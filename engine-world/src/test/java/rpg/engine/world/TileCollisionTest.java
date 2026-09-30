package rpg.engine.world;

import org.junit.jupiter.api.Test;
import rpg.engine.core.component.CircleCollider;
import rpg.engine.core.component.Transform;
import rpg.engine.core.math.WorldPosition;

import static org.junit.jupiter.api.Assertions.*;

class TileCollisionTest {
    @Test void blockedTilesStopMovementButOpenTilesRemainWalkable() {
        var world = new GameWorld();
        var player = world.spawn();
        world.entities().set(player, new Transform(new WorldPosition(1.5, 1.5, 0), 0));
        world.entities().set(player, new CircleCollider(.30));

        boolean[] blocked = new boolean[16];
        blocked[1 * 4 + 2] = true;
        world.collision().setTileCollision(4, 4, 1.0, blocked);

        assertTrue(world.collision().canOccupy(player, new WorldPosition(1.5, 1.5, 0)));
        assertFalse(world.collision().canOccupy(player, new WorldPosition(2.5, 1.5, 0)));
        assertTrue(world.collision().canOccupy(player, new WorldPosition(2.5, 2.5, 0)));
        assertFalse(world.collision().canOccupy(player, new WorldPosition(-1, 1.5, 0)));
    }
}
