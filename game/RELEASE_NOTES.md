# Asteria Online — release notes

This is a content-and-engine upgrade of the supplied openRPGator master archive.

## Engine changes

- Collision layers from `.rmap` are now enforced by the authoritative server.
- Finite map bounds are enforced when a collision layer exists.
- Player names are normalized and duplicate active characters are rejected.
- Player input is normalized and movement is capped by server time rather than packet frequency.
- Combat/UI actions are rate-limited per connection.
- Snapshot delivery is interest-managed around each player and no longer triggered directly by every movement packet.
- Player persistence keys are case-insensitive.
- Added a regression test for tile collision.

## Game content

`game/host` contains the complete Asteria Online world:

- persistent characters;
- three classes;
- combat and class abilities;
- mobs with aggro, damage, loot and respawn;
- XP/levels and regeneration;
- quests, shops, crafting-style weapon upgrade;
- NPCs, portals and personal chest loot;
- host-driven HUD/inventory/quest screens;
- CC0 asset pack copied into `asteria.pak`.

## Known environment limitation

The supplied sandbox has JDK 21 but does not contain Gradle/LuaJ and cannot resolve external
artifacts from the network, so a full `gradle test`/runtime launch could not be executed here.
The core/map/world production sources compile locally in the sandbox, the generated RMAP round-trips
through the project's `RMapIO`, and the modified dedicated-server source has no Java compiler errors
beyond the expected missing LuaJ dependency.
