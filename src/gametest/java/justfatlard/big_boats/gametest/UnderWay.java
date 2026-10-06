package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;


/**
 * A ship under way, not standing still: her ladder climbs and her door opens to a click while she
 * sails. A moving ship is drawn a little behind where the server has her, so a server that checked
 * a click or a climb against where she is this tick alone would miss the block the player meant.
 */
public final class UnderWay implements FabricClientGameTest {

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
			int top = deck + 1;
			// Open water every way, since which way she sails is up to her helm.
			fill(server, x - 70, deck - 4, z - 70, x + 70, deck - 1, z + 70, "minecraft:water");
			fill(server, x - 70, deck, z - 70, x + 70, deck + 12, z + 70, "minecraft:air");
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 2, top, z - 4, x + 2, top + 1, z - 4, "minecraft:spruce_planks");
			fill(server, x, top, z, x, deck + 5, z, "minecraft:spruce_log");
			fill(server, x, top, z + 1, x, deck + 5, z + 1, "minecraft:ladder[facing=south]");
			server.runCommand("setblock %d %d %d minecraft:oak_door[half=lower,facing=south]".formatted(x - 2, top, z + 2));
			server.runCommand("setblock %d %d %d minecraft:oak_door[half=upper,facing=south]".formatted(x - 2, top + 1, z + 2));
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x + 1, deck + 10, z - 2));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				check(ship.tryMount(connection.getServerPlayer()), "the ship takes its pilot");
				ship.undock();
				// A cow in a seat keeps her at sea once the player leaves the wheel to walk the deck.
				var cow = net.minecraft.world.entity.EntityTypes.COW.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				cow.snapTo(ship.getX(), ship.getY(), ship.getZ(), 0, 0);
				s.overworld().addFreshEntity(cow);
				check(cow.startRiding(ship, true, false), "a second rider to keep her sailing");
				connection.getServerPlayer().stopRiding();
			});
			context.waitTicks(10);
			// At the foot of the mast ladder, inside its block, with the door two along and a step forward.
			server.runCommand("tp @a %.2f %d %.2f".formatted(x + 0.5, top, z + 1.6));
			context.waitTicks(10);

			Vec3 start = server.computeOnServer(s -> ship(s, origin).position());
			// Full ahead, as a pilot holding W would give her. Nobody can hold the wheel and walk the
			// deck at once, and a made-up player cannot ride, so her engine is driven directly.
			for (int tick = 0; tick < 60; tick++) {
				server.runOnServer(s -> thrust(ship(s, origin)));
				context.waitTick();
			}
			server.runOnServer(s -> {
				double run = ship(s, origin).position().distanceTo(start);
				check(run > 6, "she is under way: she sailed only " + run + " blocks");
			});

			server.runOnServer(s -> check(connection.getServerPlayer().onClimbable(),
				"the server does not see the player on the ladder of a ship under way"));
			check(context.computeOnClient(client -> client.player != null && client.player.onClimbable()),
				"the player's own client does not climb the ladder of a ship under way");

			// Aimed at the door as this client draws it, the way a player aims.
			RelDoor door = server.computeOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				for (ShipBlock block : ship.getBlocks()) {
					if (block.blockState().is(Blocks.OAK_DOOR)
							&& block.blockState().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF).name().equals("LOWER")) {
						return new RelDoor(block.relativePos().x(), block.relativePos().y(), block.relativePos().z());
					}
				}
				throw new AssertionError("no door aboard");
			});
			context.runOnClient(client -> {
				double[] pose = drawnPose();
				// A closed door facing south is a thin board along its block's north side.
				double lx = door.x() + 0.5, ly = door.y() + 0.5, lz = door.z() + 0.1;
				double yaw = Math.toRadians(pose[3]);
				Vec3 target = new Vec3(pose[0] + lx * Math.cos(yaw) - lz * Math.sin(yaw), pose[1] + ly,
					pose[2] + lx * Math.sin(yaw) + lz * Math.cos(yaw));
				Vec3 eye = client.player.getEyePosition();
				Vec3 d = target.subtract(eye);
				client.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
				client.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
			});
			context.waitTick();
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(3);
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				boolean open = false;
				for (ShipBlock block : ship.getBlocks()) {
					if (block.blockState().is(Blocks.OAK_DOOR)) open |= block.blockState().getValue(BlockStateProperties.OPEN);
				}
				check(open, "the door clicked on a ship under way is open");
			});
		}
	}

	private record RelDoor(int x, int y, int z) {}

	/** One tick of full ahead, the way her pilot's W gives it. */
	private static void thrust(MultiBlockShipEntity ship) {
		try {
			var physics = field(ship, "physics");
			var facing = (net.minecraft.core.Direction) field(ship, "helmFacing");
			float yaw = (float) field(ship, "yawRadians");
			physics.getClass().getMethod("applyAcceleration", float.class, net.minecraft.core.Direction.class, float.class)
				.invoke(physics, 1.0F, facing, yaw);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not drive the ship", e);
		}
	}

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		var field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	/**
	 * Where this client draws the one structure in the scene this tick: x, y, z and yaw. Read by name,
	 * since Pandorical's client classes are there when the game runs but not when this compiles.
	 */
	private static double[] drawnPose() {
		try {
			Class<?> manager = Class.forName("justfatlard.pandorical.client.structure.StructureManager");
			Object structure = ((java.util.Map<?, ?>) manager.getMethod("byId").invoke(null)).values().iterator().next();
			Object pose = structure.getClass().getMethod("thisTick").invoke(structure);
			Class<?> type = pose.getClass();
			return new double[] {
				(double) type.getMethod("x").invoke(pose), (double) type.getMethod("y").invoke(pose),
				(double) type.getMethod("z").invoke(pose), (float) type.getMethod("yaw").invoke(pose)};
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not read where the client draws the ship", e);
		}
	}

	private static MultiBlockShipEntity ship(MinecraftServer s, BlockPos origin) {
		var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(80));
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
