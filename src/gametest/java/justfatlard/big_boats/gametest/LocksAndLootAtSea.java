package justfatlard.big_boats.gametest;

import com.mojang.authlib.GameProfile;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.ship.sea.Hold;
import justfatlard.big_boats.util.RelativeBlockPos;
import justfatlard.chest_utils.block.ChestLocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Chests aboard keep their rules at sea. A chest Chest Utils has locked opens to nobody else at sea
 * and is still locked, to the same owner, when she docks; a loot chest under Loot Ender opens at sea
 * as each player's own copy, and one a player emptied at sea is still empty for them ashore.
 */
public final class LocksAndLootAtSea implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamerule doDaylightCycle false");
			// Loot Ender locks some loot chests behind a lockpicking game; this is about whose loot
			// it is, not the lock, so the locks are off.
			server.runOnServer(s -> justfatlard.loot_ender.LootEnderConfig.setLockpicking(false));
			server.runCommand("gamemode spectator @a");

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX(), deck = origin.getY(), z = origin.getZ();
			int top = deck + 1;
			fill(server, x - 30, deck - 4, z - 30, x + 30, deck - 1, z + 30, "minecraft:water");
			fill(server, x - 30, deck, z - 30, x + 30, deck + 12, z + 30, "minecraft:air");
			fill(server, x - 2, deck, z - 4, x + 2, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 1, top, z - 4, x + 1, top + 1, z - 4, "minecraft:spruce_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			BlockPos lockedAt = new BlockPos(x + 1, top, z);
			BlockPos lootAt = new BlockPos(x - 1, top, z);
			// Two more, a block apart so none pairs into a double chest: one locked once she is at
			// sea, one locked ashore and unlocked at sea.
			BlockPos lockedAtSea = new BlockPos(x + 1, top, z + 2);
			BlockPos unlockedAtSea = new BlockPos(x - 1, top, z + 2);
			UUID owner = UUID.randomUUID();
			List<BlockPos> stretch = new java.util.concurrent.CopyOnWriteArrayList<>();
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				level.setBlockAndUpdate(lockedAt, Blocks.CHEST.defaultBlockState());
				level.setBlockAndUpdate(lockedAtSea, Blocks.CHEST.defaultBlockState());
				level.setBlockAndUpdate(unlockedAtSea, Blocks.CHEST.defaultBlockState());
				FakePlayer owning = FakePlayer.get(level, new GameProfile(owner, "Owner"));
				ChestLocks.get(level).lock(owning, level.getBlockState(lockedAt), lockedAt);
				ChestLocks.get(level).lock(owning, level.getBlockState(unlockedAtSea), unlockedAtSea);
				level.setBlockAndUpdate(lootAt, Blocks.CHEST.defaultBlockState());
				((ChestBlockEntity) level.getBlockEntity(lootAt)).setLootTable(
					net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON, 7L);
			});

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x, deck + 10, z - 2));
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
			context.waitTicks(10);
			server.runCommand("tp @a %d %d %d".formatted(x, top, z - 2));
			context.waitTicks(5);

			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				MultiBlockShipEntity ship = ship(s, origin);
				Hold hold = ship.getHold();
				check(ChestLocks.get(level).lockAt(lockedAt) == null, "the lock left its old place with the chest");

				ChestLocks locks = ChestLocks.get(level);
				BlockPos locked = hold.virtual(chest(ship, lockedAt, origin));
				FakePlayer stranger = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "Stranger"));
				FakePlayer owning = FakePlayer.get(level, new GameProfile(owner, "Owner"));
				// Asked the way Chest Utils asks when the chest is clicked, of the chest where it stands at sea.
				check(locks.refuses(stranger, level.getBlockState(locked), locked), "a stranger is refused the locked chest at sea");
				check(!locks.refuses(owning, level.getBlockState(locked), locked), "and its owner is not");

				BlockPos lockingNow = hold.virtual(chest(ship, lockedAtSea, origin));
				locks.lock(owning, level.getBlockState(lockingNow), lockingNow);
				BlockPos unlockingNow = hold.virtual(chest(ship, unlockedAtSea, origin));
				check(locks.lockAt(unlockingNow) != null, "the chest locked ashore is locked at sea");
				locks.unlock(level.getBlockState(unlockingNow), unlockingNow);
				stretch.addAll(List.of(locked, lockingNow, unlockingNow));

				ServerPlayer player = world.getConnection().getServerPlayer();
				hold.use(player, chest(ship, lootAt, origin));
			});
			// Loot Ender opens the copy through Chest Utils' screen when it is here, a tick or two on.
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				var copy = lootSlots(player);
				check(!copy.isEmpty(), "the loot chest opens at sea as this player's own copy, got " + player.containerMenu);
				check(copy.stream().anyMatch(slot -> !slot.getItem().isEmpty()), "with loot in it");
				copy.forEach(slot -> slot.set(net.minecraft.world.item.ItemStack.EMPTY));
				player.closeContainer();
				MultiBlockShipEntity ship = ship(s, origin);
				ship.getHold().use(player, chest(ship, lootAt, origin));
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				var again = lootSlots(player);
				check(again.stream().allMatch(slot -> slot.getItem().isEmpty()), "emptied, it stays empty for them at sea");
				player.closeContainer();
				MultiBlockShipEntity ship = ship(s, origin);
				ship.getPassengers().forEach(net.minecraft.world.entity.Entity::stopRiding);
			});
			context.waitTicks(20);

			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				MultiBlockShipEntity ship = ship(s, origin);
				check(ship.isDocked(), "she docked when her last rider stepped off");
				ChestLocks locks = ChestLocks.get(level);
				ChestLocks.Lock lock = locks.lockAt(lockedAt);
				check(lock != null && lock.owner().equals(owner), "the chest is locked to its owner again where she docked");
				ChestLocks.Lock lockedThere = locks.lockAt(lockedAtSea);
				check(lockedThere != null && lockedThere.owner().equals(owner), "a chest locked at sea comes ashore locked");
				check(locks.lockAt(unlockedAtSea) == null, "a chest unlocked at sea comes ashore open");
				for (BlockPos up : stretch) check(locks.lockAt(up) == null, "no lock is left behind at sea, at " + up);

				ServerPlayer player = world.getConnection().getServerPlayer();
				level.getBlockState(lootAt).useWithoutItem(level, player,
					new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(lootAt), net.minecraft.core.Direction.UP, lootAt, false));
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = world.getConnection().getServerPlayer();
				var ashore = lootSlots(player);
				check(!ashore.isEmpty(), "the loot chest opens ashore as their copy, got " + player.containerMenu);
				check(ashore.stream().allMatch(slot -> slot.getItem().isEmpty()), "and what they emptied at sea is still empty for them ashore");
				player.closeContainer();
			});
		}
	}

	private static MultiBlockShipEntity ship(MinecraftServer s, BlockPos origin) {
		var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(32));
		check(!ships.isEmpty(), "no ship: the scene never christened");
		return ships.get(0);
	}

	/** The ship's place for the chest that stood at {@code world} when she sailed. */
	private static RelativeBlockPos chest(MultiBlockShipEntity ship, BlockPos world, BlockPos origin) {
		BlockPos helm = ship.getHelmBlockPos();
		RelativeBlockPos want = new RelativeBlockPos(world.getX() - helm.getX(), world.getY() - helm.getY(), world.getZ() - helm.getZ());
		for (ShipBlock block : ship.getBlocks()) {
			if (block.relativePos().equals(want) && block.blockState().is(Blocks.CHEST)) return want;
		}
		throw new AssertionError("no chest aboard where " + world + " stood (helm " + helm + ")");
	}

	/** The slots of the open screen that are this player's Loot Ender copy. */
	private static java.util.List<net.minecraft.world.inventory.Slot> lootSlots(ServerPlayer player) {
		return player.containerMenu.slots.stream()
			.filter(slot -> slot.container instanceof justfatlard.loot_ender.PlayerLootContainer
				|| slot.container instanceof justfatlard.loot_ender.LootDoubleContainer)
			.toList();
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
