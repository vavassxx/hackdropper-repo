package rpg.engine.map;

import java.util.*;
import rpg.engine.core.math.WorldPosition;

/** Immutable map asset. Region portals are optional and preserve the v2 constructor API. */
public record RMap(String name, int tileSize, int width, int height, List<TileLayer> layers,
                   List<MapEntity> entities, List<MapPortal> portals) {
    public RMap {
        if (width <= 0 || height <= 0 || tileSize <= 0) throw new IllegalArgumentException("invalid map dimensions");
        layers = List.copyOf(layers);
        entities = List.copyOf(entities);
        portals = List.copyOf(portals);
    }
    public RMap(String name, int tileSize, int width, int height, List<TileLayer> layers, List<MapEntity> entities) {
        this(name, tileSize, width, height, layers, entities, List.of());
    }
    /** Returns the first portal whose circular trigger contains the position. */
    public Optional<MapPortal> portalAt(WorldPosition position) {
        for (MapPortal portal : portals) {
            double dx = position.x() - portal.position().x();
            double dy = position.y() - portal.position().y();
            if (dx * dx + dy * dy <= portal.radius() * portal.radius()) return Optional.of(portal);
        }
        return Optional.empty();
    }
}
