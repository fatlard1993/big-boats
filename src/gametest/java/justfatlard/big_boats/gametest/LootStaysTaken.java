package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * A loot chest aboard a ship is looted once. A worldgen ship's chests carry an unrolled loot table,
 * as a pirate ship's do; empty one and sail, and docking must not hand back a full one.
 */
public final class LootStaysTaken implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX(), deck = origin.getY(), z = origin.getZ();
			fill(server, x - 40, deck - 4, z - 40, x + 40, deck - 1, z + 40, "minecraft:water");
			fill(server, x - 40, deck, z - 40, x + 40, deck + 20, z + 40, "minecraft:air");
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 2, deck + 1, z - 4, x - 2, deck + 1, z + 3, "minecraft:spruce_planks");
			fill(server, x + 2, deck + 1, z - 4, x + 2, deck + 1, z + 3, "minecraft:spruce_planks");
			fill(server, x - 2, deck + 1, z - 4, x + 2, deck + 1, z - 4, "minecraft:spruce_planks");
			fill(server, x - 2, deck + 2, z - 4, x + 2, deck + 2, z - 4, "minecraft:spruce_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			BlockPos chest = new BlockPos(x, deck + 1, z);
			BlockPos second = new BlockPos(x + 1, deck + 1, z + 2);
			server.runOnServer(s -> {
				ResourceKey<net.minecraft.world.level.storage.loot.LootTable> dungeon = ResourceKey.create(Registries.LOOT_TABLE,
					Identifier.withDefaultNamespace("chests/simple_dungeon"));
				for (BlockPos at : new BlockPos[] {chest, second}) {
					s.overworld().setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
					((ChestBlockEntity) s.overworld().getBlockEntity(at)).setLootTable(dungeon, 42L + at.asLong());
				}
			});

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x, deck + 10, z));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			// The first chest is looted where it stands, before the ship ever sails.
			server.runOnServer(s -> {
				ChestBlockEntity box = (ChestBlockEntity) s.overworld().getBlockEntity(chest);
				box.unpackLootTable(connection.getServerPlayer());
				check(!box.isEmpty(), "the chest held loot to take: the scene is wrong, not the ship");
				box.clearContent();
			});

			for (int voyage = 1; voyage <= 2; voyage++) {
				int v = voyage;
				server.runOnServer(s -> {
					MultiBlockShipEntity ship = ship(s, x, deck, z);
					check(ship.tryMount(connection.getServerPlayer()), "the ship takes its pilot");
					ship.undock();
				});
				context.waitTicks(10);
				server.runOnServer(s -> {
					MultiBlockShipEntity ship = ship(s, x, deck, z);
					connection.getServerPlayer().stopRiding();
					ship.dock();
				});
				context.waitTicks(10);
				server.runOnServer(s -> {
					MultiBlockShipEntity ship = ship(s, x, deck, z);
					BlockPos looted = chestNear(s, ship, chest);
					ChestBlockEntity box = (ChestBlockEntity) s.overworld().getBlockEntity(looted);
					box.unpackLootTable(connection.getServerPlayer());
					check(box.isEmpty(), "voyage " + v + ": a chest emptied before sailing came back full");

					// The second is opened at sea's end, the first time; from then on it is spent too.
					BlockPos other = chestNear(s, ship, second);
					ChestBlockEntity unopened = (ChestBlockEntity) s.overworld().getBlockEntity(other);
					unopened.unpackLootTable(connection.getServerPlayer());
					if (v == 1) check(!unopened.isEmpty(), "a chest nobody opened still has its loot after a voyage");
					else check(unopened.isEmpty(), "voyage 2: a chest emptied after the first voyage came back full");
					unopened.clearContent();
				});
			}
		}
	}

	private static MultiBlockShipEntity ship(net.minecraft.server.MinecraftServer s, int x, int y, int z) {
		var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(new BlockPos(x, y, z)).inflate(32));
		check(!ships.isEmpty(), "no ship: the scene never christened");
		return ships.get(0);
	}

	/** Where a chest that started at {@code start} stands now: a ship docks wherever it stopped. */
	private static BlockPos chestNear(net.minecraft.server.MinecraftServer s, MultiBlockShipEntity ship, BlockPos start) {
		BlockPos found = null;
		double best = Double.MAX_VALUE;
		for (BlockPos at : BlockPos.betweenClosed(start.offset(-12, -2, -12), start.offset(12, 2, 12))) {
			if (s.overworld().getBlockEntity(at) instanceof ChestBlockEntity) {
				double d = at.distSqr(start);
				if (d < best) {
					best = d;
					found = at.immutable();
				}
			}
		}
		check(found != null, "the chest is gone after docking");
		return found;
	}

	private static void check(boolean ok, String complaint) {
		if (!ok) throw new AssertionError(complaint);
	}

	/** In slabs, since /fill stops at 32768 blocks. */
	private void fill(TestServerContext server, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		int area = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
		int slab = Math.max(1, 32768 / Math.max(1, area));
		for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y += slab) {
			server.runCommand("fill %d %d %d %d %d %d %s".formatted(x1, y, z1, x2, Math.min(Math.max(y1, y2), y + slab - 1), z2, block));
		}
	}
}
