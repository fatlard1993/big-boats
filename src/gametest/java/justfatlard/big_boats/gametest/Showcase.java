package justfatlard.big_boats.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The pictures for the readme and the mod page: a ship built out of blocks, christened, and under
 * way on open water.
 *
 * <p>The scene is written with commands, the way a builder would build it, and the ship is launched
 * by throwing a christening bottle at it, the way a player launches one. Run it under xvfb-run;
 * the frames land in build/run/clientGameTest/screenshots.
 */
public final class Showcase implements FabricClientGameTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			context.getInput().pressKey(options -> options.keyToggleGui);
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("gamerule doWeatherCycle false");
			server.runCommand("weather clear");
			server.runCommand("time set 1000");
			// Spectator, or the camera falls into the sea between the teleport and the shutter.
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX();
			int y = origin.getY();
			int z = origin.getZ();

			// Open water to sail on, with the surface at the ground the world came with.
			fill(server, x - 40, y - 4, z - 40, x + 40, y - 1, z + 40, "minecraft:water");
			fill(server, x - 40, y, z - 40, x + 40, y + 20, z + 40, "minecraft:air");

			// The hull, a block clear of the waterline: a deck five wide and nine long, gunwales a
			// block high, a taper at the bow, and a raised stern to stand the wheel on. Kept under
			// a hundred blocks, which is all a plain helm is rated for - an eleven-long version of
			// this came to 118 and the bottle refused it.
			int deck = y;
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 1, deck, z + 4, x + 1, deck, z + 4, "minecraft:oak_planks");
			fill(server, x - 2, deck + 1, z - 4, x - 2, deck + 1, z + 3, "minecraft:spruce_planks");
			fill(server, x + 2, deck + 1, z - 4, x + 2, deck + 1, z + 3, "minecraft:spruce_planks");
			fill(server, x - 1, deck + 1, z + 4, x + 1, deck + 1, z + 4, "minecraft:spruce_planks");
			fill(server, x - 2, deck + 1, z - 4, x + 2, deck + 1, z - 4, "minecraft:spruce_planks");
			fill(server, x - 2, deck + 2, z - 4, x + 2, deck + 2, z - 4, "minecraft:spruce_planks");

			// Mast and sail, which is what makes it read as a ship from a distance.
			fill(server, x, deck + 1, z, x, deck + 6, z, "minecraft:oak_log");
			fill(server, x - 1, deck + 4, z, x + 1, deck + 4, z, "minecraft:oak_fence");
			fill(server, x - 1, deck + 5, z, x + 1, deck + 6, z, "minecraft:white_wool");

			// The wheel, at the stern. A helm faces the pilot, and the pilot faces the bow, so
			// one placed facing south drives the ship north - which is why the bow is at +z.
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			// Southeast of it, looking back: yaw is measured from south, so the way to face a thing
			// is -atan2(dx, dz) of the way to it, and guessing it gets you a picture of empty sea.
			look(server, x + 13.0, deck + 4.0, z + 11.0, x, deck + 2.0, z);
			context.waitTicks(40);
			shoot(context, "ship-docked");

			// Christened the way a player does it: a bottle thrown at the hull.
			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}"
				.formatted(x, deck + 10, z));
			context.waitTicks(60);
			shoot(context, "ship");

			// And under way, with somebody at the wheel: mounted the way the helm mounts a player,
			// undocked, and watched over the pilot's shoulder.
			server.runCommand("gamemode survival @a");
			boolean sailing = server.computeOnServer(s -> {
				var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class,
					new AABB(new BlockPos(x, y, z)).inflate(24));
				if (ships.isEmpty()) return false;
				MultiBlockShipEntity ship = ships.get(0);
				ship.tryMount(connection.getServerPlayer());
				ship.undock();
				return true;
			});
			if (!sailing) {
				throw new AssertionError("PROBE no ship: helm=" + server.computeOnServer(s ->
					s.overworld().getBlockState(new BlockPos(x, y + 3, z - 4)) + " bottles="
						+ s.overworld().getEntitiesOfClass(justfatlard.big_boats.ChristeningBottleEntity.class,
							new AABB(new BlockPos(x, y, z)).inflate(40)).size()
						+ " ships=" + s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class,
							new AABB(new BlockPos(x, y, z)).inflate(200)).size()));
			}
			if (sailing) {
				context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
				context.waitTicks(60);
				shoot(context, "sailing");
			}
		}
	}

	/**
	 * A /fill, in slabs vanilla will actually accept.
	 *
	 * <p>The command refuses more than 32768 blocks at a time and says so to the source, which for
	 * a gametest is nobody: the scene simply never got built and the test went on against whatever
	 * terrain was there. The air fill here is 137,781 blocks and has never once run - it passed
	 * only because the region above a pond is already air.
	 */
	private void fill(TestServerContext server, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		int area = (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
		int slab = Math.max(1, 32768 / Math.max(1, area));
		int low = Math.min(y1, y2);
		int high = Math.max(y1, y2);
		for (int y = low; y <= high; y += slab) {
			int top = Math.min(high, y + slab - 1);
			server.runCommand("fill %d %d %d %d %d %d %s".formatted(x1, y, z1, x2, top, z2, block));
		}
	}

	/**
	 * Stand the camera at one place and point it at another. The camera's y is the feet, so it
	 * looks from 1.62 above where it stands: aim at a point, not at a guess.
	 */
	private void look(TestServerContext server, double x, double y, double z,
			double atX, double atY, double atZ) {
		double dx = atX - x;
		double dy = atY - (y + 1.62);
		double dz = atZ - z;
		double yaw = -Math.toDegrees(Math.atan2(dx, dz));
		double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		server.runCommand("tp @a %.2f %.2f %.2f %.1f %.1f".formatted(x, y, z, yaw, pitch));
	}

	private void shoot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name)
			.withSize(WIDTH, HEIGHT)
			.disableCounterPrefix());
	}
}
