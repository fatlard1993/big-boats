package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * A ladder on a ship climbs at sea as it does at the quay. Under way the ladder is no block in the
 * world, only one drawn on the ship, so the game's own climbing check finds air: both the player's
 * client, which does the climbing, and the server, which has to agree or call it a fall, must ask
 * the ship instead.
 */
public final class LaddersAtSea implements FabricClientGameTest {

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
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 2, deck + 1, z - 4, x + 2, deck + 2, z - 4, "minecraft:spruce_planks");
			// A mast with a ladder up its south face.
			fill(server, x, deck + 1, z, x, deck + 5, z, "minecraft:spruce_log");
			fill(server, x, deck + 1, z + 1, x, deck + 5, z + 1, "minecraft:ladder[facing=south]");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x + 1, deck + 10, z - 2));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(32));
				check(!ships.isEmpty(), "no ship: the scene never christened");
				MultiBlockShipEntity ship = ships.get(0);
				check(ship.tryMount(connection.getServerPlayer()), "the ship takes its pilot");
				ship.undock();
				// A ship docks when its last rider gets off; a cow in a seat keeps her under way
				// while the player walks the deck, as a pilot at the wheel would.
				var cow = net.minecraft.world.entity.EntityTypes.COW.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				cow.snapTo(ship.getX(), ship.getY(), ship.getZ(), 0, 0);
				s.overworld().addFreshEntity(cow);
				check(cow.startRiding(ship, true, false), "a second rider to keep her sailing");
				connection.getServerPlayer().stopRiding();
			});
			context.waitTicks(20);

			BlockPos rung = new BlockPos(x, deck + 2, z + 1);
			server.runOnServer(s -> {
				var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(32));
				check(s.overworld().getBlockState(rung).isAir(), "the ladder left the world with the ship: the scene is not at sea;"
					+ " rung " + s.overworld().getBlockState(rung) + ", deck " + s.overworld().getBlockState(new BlockPos(x, deck, z))
					+ ", ships " + ships.size() + (ships.isEmpty() ? "" : ", blocks " + ships.get(0).getBlocks().size()
					+ ", state " + ships.get(0).getShipState()));
			});
			server.runCommand("tp @a %.2f %d %.2f".formatted(x + 0.5, deck + 2, z + 1.6));
			context.waitTicks(5);

			server.runOnServer(s -> check(connection.getServerPlayer().onClimbable(),
				"the server does not see the player on the ship's ladder"));
			boolean clientClimbs = context.computeOnClient(client -> client.player != null && client.player.onClimbable());
			check(clientClimbs, "the player's own client does not climb the ship's ladder");
		}
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
