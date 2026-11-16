package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What stands on a ship at sea and is not a player stays on her deck while she sails: an armour
 * stand and a dropped item come to rest on the boards and are still aboard, at the same height, a
 * few seconds of full ahead later. Nothing holding the deck up is an entity: there are no shulkers.
 */
public final class CrewAtSea implements FabricClientGameTest {

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
			fill(server, x - 70, deck - 4, z - 70, x + 70, deck - 1, z + 70, "minecraft:water");
			fill(server, x - 70, deck, z - 70, x + 70, deck + 12, z + 70, "minecraft:air");
			fill(server, x - 3, deck, z - 4, x + 3, deck, z + 4, "minecraft:oak_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 1, z - 4));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x + 1, deck + 10, z - 2));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				check(ship.tryMount(connection.getServerPlayer()), "the ship takes its pilot");
				ship.undock();
				var cow = EntityTypes.COW.create(s.overworld(), EntitySpawnReason.COMMAND);
				cow.snapTo(ship.getX(), ship.getY(), ship.getZ(), 0, 0);
				s.overworld().addFreshEntity(cow);
				check(cow.startRiding(ship, true, false), "a second rider to keep her sailing");
				connection.getServerPlayer().stopRiding();
			});
			server.runCommand("gamemode spectator @a");
			context.waitTicks(5);

			int stand = server.computeOnServer(s -> {
				ArmorStand entity = new ArmorStand(EntityTypes.ARMOR_STAND, s.overworld());
				entity.setPos(x + 2.5, deck + 2.5, z + 2.5);
				s.overworld().addFreshEntity(entity);
				return entity.getId();
			});
			int item = server.computeOnServer(s -> {
				ItemEntity entity = new ItemEntity(s.overworld(), x - 1.5, deck + 2.5, z + 2.5, new ItemStack(Items.STICK));
				entity.setDeltaMovement(Vec3.ZERO);
				entity.setNeverPickUp();
				entity.setUnlimitedLifetime();
				s.overworld().addFreshEntity(entity);
				return entity.getId();
			});
			context.waitTicks(40);

			double boards = deck + 1;
			Aboard standAt = aboard(server, origin, stand);
			Aboard itemAt = aboard(server, origin, item);
			check(Math.abs(standAt.y - boards) < 0.05, "the armour stand did not come to rest on the deck: y " + standAt.y);
			check(Math.abs(itemAt.y - boards) < 0.05, "the item did not come to rest on the deck: y " + itemAt.y);

			server.runOnServer(s -> {
				var shulkers = s.overworld().getEntitiesOfClass(Shulker.class, new AABB(origin).inflate(40));
				check(shulkers.isEmpty(), shulkers.size() + " shulkers hold up a ship's deck");
			});

			Vec3 start = server.computeOnServer(s -> ship(s, origin).position());
			for (int tick = 0; tick < 60; tick++) {
				server.runOnServer(s -> thrust(ship(s, origin)));
				context.waitTick();
			}
			server.runOnServer(s -> {
				double run = ship(s, origin).position().distanceTo(start);
				check(run > 6, "she is under way: she sailed only " + run + " blocks");
			});

			Aboard standAfter = aboard(server, origin, stand);
			Aboard itemAfter = aboard(server, origin, item);
			check(Math.abs(standAfter.y - boards) < 0.05, "under way, the armour stand left the deck: y " + standAfter.y);
			check(Math.abs(itemAfter.y - boards) < 0.05, "under way, the item left the deck: y " + itemAfter.y);
			check(Math.abs(standAfter.fromShip - standAt.fromShip) < 0.5,
				"under way, the armour stand was not carried: " + standAt.fromShip + " from her then, " + standAfter.fromShip + " now");
			check(Math.abs(itemAfter.fromShip - itemAt.fromShip) < 0.5,
				"under way, the item was not carried: " + itemAt.fromShip + " from her then, " + itemAfter.fromShip + " now");
		}
	}

	/** An entity's height, and how far across from the ship's anchor it stands. */
	private record Aboard(double y, double fromShip) {}

	private static Aboard aboard(TestServerContext server, BlockPos origin, int id) {
		return server.computeOnServer(s -> {
			Entity entity = s.overworld().getEntity(id);
			check(entity != null, "an entity on the deck is gone");
			MultiBlockShipEntity ship = ship(s, origin);
			return new Aboard(entity.getY(), Math.hypot(entity.getX() - ship.getX(), entity.getZ() - ship.getZ()));
		});
	}

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
