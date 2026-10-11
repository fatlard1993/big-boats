package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;

/**
 * The hold of a ship at sea is air. Under way her blocks are not in the world and the sea stands
 * where they are, so a player below her waterline was swimming, saw water and drowned. An open
 * boat's hull keeps it out as a closed one does, and what was dry at sea is bailed dry when she
 * docks, while the sea outside her stays the sea.
 */
public final class DryHoldAtSea implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX(), deck = origin.getY(), z = origin.getZ();
			fill(server, x - 30, deck - 4, z - 30, x + 30, deck - 1, z + 30, "minecraft:water");
			fill(server, x - 30, deck, z - 30, x + 30, deck + 12, z + 30, "minecraft:air");
			// An open hull: a floor under the water and sides standing a block above it, sea inside.
			// 84 planks and the helm, inside what a plain helm holds.
			fill(server, x - 2, deck - 3, z - 3, x + 2, deck, z + 2, "minecraft:oak_planks");
			fill(server, x - 1, deck - 2, z - 2, x + 1, deck - 1, z + 1, "minecraft:water");
			fill(server, x - 1, deck, z - 2, x + 1, deck, z + 1, "minecraft:air");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 1, z - 3));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %.1f %d %.1f {Motion:[0.0,-0.4,0.0]}".formatted(x + 2.5, deck + 10, z + 0.5));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				check(ship.tryMount(connection.getServerPlayer()), "the ship takes its pilot");
				ship.undock();
				// A cow at the wheel keeps her under way while the player goes below.
				var cow = net.minecraft.world.entity.EntityTypes.COW.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				cow.snapTo(ship.getX(), ship.getY(), ship.getZ(), 0, 0);
				s.overworld().addFreshEntity(cow);
				check(cow.startRiding(ship, true, false), "a second rider to keep her sailing");
				connection.getServerPlayer().stopRiding();
			});
			context.waitTicks(20);

			BlockPos below = new BlockPos(x, deck - 1, z);
			server.runOnServer(s -> check(!s.overworld().getFluidState(below).isEmpty(),
				"no sea where her hold is at sea: the scene is not under way"));

			server.runCommand("tp @a %.2f %d %.2f".formatted(x + 0.5, deck - 2, z + 0.5));
			context.waitTicks(60);

			server.runOnServer(s -> {
				var player = connection.getServerPlayer();
				check(!player.isInWater(), "the server has the player in water in her hold");
				check(player.getAirSupply() == player.getMaxAirSupply(),
					"the player lost air in her hold: " + player.getAirSupply() + " of " + player.getMaxAirSupply());
			});
			check(!context.computeOnClient(client -> client.player.isInWater()), "the client has the player swimming in her hold");
			check(context.computeOnClient(client -> client.gameRenderer.mainCamera().getFluidInCamera()) == FogType.NONE,
				"the camera sees water in her hold");

			// Ashore again, her hold is bailed and the sea beside her is not.
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				for (var rider : ship.getPassengers()) rider.stopRiding();
			});
			context.waitTicks(20);
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				check(ship.getShipState() == MultiBlockShipEntity.ShipState.DOCKED, "she did not dock: " + ship.getShipState());
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -2; dz <= 1; dz++) {
						for (int y = deck - 2; y <= deck - 1; y++) {
							BlockPos cell = new BlockPos(x + dx, y, z + dz);
							check(s.overworld().getFluidState(cell).isEmpty(), "her hold docked with water in it at " + cell);
						}
					}
				}
				check(!s.overworld().getFluidState(new BlockPos(x + 4, deck - 1, z)).isEmpty(), "the sea beside her was drained");
			});
		}
	}

	private static MultiBlockShipEntity ship(net.minecraft.server.MinecraftServer s, BlockPos origin) {
		var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(32));
		check(!ships.isEmpty(), "no ship: the scene never christened");
		return ships.get(0);
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
