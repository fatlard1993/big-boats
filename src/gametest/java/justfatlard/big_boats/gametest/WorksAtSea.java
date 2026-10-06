package justfatlard.big_boats.gametest;

import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipBlock;
import justfatlard.big_boats.ship.sea.Hold;
import justfatlard.big_boats.util.RelativeBlockPos;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/**
 * A ship at sea is a working ship. Her door opens to a click, her chest gives up what is in it and
 * keeps what is put in it through docking, her hopper feeds her furnace and the furnace smelts,
 * her enchanting table counts the bookshelves around it, and her crafting table, anvil and note block
 * do what they do ashore - each by the game's own handling of the block, in her stretch of the world.
 */
public final class WorksAtSea implements FabricClientGameTest {

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
			fill(server, x - 30, deck - 4, z - 30, x + 30, deck - 1, z + 30, "minecraft:water");
			fill(server, x - 30, deck, z - 30, x + 30, deck + 12, z + 30, "minecraft:air");
			fill(server, x - 3, deck, z - 4, x + 3, deck, z + 3, "minecraft:oak_planks");
			fill(server, x - 1, top, z - 4, x + 1, top + 1, z - 4, "minecraft:spruce_planks");
			server.runCommand("setblock %d %d %d big-boats-justfatlard:helm".formatted(x, deck + 3, z - 4));

			// An enchanting table in a ring of bookshelves, the air between them clear.
			server.runCommand("setblock %d %d %d minecraft:enchanting_table".formatted(x, top, z));
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
						server.runCommand("setblock %d %d %d minecraft:bookshelf".formatted(x + dx, top, z + dz));
					}
				}
			}
			// Along the starboard rail: a chest of diamonds, a hopper of raw iron over a furnace of coal, a note block.
			server.runCommand("setblock %d %d %d minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}".formatted(x + 3, top, z - 3));
			server.runCommand("setblock %d %d %d minecraft:furnace[facing=west]{Items:[{Slot:1b,id:\"minecraft:coal\",count:2}]}".formatted(x + 3, top, z));
			server.runCommand("setblock %d %d %d minecraft:hopper[facing=down]{Items:[{Slot:0b,id:\"minecraft:raw_iron\",count:2}]}".formatted(x + 3, top + 1, z));
			server.runCommand("setblock %d %d %d minecraft:note_block".formatted(x + 3, top, z + 2));
			server.runCommand("setblock %d %d %d minecraft:bell[attachment=floor,facing=north]".formatted(x + 3, top, z + 3));
			server.runCommand("setblock %d %d %d minecraft:barrel[facing=up]".formatted(x - 3, top, z + 3));
			// Along the port rail: a crafting table, an anvil, and a door.
			server.runCommand("setblock %d %d %d minecraft:crafting_table".formatted(x - 3, top, z - 3));
			server.runCommand("setblock %d %d %d minecraft:anvil".formatted(x - 3, top, z));
			server.runCommand("setblock %d %d %d minecraft:oak_door[half=lower,facing=south]".formatted(x - 3, top, z + 2));
			server.runCommand("setblock %d %d %d minecraft:oak_door[half=upper,facing=south]".formatted(x - 3, top + 1, z + 2));

			server.runCommand("summon big-boats-justfatlard:christening_bottle %d %d %d {Motion:[0.0,-0.4,0.0]}".formatted(x + 1, deck + 10, z - 1));
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
				check(ship.getHold() != null, "a ship at sea has her blocks' stretch of the world");
			});
			context.waitTicks(10);
			server.runCommand("tp @a %.1f %d %.1f".formatted(x + 1.5, top, z - 2.5));
			context.waitTicks(5);

			// The door, by a real click: the player's client finds it on the drawn ship and asks.
			// Facing north, looking down a little at the door's middle. A "facing" target turns the
			// player from their feet, which looked clean over the door's top.
			server.runCommand("tp @a %.1f %d %.1f 180 30".formatted(x - 2.5, top, z + 3.6));
			context.waitTicks(10);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(10);
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				check(state(ship, Blocks.OAK_DOOR, true).getValue(BlockStateProperties.OPEN), "the door clicked at sea is open");
				check(state(ship, Blocks.OAK_DOOR, false).getValue(BlockStateProperties.OPEN), "and so is its other half");
			});
			server.runCommand("tp @a %.1f %d %.1f".formatted(x + 1.5, top, z - 2.5));
			context.waitTicks(5);

			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				Hold hold = ship.getHold();
				ServerPlayer player = connection.getServerPlayer();

				hold.use(player, at(ship, Blocks.NOTE_BLOCK));
				check(state(ship, Blocks.NOTE_BLOCK, null).getValue(BlockStateProperties.NOTE) == 1, "the note block steps up a note");

				hold.use(player, at(ship, Blocks.BELL));
				check(s.overworld().getBlockEntity(hold.virtual(at(ship, Blocks.BELL))) instanceof net.minecraft.world.level.block.entity.BellBlockEntity bell
					&& bell.shaking, "the bell rings at sea");

				hold.use(player, at(ship, Blocks.CHEST));
				var chestSlots = chestSlots(player);
				check(!chestSlots.isEmpty(), "the chest opens at sea, got " + player.containerMenu);
				for (var slot : chestSlots) {
					player.getInventory().add(slot.getItem().copy());
					slot.set(ItemStack.EMPTY);
				}
				player.closeContainer();
				check(player.getInventory().countItem(Items.DIAMOND) == 3, "and gives up its diamonds");

				hold.use(player, at(ship, Blocks.CRAFTING_TABLE));
				check(player.containerMenu instanceof CraftingMenu, "the crafting table opens at sea, got " + player.containerMenu);
				player.closeContainer();
				hold.use(player, at(ship, Blocks.ANVIL));
				check(player.containerMenu instanceof AnvilMenu, "the anvil opens at sea, got " + player.containerMenu);
				player.closeContainer();

				hold.use(player, at(ship, Blocks.ENCHANTING_TABLE));
				check(player.containerMenu instanceof EnchantmentMenu, "the enchanting table opens at sea, got " + player.containerMenu);
				EnchantmentMenu enchanting = (EnchantmentMenu) player.containerMenu;
				enchanting.getSlot(0).set(new ItemStack(Items.IRON_SWORD));
				enchanting.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
				enchanting.slotsChanged(enchanting.getSlot(0).container);
				check(enchanting.costs[2] >= 25, "the table counts the ship's bookshelves, top cost " + enchanting.costs[2]);
				player.closeContainer();
			});

			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				ship.getHold().use(connection.getServerPlayer(), at(ship, Blocks.BARREL));
			});
			// A plain chest screen, or Chest Utils' own once the client has it up, however long that takes.
			for (int waited = 0; waited < 40 && !server.computeOnServer(s ->
					connection.getServerPlayer().containerMenu != connection.getServerPlayer().inventoryMenu); waited++) {
				context.waitTick();
			}
			context.waitTicks(2);
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				ServerPlayer player = connection.getServerPlayer();
				check(player.containerMenu != player.inventoryMenu, "the barrel opens at sea");
				// Only the game's own chest screen raises a lid; Chest Utils' leaves it shut, ashore as at sea.
				if (player.containerMenu instanceof ChestMenu) {
					check(state(ship, Blocks.BARREL, null).getValue(BlockStateProperties.OPEN), "with its lid up while it is open");
				}
				player.closeContainer();
			});

			// Ten seconds a piece at a furnace: two raw iron, fed in by the hopper above it.
			context.waitTicks(260);
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				BlockEntity furnace = s.overworld().getBlockEntity(ship.getHold().virtual(at(ship, Blocks.FURNACE)));
				check(furnace instanceof AbstractFurnaceBlockEntity, "the furnace is there at sea");
				ItemStack made = ((Container) furnace).getItem(2);
				check(made.is(Items.IRON_INGOT) && made.getCount() >= 1, "the furnace smelted what the hopper fed it, output " + made);
				// And she has it written down already, not only when she docks: a save at sea, then a
				// crash, would otherwise bring her back with the iron still raw and still in the hopper.
				String furnaceSaved = saved(ship, Blocks.FURNACE), hopperSaved = saved(ship, Blocks.HOPPER);
				check(furnaceSaved.contains("iron_ingot"), "the ship's own record of the furnace has the iron it made: " + furnaceSaved);
				check(!hopperSaved.contains("raw_iron"), "and of the hopper, without the iron it fed on: " + hopperSaved);
				// The stretch is far out east: nothing done there may load or make a chunk of it.
				BlockPos stretch = ship.getHold().virtual(at(ship, Blocks.FURNACE));
				int loaded = 0;
				for (int cx = -4; cx <= 4; cx++) {
					for (int cz = -4; cz <= 4; cz++) {
						if (s.overworld().getChunkSource().hasChunk((stretch.getX() >> 4) + cx, (stretch.getZ() >> 4) + cz)) loaded++;
					}
				}
				check(loaded == 0, loaded + " chunks loaded out in the ship's stretch");
			});

			// Something left in the chest at sea is in it when she docks, and the screen is shut.
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				ServerPlayer player = connection.getServerPlayer();
				ship.getHold().use(player, at(ship, Blocks.CHEST));
				var chestSlots = chestSlots(player);
				check(!chestSlots.isEmpty(), "the chest opens again at sea");
				chestSlots.get(0).set(new ItemStack(Items.GOLD_INGOT, 5));
				ship.getPassengers().forEach(net.minecraft.world.entity.Entity::stopRiding);
			});
			context.waitTicks(20);
			server.runOnServer(s -> {
				MultiBlockShipEntity ship = ship(s, origin);
				ServerPlayer player = connection.getServerPlayer();
				check(ship.isDocked(), "she docked when her last rider stepped off");
				check(player.containerMenu == player.inventoryMenu, "the chest screen shut as she docked");
				BlockPos chestAt = null;
				for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -2, -12), origin.offset(12, 4, 12))) {
					if (s.overworld().getBlockState(pos).getBlock() instanceof ChestBlock) chestAt = pos.immutable();
				}
				check(chestAt != null, "the chest is in the world again");
				ChestBlockEntity docked = (ChestBlockEntity) s.overworld().getBlockEntity(chestAt);
				check(docked.countItem(Items.GOLD_INGOT) == 5, "with the gold put in it at sea");
				check(docked.countItem(Items.DIAMOND) == 0, "and without the diamonds taken out at sea");
			});
		}
	}

	/** The open screen's slots that are the chest's own, whichever screen shows it. */
	private static java.util.List<net.minecraft.world.inventory.Slot> chestSlots(ServerPlayer player) {
		if (player.containerMenu == player.inventoryMenu) return java.util.List.of();
		return player.containerMenu.slots.stream()
			.filter(slot -> slot.container instanceof ChestBlockEntity || slot.container instanceof net.minecraft.world.CompoundContainer)
			.toList();
	}

	private static MultiBlockShipEntity ship(MinecraftServer s, BlockPos origin) {
		var ships = s.overworld().getEntitiesOfClass(MultiBlockShipEntity.class, new AABB(origin).inflate(32));
		check(!ships.isEmpty(), "no ship: the scene never christened");
		return ships.get(0);
	}

	/** What the ship holds written down for the one block entity of this kind aboard. */
	private static String saved(MultiBlockShipEntity ship, net.minecraft.world.level.block.Block kind) {
		for (ShipBlock block : ship.getBlocks()) {
			if (block.blockState().is(kind)) return block.blockEntityData().map(Object::toString).orElse("");
		}
		throw new AssertionError("no " + kind + " aboard");
	}

	/** Where the one block of this kind sits on her. For a door, the lower half. */
	private static RelativeBlockPos at(MultiBlockShipEntity ship, Block block) {
		return find(ship, block, true).relativePos();
	}

	/** @param lower for a door, which half; null for anything else */
	private static BlockState state(MultiBlockShipEntity ship, Block block, Boolean lower) {
		return find(ship, block, lower).blockState();
	}

	private static ShipBlock find(MultiBlockShipEntity ship, Block block, Boolean lower) {
		for (ShipBlock candidate : ship.getBlocks()) {
			BlockState state = candidate.blockState();
			if (!state.is(block)) continue;
			if (lower != null && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
					&& (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
						== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) != lower) continue;
			return candidate;
		}
		throw new AssertionError("no " + block + " aboard");
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
