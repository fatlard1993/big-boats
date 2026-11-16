package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.pandorical.api.CellsDrawing;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.gametest.Pictures;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * A block another mod draws as something else is drawn that way at sea: the ship hands each block
 * and what it saved to Pandorical's drawing registry, knowing nothing of what answers. Stood in
 * for here by a barrel named for the purpose, drawn as a little house; a barrel without the name
 * beside it, and the planks, are drawn as themselves.
 */
public final class DrawnAtSea implements FabricClientGameTest {
	private static final String NAME = "Drawn at sea";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runOnServer(s -> PandoricalApi.structures().drawings().register(DrawnAtSea::draw));
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule advance_weather false");
			server.runCommand("weather clear");
			server.runCommand("time set 2000");
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX(), deck = origin.getY(), z = origin.getZ();
			fill(server, x - 30, deck - 4, z - 30, x + 30, deck - 1, z + 30, "minecraft:water");
			fill(server, x - 30, deck, z - 30, x + 30, deck + 12, z + 30, "minecraft:air");
			fill(server, x - 2, deck, z - 3, x + 2, deck, z + 3, "minecraft:oak_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 1, z - 3));
			server.runCommand("setblock %d %d %d minecraft:barrel{CustomName:\"%s\"}".formatted(x - 1, deck + 1, z, NAME));
			server.runCommand("setblock %d %d %d minecraft:barrel".formatted(x + 1, deck + 1, z));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %.1f %d %.1f {Motion:[0.0,-0.4,0.0]}".formatted(x + 0.5, deck + 10, z + 1.5));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				check(ship.tryMount(connection.getServerPlayer()), "the ship takes its pilot");
				ship.undock();
				check(ship.getShipState() == MultiBlockShipEntity.ShipState.SAILING, "she did not sail: " + ship.getShipState());
				// A cow at the wheel keeps her at sea while the player steps off to look at her.
				var cow = net.minecraft.world.entity.EntityTypes.COW.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				cow.snapTo(ship.getX(), ship.getY(), ship.getZ(), 0, 0);
				s.overworld().addFreshEntity(cow);
				check(cow.startRiding(ship, true, false), "a second rider to keep her sailing");
				connection.getServerPlayer().stopRiding();
			});

			String id = server.computeOnServer(s -> "bigboats:" + ship(s, origin).getUUID());
			ShipBlock named = server.computeOnServer(s -> aboard(ship(s, origin), true));
			ShipBlock plain = server.computeOnServer(s -> aboard(ship(s, origin), false));
			ShipBlock plank = server.computeOnServer(s -> ship(s, origin).getBlocks().stream()
				.filter(block -> block.blockState().is(Blocks.OAK_PLANKS)).findFirst().orElseThrow());

			context.waitFor(client -> {
				Object structure = structure(id);
				return structure != null && (boolean) read(structure, "visible") && drawingAt(structure, named) != null;
			});
			int[] drawing = context.computeOnClient(client -> {
				Object drawn = drawingAt(structure(id), named);
				return new int[] {(int) call(drawn, "n"), ((List<?>) call(drawn, "showing")).size()};
			});
			check(drawing[0] == 4 && drawing[1] > 0, "the named barrel is drawn " + drawing[0] + " to a side with " + drawing[1] + " cells showing");
			check(context.computeOnClient(client -> drawingAt(structure(id), plain)) == null,
				"a barrel without the name is drawn as a build");
			check(context.computeOnClient(client -> drawingAt(structure(id), plank)) == null,
				"a plank is drawn as a build");
			check(context.computeOnClient(client -> (int) call(structure(id), "drawingCount")) == 1,
				"more than the one block is drawn as a build");

			if (Pictures.wanted()) {
				server.runCommand("gamemode spectator @a");
				server.runCommand("kill @e[type=minecraft:item]");
				Vec3 at = server.computeOnServer(s -> ship(s, origin).position());
				int distance = context.computeOnClient(client -> client.options.renderDistance().get());
				Pictures.look(server, at.add(4.5, 3.5, 5.5), at.add(-0.5, 0.5, 0));
				Pictures.stage(context, server, Pictures.MORNING);
				context.waitTicks(20);
				Pictures.shoot(context, connection, "drawn-at-sea");
				// The HUD and the far view stay with the client, and the next test's world never
				// finishes loading under them: put both back.
				context.runOnClient(client -> {
					if (client.gui.hud.isHidden()) client.gui.hud.toggle();
					client.options.renderDistance().set(distance);
					client.options.broadcastOptions();
				});
			}
		}
	}

	/** The test's own provider: the named barrel, by what it saved, and nothing else. */
	private static Optional<CellsDrawing> draw(BlockState state, CompoundTag saved) {
		if (!state.is(Blocks.BARREL) || saved == null || !String.valueOf(saved.get("CustomName")).contains(NAME)) return Optional.empty();
		return Optional.of(house());
	}

	/** Four to a side: a stone floor, plank walls, a brick roof. */
	private static CellsDrawing house() {
		int n = 4;
		byte[] cells = new byte[n * n * n];
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
			boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
			byte cell = y == 0 ? 1 : y == n - 1 ? 3 : edge ? (byte) 2 : 0;
			cells[(y * n + z) * n + x] = cell;
		}
		return new CellsDrawing(n, n, List.of(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
			Blocks.BRICKS.defaultBlockState()), cells, 0, 0);
	}

	private static ShipBlock aboard(MultiBlockShipEntity ship, boolean named) {
		return ship.getBlocks().stream()
			.filter(block -> block.blockState().is(Blocks.BARREL))
			.filter(block -> named == block.blockEntityData().map(data -> String.valueOf(data.get("CustomName")).contains(NAME)).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError("no " + (named ? "named" : "plain") + " barrel aboard"));
	}

	/**
	 * The ship as the client has it, out of Pandorical's own client structures, or null if it has
	 * none by that id. Reflected rather than imported, as {@link PilotFollowsShip} does: this build
	 * compiles against Pandorical's main classes only. A test that cannot look fails saying so.
	 */
	private static Object structure(String id) {
		try {
			Object byId = Class.forName("justfatlard.pandorical.client.structure.StructureManager").getMethod("byId").invoke(null);
			return ((java.util.Map<?, ?>) byId).get(id);
		} catch (ReflectiveOperationException | ClassCastException e) {
			throw new AssertionError("could not read Pandorical's client structures", e);
		}
	}

	private static Object drawingAt(Object structure, ShipBlock block) {
		try {
			return structure.getClass().getMethod("drawingAt", int.class, int.class, int.class)
				.invoke(structure, block.relativePos().x(), block.relativePos().y(), block.relativePos().z());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not read a client structure's drawings", e);
		}
	}

	private static Object call(Object target, String method) {
		try {
			return target.getClass().getMethod(method).invoke(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not ask " + target + " for " + method, e);
		}
	}

	private static Object read(Object target, String field) {
		try {
			return target.getClass().getField(field).get(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not read " + field + " of " + target, e);
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
