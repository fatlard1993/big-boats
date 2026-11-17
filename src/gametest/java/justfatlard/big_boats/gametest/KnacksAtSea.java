package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.knick_knacks.Knack;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A knick-knack set down on deck stays where it was set as she sails, rather than falling as an
 * item the moment its block leaves the world for the sea, and is still there, on its plank, when
 * she docks.
 */
public final class KnacksAtSea implements FabricClientGameTest {
	private static final int SAILING_TICKS = 40;

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
			fill(server, x - 40, deck - 4, z - 40, x + 40, deck - 1, z + 40, "minecraft:water");
			fill(server, x - 40, deck, z - 40, x + 40, deck + 12, z + 40, "minecraft:air");
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 1, top, z - 4, x + 1, top + 1, z - 4, "minecraft:spruce_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x, deck + 10, z - 2));
			context.waitTicks(60);
			server.runCommand("gamemode survival @a");

			// An apple set down on the deck the way a player sets one down: sneaking, empty hand, apple in the other.
			BlockPos plank = new BlockPos(x + 1, deck, z + 2);
			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				player.teleportTo(s.overworld(), x + 1.5, top, z + 0.5, java.util.Set.of(), 0F, 60F, true);
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.APPLE));
				player.setShiftKeyDown(true);
				player.gameMode.useItemOn(player, s.overworld(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.upFromBottomCenterOf(plank, 1.0), Direction.UP, plank, false));
				player.setShiftKeyDown(false);
			});
			context.waitTicks(5);
			check(knacks(server, origin).size() == 1, "no apple was set down on the deck to sail with");

			server.runOnServer(s -> check(ship(s, origin).tryMount(connection.getServerPlayer()), "the ship takes its pilot"));
			context.waitTicks(5);
			server.runOnServer(s -> ship(s, origin).undock());
			context.getInput().holdKey(options -> options.keyUp);
			context.waitTicks(SAILING_TICKS);
			context.getInput().releaseKey(options -> options.keyUp);
			Vec3 moved = server.computeOnServer(s -> ship(s, origin).position());
			check(moved.distanceTo(Vec3.atCenterOf(origin)) > 1.5, "she never got under way: " + moved);
			check(knacks(server, origin).size() == 1, "the apple did not stay aboard as she sailed");
			check(apples(server, origin) == 0, "the apple fell as an item when she sailed");
			Vec3 handleAtSea = server.computeOnServer(s -> knacks(s, origin).getFirst().position());
			check(handleAtSea.distanceTo(moved) < 8, "the apple was left behind as she sailed: it is at " + handleAtSea + ", she at " + moved);

			server.runOnServer(s -> connection.getServerPlayer().stopRiding());
			context.waitTicks(40);
			server.runOnServer(s -> check(ship(s, origin).isDocked(), "she did not dock when her pilot stepped off"));
			context.waitTicks(40);
			check(apples(server, origin) == 0, "the apple fell as an item when she docked");
			List<Interaction> ashore = server.computeOnServer(s -> knacks(s, origin));
			check(ashore.size() == 1, "the apple did not come ashore with her: " + ashore.size() + " knacks");
			boolean onPlank = server.computeOnServer(s -> {
				BlockPos under = BlockPos.containing(ashore.getFirst().position().subtract(0, 0.05, 0));
				return s.overworld().getBlockState(under).is(Blocks.OAK_PLANKS);
			});
			check(onPlank, "the apple is not on a plank of her deck where she docked, at " + ashore.getFirst().position());
			System.out.println("[big-boats-test] knacks at sea passed");
		}
	}

	private static List<Interaction> knacks(TestServerContext server, BlockPos origin) {
		return server.computeOnServer(s -> knacks(s, origin));
	}

	private static List<Interaction> knacks(MinecraftServer s, BlockPos origin) {
		return s.overworld().getEntitiesOfClass(Interaction.class, new AABB(origin).inflate(48), Knack::isKnack);
	}

	private static int apples(TestServerContext server, BlockPos origin) {
		return server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(48),
			item -> item.getItem().is(Items.APPLE)).size());
	}

	private static MultiBlockShipEntity ship(MinecraftServer s, BlockPos origin) {
		var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(48));
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
