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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The pilot goes where the ship goes, on their own screen as well as the server's.
 *
 * <p>Nothing else in the suite sails a ship with somebody holding a key down: the showcase mounts
 * a pilot for the photograph and never presses anything, so a ship that leaves without its pilot
 * would pose for that picture perfectly. This holds W for five seconds and then asks the one
 * question that picture cannot: where does the CLIENT think the player is.
 *
 * <p>The numbers at the end tell different stories and only one of them is a pass or a fail. The
 * ship not moving at all is a broken scene, not a carried pilot. The server player moving with it
 * and the client player left at the quay is the pilot watching their own ship sail away from the
 * outside, which is the fault this guards. The last one - where the pilot is drawn against the
 * deck drawn under them - is reported and not judged: half a block of it is known and unexplained,
 * and a limit on a number nobody has tuned would only pin today's value in place.
 */
public final class PilotFollowsShip implements FabricClientGameTest {

	/** Long enough to out-sail any interpolation: at the harbour cap this is some nineteen blocks. */
	private static final int SAILING_TICKS = 100;

	/** A ship that has not gone this far has not sailed, and the run proves nothing. */
	private static final double SAILED = 2.0;

	/**
	 * How far behind the server a client may be, measured in ticks of the ship's own travel.
	 *
	 * <p>In ticks and not in blocks, because what a client lags by is a number of ticks and the
	 * blocks that makes depends entirely on how fast the ship is going: the same three ticks is
	 * half a block leaving harbour and over a block on open water. A limit in blocks would pass
	 * here at the harbour cap and fail the same healthy ship out at sea.
	 *
	 * <p>Three ticks is what vanilla smooths an entity's update over, and the half is for the tick
	 * the measurement itself falls in. What the drawn deck smooths over is its own question and a
	 * larger number than this; see the offset reported beside it.
	 */
	private static final double ALLOWED_TRAIL_TICKS = 3.5;

	/** Under this, drift is measurement noise and its tick count means nothing. */
	private static final double SETTLED = 0.25;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();

			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("gamerule doWeatherCycle false");
			server.runCommand("weather clear");
			server.runCommand("time set 1000");
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX();
			int y = origin.getY();
			int z = origin.getZ();
			int deck = y;

			// The showcase's hull, on the showcase's pond: a shape already known to float and to
			// christen, so a failure here is about the pilot and not about the boat.
			fill(server, x - 40, y - 4, z - 40, x + 40, y - 1, z + 40, "minecraft:water");
			fill(server, x - 40, y, z - 40, x + 40, y + 20, z + 40, "minecraft:air");
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 1, deck, z + 4, x + 1, deck, z + 4, "minecraft:oak_planks");
			fill(server, x - 2, deck + 1, z - 4, x - 2, deck + 1, z + 3, "minecraft:spruce_planks");
			fill(server, x + 2, deck + 1, z - 4, x + 2, deck + 1, z + 3, "minecraft:spruce_planks");
			fill(server, x - 1, deck + 1, z + 4, x + 1, deck + 1, z + 4, "minecraft:spruce_planks");
			fill(server, x - 2, deck + 1, z - 4, x + 2, deck + 1, z - 4, "minecraft:spruce_planks");
			fill(server, x - 2, deck + 2, z - 4, x + 2, deck + 2, z - 4, "minecraft:spruce_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}"
				.formatted(x, deck + 10, z));
			context.waitTicks(60);

			server.runCommand("gamemode survival @a");
			boolean mounted = server.computeOnServer(s -> {
				var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class,
					new AABB(new BlockPos(x, y, z)).inflate(24));
				if (ships.isEmpty()) return false;
				MultiBlockShipEntity ship = ships.get(0);
				if (!ship.tryMount(connection.getServerPlayer())) return false;
				ship.undock();
				return true;
			});
			if (!mounted) throw new AssertionError("no ship to pilot: the scene never christened");

			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			connection.waitForClientboundPackets();
			context.waitTicks(5);

			Vec3 shipBefore = shipPos(server, connection);
			Vec3 serverBefore = server.computeOnServer(s -> connection.getServerPlayer().position());
			Vec3 clientBefore = context.computeOnClient(client ->
				client.player == null ? null : client.player.position());

			// Where the seat sits on the ship, taken while the ship is still: standing, there is
			// no lead in the anchor and nothing for a client to be catching up to, so this is the
			// ship's own geometry and the one thing here that a moving ship cannot change. Read
			// after sailing it would be whatever the anchor had been sent ahead by, and the
			// measurement would compare the lead against itself and call it zero.
			Vec3 seatGeometry = server.computeOnServer(s -> {
				Entity vehicle = connection.getServerPlayer().getVehicle();
				if (!(vehicle instanceof MultiBlockShipEntity ship)) return null;
				return connection.getServerPlayer().position().subtract(ship.pose().renderOrigin());
			});

			// Underway, by the one route a player has: a key held down.
			context.getInput().holdKey(options -> options.keyUp);
			context.waitTicks(SAILING_TICKS);

			Vec3 shipAfter = shipPos(server, connection);
			Vec3 serverAfter = server.computeOnServer(s -> connection.getServerPlayer().position());
			Vec3 clientAfter = context.computeOnClient(client ->
				client.player == null ? null : client.player.position());
			Vec3 clientShip = context.computeOnClient(client -> {
				Entity vehicle = client.player.getVehicle();
				return vehicle == null ? null : vehicle.position();
			});

			// What the pilot can actually see: where they stand against the deck drawn under them.
			// Both sides of that picture are smoothed, so being behind the server is only a fault
			// if the deck is not behind it by the same amount.
			//
			// Read in ONE call, because this is the only number here small enough to be drowned by
			// the reads themselves: every hop to the other thread lands on whatever tick it lands
			// on, and at a fifth of a block a tick, three ticks of skew is the whole of what is
			// being measured. Asked together it is a position against a pose at one instant, and
			// the skew is gone from the difference rather than inside it.
			Vec3 seatDrawn = context.computeOnClient(client -> {
				Vec3 drawn = drawnDeckOrigin();
				if (drawn == null || client.player == null) return null;
				return client.player.position().subtract(drawn);
			});
			double deckOffset = (seatGeometry == null || seatDrawn == null)
				? Double.NaN : seatDrawn.distanceTo(seatGeometry);

			context.takeScreenshot(TestScreenshotOptions.of("pilot-underway")
				.withSize(1920, 1080).disableCounterPrefix());

			// And again from abeam. Astern, the one thing worth looking at - whether the pilot
			// stands on the wheel or half a block off it - lies straight down the camera's own
			// axis, where no picture can show it. The pilot's look swings the camera and does not
			// touch the helm (A and D steer; see onPassengerTurned), so this turns the hidden axis
			// across the frame without turning the ship.
			context.getInput().lookAt(90f, 0f);
			context.waitTicks(10);
			context.takeScreenshot(TestScreenshotOptions.of("pilot-abeam")
				.withSize(1920, 1080).disableCounterPrefix());
			context.getInput().releaseKey(options -> options.keyUp);

			double sailed = shipAfter.distanceTo(shipBefore);
			double serverCarried = serverAfter.distanceTo(serverBefore);
			double clientCarried = clientAfter.distanceTo(clientBefore);
			double drift = clientAfter.distanceTo(serverAfter);
			double perTick = sailed / SAILING_TICKS;
			double trailTicks = drift / perTick;

			String report = ("""
				ship sailed %.3f (%s -> %s), %.3f a tick
				server player carried %.3f (%s -> %s)
				client player carried %.3f (%s -> %s)
				client vehicle at %s
				client-to-server drift %.3f, which is %.2f ticks of travel
				pilot against the deck they are drawn on %.3f"""
				).formatted(sailed, fmt(shipBefore), fmt(shipAfter), perTick,
					serverCarried, fmt(serverBefore), fmt(serverAfter),
					clientCarried, fmt(clientBefore), fmt(clientAfter),
					clientShip == null ? "NOT RIDING" : fmt(clientShip),
					drift, trailTicks, deckOffset);
			System.out.println("[pilot-follows-ship]\n" + report);

			if (clientAfter == null || serverAfter == null) {
				throw new AssertionError("could not read a position to compare:\n" + report);
			}
			if (sailed < SAILED) {
				throw new AssertionError("the ship never sailed, so nothing was tested:\n" + report);
			}
			// The deck offset is reported rather than judged - but a reading that could not be
			// taken must not pass as one. drawnDeckOrigin reaches into Pandorical's client by
			// name; when that name moves, the number silently becomes NaN, prints, and goes
			// green. Green by absence is the thing this whole file exists to catch.
			if (Double.isNaN(deckOffset)) {
				throw new AssertionError("could not measure the pilot against the drawn deck "
					+ "(Pandorical's client structures could not be read):\n" + report);
			}
			if (drift > SETTLED && trailTicks > ALLOWED_TRAIL_TICKS) {
				throw new AssertionError("the pilot's client was left behind:\n" + report);
			}
		}
	}

	/**
	 * Where the client is drawing the ship's deck, out of Pandorical's own client structures.
	 *
	 * <p>Reflected rather than imported: this is a client-side internal of another mod, reached
	 * for one measurement, and a test that cannot compile is a worse outcome than one that says
	 * it could not look. Null means it could not look.
	 */
	private static Vec3 drawnDeckOrigin() {
		try {
			Class<?> manager = Class.forName("justfatlard.pandorical.client.structure.StructureManager");
			Object active = manager.getMethod("getActive").invoke(null);
			for (Object structure : (Iterable<?>) active) {
				Object pose = structure.getClass().getMethod("thisTick").invoke(structure);
				return new Vec3(
					(double) pose.getClass().getMethod("x").invoke(pose),
					(double) pose.getClass().getMethod("y").invoke(pose),
					(double) pose.getClass().getMethod("z").invoke(pose));
			}
			return null;
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}

	private Vec3 shipPos(TestServerContext server, TestServerConnection connection) {
		return server.computeOnServer(s -> {
			Entity vehicle = connection.getServerPlayer().getVehicle();
			return vehicle == null ? Vec3.ZERO : vehicle.position();
		});
	}

	private static String fmt(Vec3 v) {
		return "%.2f,%.2f,%.2f".formatted(v.x, v.y, v.z);
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
}
