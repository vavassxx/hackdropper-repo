# PR: Multi-region worlds for openRPGator

## Summary

This fork adds the engine-side primitives required to host a large persistent world as multiple independently simulated regions. The feature is intentionally game-agnostic; Asteria Online uses it as a consumer, but the engine changes can be reviewed and merged independently.

## Motivation

The original runtime models one global `GameRuntime.map`. That is convenient for small maps but becomes a poor fit for an MMO world: loading one enormous collision/map structure, simulating every entity in one world, and treating a region change as game-specific Lua logic all couple world size to a single runtime instance.

This PR introduces an explicit region boundary while preserving the existing client wire protocol.

## Engine changes

### Map format v3

- Adds `MapPortal` records to `.rmap`.
- A portal contains a stable id, source position/radius, target region id, and destination position.
- v1/v2 maps remain readable; they simply contain no portals.

### Region catalog

- `RegionCatalog` discovers `*.rmap` files from a host directory.
- Filename stem is the stable region id.
- Startup validation rejects portals pointing at unknown regions.

### Runtime/world model

- A dedicated host can own one `GameRuntime`/`GameWorld` per region.
- Player entity ids remain stable while the player moves between regions.
- `WorldRegistry.adopt(...)` supports transferring an entity id into the destination world.
- Each region can retain its own collision grid and Lua runtime.

### Transitions

- Authoritative movement checks the current map's `MapPortal` records.
- Lua can request an explicit transition through `engine.transition(...)`.
- The client receives the existing `MapPacket` for the destination region, so no new client wire protocol is required.
- Region topology remains server-owned and is not exposed to clients.

### Editors / compatibility

- Map editing preserves portal records when maps are saved.
- Existing maps remain readable.

### Script loading

- `world.lua` is loaded automatically by the runtime when present, matching the documented host model.

## Why this belongs in mainline

The change is intentionally below the game layer. It provides a reusable primitive for any host that needs a world larger than one runtime instance, without embedding MMO-specific rules, content, progression, or NPC logic in the engine.

## Asteria Online consumer

The accompanying game content uses six 256×256 regions:

- `asteria`
- `blackwood`
- `highlands`
- `sunscorch`
- `ruins`
- `coast`

Each region is independently simulated and connected through engine-level portals.

## Compatibility

- Old `.rmap` v1/v2 files remain readable.
- The network protocol is unchanged.
- Existing clients can render a destination region because a transition is represented by the already existing `MapPacket`.

## Validation performed

- Changed engine/map/world Java sources compile with JDK 21 in the supplied sandbox.
- Region catalog validation was exercised against all six Asteria regions.
- Portal references resolve to existing regions.
- The generated v3 maps round-trip through `RMapIO`.

A complete Gradle test/runtime launch was not possible in the supplied sandbox because Gradle/LuaJ dependencies were unavailable there.

## Suggested review order

1. `engine-map/src/main/java/rpg/engine/map/MapPortal.java`
2. `engine-map/src/main/java/rpg/engine/map/RMapIO.java`
3. `engine-map/src/main/java/rpg/engine/map/RegionCatalog.java`
4. `engine-world/src/main/java/rpg/engine/world/WorldRegistry.java`
5. `dedicated-server/src/main/java/rpg/engine/server/ServerHost.java`
6. `docs/regions.md`
