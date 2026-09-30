# Asteria Online — MMO host

`game/host` is a playable multiplayer world built directly on openRPGator. It intentionally uses the engine's host-driven model: the client renders snapshots/UI; gameplay state and rules stay on the server.

## Included

- Six persistent 256×256 regions connected by authoritative engine-level portals: Asteria Kingdom, Blackwood Forest, Ironcrest Highlands, Sunscorch Desert, Eastern Ruins and Azure Coast.
- Authoritative tile collision and finite world bounds.
- Per-character persistence in `host/players/` via the engine's atomic `PlayerStore`.
- Three classes: Guardian, Ranger, Arcanist.
- Level/XP progression, HP/MP regeneration and death/respawn.
- Melee/ranged/magic attacks and class secondary abilities.
- Wolves, skeletons and slimes with chase/attack AI, loot and timed respawn.
- Wolf-hunt and ruins quests with per-character progress and rewards.
- Merchant, healer, inn, blacksmith, guide, portals and personal chest loot.
- Server-generated HUD, inventory and quest screens; no game-specific client code required.
- Input normalization, movement-speed validation, action rate limiting and 24-tile interest-managed snapshots.

## Run

From the repository root:

```text
gradle :dedicated-server:installDist
./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
  --data-dir game --port 27800 --tick-rate 20
```

Then start the desktop client and connect it to `127.0.0.1:27800`. Android can use the same remote host.

Player saves are under `game/host/players/`. Back them up before upgrading the world.

## Controls

- WASD / arrows — movement
- J — primary attack
- K — class ability
- L — interact
- I — inventory
- C — camera follow

## Content format

The region maps are stored in `game/host/` and are discovered by the host region catalog; Lua scripts are loaded automatically from each region directory. `asteria.rmap` contains both the visual ground layer and a server-only collision layer. The collision layer is never sent to clients.
