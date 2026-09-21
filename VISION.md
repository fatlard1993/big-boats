# Big Boats: Vision

## What It Is

A server-side Fabric mod that lets players build ships out of any blocks and sail them. The vanilla Minecraft promise, build anything, extended to the water. You build a dock and a ship because that's what people do. Big Boats makes the ship actually work.

## Destination

Any player-built structure becomes a sailable ship with no block or inventory loss.

This is not a naval warfare mod. Not a tech mod. Not a framework. It's the simplest possible answer to "I built a ship, why can't I sail it?"

The core features are built, including ship-to-ship collision. Work since has gone both ways: new growth (walkable decks, reefs, painted chests, one pivot for everything that turns) alongside hardening the no-loss contract. What is still owed is multiplayer testing and the edge cases solo testing does not find.

## Who It's For

Players who build ships and want to use them. Throw the bottle, sail the ship.

## Principles

1. **Vanilla feel.** If Mojang added ships, they'd work like this. No HUD. No special crafting tables. WASD and shift. The christening bottle is the only new concept, and it's a throwable potion, and players already know the gesture.

2. **No loss.** Blocks, inventories, decorations, block entities: everything survives the full lifecycle. Dock, undock, sail, crash the server, restart, dock again. Nothing missing. This is the core contract and the hardest problem.

3. **Server-side authoritative.** Pandorical is the delivery mechanism for rendering and camera control, both pushed from the server. Pandorical is a required client-side dependency, not an optional add-on; there is no vanilla-client path.

4. **Performance is a feature.** Hull-only collision, tick-spreading, deduplication. The mod should run on a modest server with multiple active ships without degrading tick rate. The 2,000 block limit exists for this reason and will rise only when measurements justify it.

5. **One ship, one helm, one throw.** No configuration. No modes. No menus. The complexity budget is spent on making the simple path work perfectly, not on adding options.

## What Success Looks Like

- A player builds a 200-block galleon on their SMP, christens it, and sails it across the ocean. They dock at a friend's base. The friend boards and they sail together (friend standing on the deck). They dock again. Every chest is full. Every item frame is placed. Every door works.

- A server with 3-4 active ships doesn't notice the mod in its tick timing.

- A server crashes mid-sail. On restart, every ship is docked with all blocks restored.

- Two ships collide and stop. Neither clips through the other.

## What It's Not

- Not a naval combat mod. No cannons, no damage model, no sinking.
- Not a simulation. Ships float, move, and collide. They don't have draft, buoyancy, wind, or material-based speed. Vanilla feel means vanilla simplicity.

## Architecture

The ship entity delegates behavior to focused components, all receiving a `ShipPose` to transform coordinates:

- **ShipPhysics**: velocity, acceleration, drag. Stateful (owns velocity).
- **ShipRiders**: everything standing on the deck, carried by velocity rather than by teleport so a rider keeps control of their own feet. Stateful (remembers who is aboard, and where on the deck).
- **ShipSeats**: the cushions a ship carries, and the seated riders who ride them as passengers. Stateful (owns the seat list).
- **ShipCollision**: hull block computation and world collision checks. Stateful (owns hull set).
- **ShipCollisionEntities**: invisible shulker lifecycle for server-side collision, plus helm interaction entity. Stateful.
- **ShipLighting**: light-emitting block detection and invisible light block placement/movement. Stateful.
- **ShipStructure**: thin wrapper around Pandorical's structure API for rendering ship blocks to Pandorical clients as a single batch-rendered structure.
- **ShipInteraction**: stateless helper for player interaction with doors/trapdoors/fence gates on moving ships.
- **ShipDecoration**: record capturing item frames and paintings attached to the ship, serialized during undock, restored on dock.
- **ShipDocking**: world mutations for dock/undock: block placement/removal, decoration capture/restore, block entity salvaging.
- **ShipConfig**: central tuning constants with documented rationale. All values static; no runtime config.
- **ShipPose**: immutable record bundling helm position + rotation. Passed to every delegate.
- **ShipBlockUtils**: shared rotation math, breakability checks, cached seat offsets.
- **ShipBlock**: record for a single block: relative position, state, optional block entity NBT.

MultiBlockShipEntity owns the state machine, tick loop, and delegate coordination.

## What's Left

Ranked by impact:

1. **Multiplayer testing.** The mod has been developed single-player. Passengers standing on the structure, multiple ships in the same area, concurrent mount/dismount, chunk loading boundaries with multiple players: all untested. Risk of silent breakage.

2. **Hardening the no-loss contract.** Transition state handling during entity removal, and edge cases in the dock/undock cycle. Every block loss is a bug.

3. **Architecture cleanup.** Serialization could move to a codec helper.

4. **Performance measurement.** The hull optimization is in place but not benchmarked. Ship-to-ship collision queries are O(n*m) per tick per ship; the hull positions behind them are memoised per tick, the queries themselves are not. Tick-spreading for the collision hull has been removed - it never fired, because its interval was one. Need actual numbers before raising the block limit or claiming server-friendly.
