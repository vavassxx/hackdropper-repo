package rpg.engine.map;

import org.junit.jupiter.api.Test;
import rpg.engine.core.math.WorldPosition;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionCatalogTest {
    @Test void portalRoundTripAndCatalogValidation() throws Exception {
        Path dir = Files.createTempDirectory("regions");
        RMap town = new RMap("Town", 32, 4, 4,
                List.of(new TileLayer("ground", 4, 4, new int[16], true)), List.of(),
                List.of(new MapPortal("east", new WorldPosition(3,2,0), 1.5, "forest", new WorldPosition(1,2,0))));
        RMap forest = new RMap("Forest", 32, 4, 4,
                List.of(new TileLayer("ground", 4, 4, new int[16], true)), List.of(), List.of());
        RMapIO.write(town, dir.resolve("town.rmap"));
        RMapIO.write(forest, dir.resolve("forest.rmap"));
        RegionCatalog catalog = RegionCatalog.load(dir);
        assertEquals(2, catalog.size());
        assertEquals("forest", catalog.find("town").orElseThrow().map().portals().get(0).targetRegion());
        assertTrue(catalog.find("town").orElseThrow().map().portalAt(new WorldPosition(3,2,0)).isPresent());
    }
}
