package rpg.engine.script;

import rpg.engine.core.math.WorldPosition;

/** Host callback used by server-side scripts to request a region transition. */
@FunctionalInterface
public interface RegionTransitionSink {
    void transition(long playerEntityId, String targetRegion, WorldPosition targetPosition);
}
