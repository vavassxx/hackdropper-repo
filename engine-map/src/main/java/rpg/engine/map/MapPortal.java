package rpg.engine.map;

import rpg.engine.core.math.WorldPosition;

/** A server-authoritative exit from one map region into another. */
public record MapPortal(String id, WorldPosition position, double radius, String targetRegion, WorldPosition targetPosition) {
    public MapPortal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("portal id");
        if (position == null || targetPosition == null) throw new IllegalArgumentException("portal position");
        if (radius <= 0) throw new IllegalArgumentException("portal radius <= 0");
        if (targetRegion == null || targetRegion.isBlank()) throw new IllegalArgumentException("target region");
    }
}
