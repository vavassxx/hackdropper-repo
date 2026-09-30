# Regions and transitions

openRPGator supports multi-region hosts without requiring the client to know the world topology.

## Map format v3

`.rmap` v3 adds `MapPortal` records after entities. Each portal contains:

- stable portal id;
- source position and trigger radius;
- target region id;
- destination position in the target region.

v1/v2 maps remain readable and simply have no portals.

## Region catalog

`RegionCatalog.load(directory)` discovers `*.rmap` files. The filename stem is the stable region id.
It validates that every portal points at an existing region before the host starts.

## Server behaviour

The dedicated host treats every region as an independent `GameRuntime`/`GameWorld` instance. Player entity IDs remain stable while crossing a portal. Only the player's world instance changes, and the host immediately streams the target region's ground `MapPacket`.

Region worlds have independent Lua runtimes and collision grids, so a large world does not require one enormous collision structure or one global entity update loop.

## Lua

Server scripts may request a transition explicitly:

```lua
engine.transition(player, "blackwood", 8, 128)
```

For ordinary exits, declaring a `MapPortal` is preferable: the host detects it after authoritative movement and performs the transition without trusting the client.

## Compatibility

The wire protocol remains backward compatible. Region entry is represented by the existing `MapPacket`; older clients therefore continue to render the new region after a transition. The region topology itself is host-owned and is not exposed to the client.
