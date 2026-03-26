package justfatlard.big_boats;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.item.PolymerItemGroupUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import justfatlard.big_boats.block.HelmBlock;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipConfig;
import justfatlard.big_boats.ship.ShipInteraction;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import justfatlard.big_boats.util.PlayerInputStorage;
import net.minecraft.server.world.ServerWorld;
import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class BigBoats implements ModInitializer {
	public static final String MOD_ID = "big-boats-justfatlard";

	private static RegistryKey<Item> itemKeyOf(String name) {
		return RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, name));
	}

	private static RegistryKey<Block> blockKeyOf(String name) {
		return RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(MOD_ID, name));
	}

	private static RegistryKey<EntityType<?>> entityKeyOf(String name) {
		return RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MOD_ID, name));
	}

	public static final RegistryKey<Item> CHRISTENING_BOTTLE_KEY = itemKeyOf("christening_bottle");
	public static final ChristeningBottleItem CHRISTENING_BOTTLE = new ChristeningBottleItem(
		new Item.Settings()
			.registryKey(CHRISTENING_BOTTLE_KEY)
			.maxCount(16)
	);

	public static final RegistryKey<Block> HELM_BLOCK_KEY = blockKeyOf("helm");
	public static final HelmBlock HELM_BLOCK = new HelmBlock(
		AbstractBlock.Settings.copy(Blocks.OAK_PLANKS)
			.registryKey(HELM_BLOCK_KEY)
			.nonOpaque()
	);

	public static final RegistryKey<Item> HELM_ITEM_KEY = itemKeyOf("helm");
	public static final HelmBlock.HelmBlockItem HELM_ITEM = new HelmBlock.HelmBlockItem(
		HELM_BLOCK,
		new Item.Settings()
			.registryKey(HELM_ITEM_KEY)
	);

	public static final RegistryKey<EntityType<?>> MULTI_BLOCK_SHIP_ENTITY_KEY = entityKeyOf("ship");
	public static final EntityType<MultiBlockShipEntity> MULTI_BLOCK_SHIP_ENTITY_TYPE = EntityType.Builder
		.<MultiBlockShipEntity>create(MultiBlockShipEntity::new, SpawnGroup.MISC)
		.dimensions(3.0F, 1.5F) // Large enough for entity tracker to keep ship loaded when nearby
		.eyeHeight(1.0F)
		.maxTrackingRange(ShipConfig.ENTITY_TRACKING_RANGE)
		.trackingTickInterval(1)
		.build(MULTI_BLOCK_SHIP_ENTITY_KEY);

	public static final RegistryKey<EntityType<?>> CHRISTENING_BOTTLE_ENTITY_KEY = entityKeyOf("christening_bottle");
	public static final EntityType<ChristeningBottleEntity> CHRISTENING_BOTTLE_ENTITY_TYPE = EntityType.Builder
		.<ChristeningBottleEntity>create(ChristeningBottleEntity::new, SpawnGroup.MISC)
		.dimensions(0.25F, 0.25F)
		.maxTrackingRange(4)
		.trackingTickInterval(10)
		.build(CHRISTENING_BOTTLE_ENTITY_KEY);

	@Override
	public void onInitialize() {
		PolymerResourcePackUtils.addModAssets(MOD_ID);
		PolymerResourcePackUtils.markAsRequired();

		Registry.register(Registries.BLOCK, HELM_BLOCK_KEY.getValue(), HELM_BLOCK);
		HELM_BLOCK.registerPolymerModels();

		Registry.register(Registries.ITEM, CHRISTENING_BOTTLE_KEY.getValue(), CHRISTENING_BOTTLE);
		Registry.register(Registries.ITEM, HELM_ITEM_KEY.getValue(), HELM_ITEM);

		Registry.register(Registries.ENTITY_TYPE, MULTI_BLOCK_SHIP_ENTITY_KEY.getValue(), MULTI_BLOCK_SHIP_ENTITY_TYPE);
		PolymerEntityUtils.registerType(MULTI_BLOCK_SHIP_ENTITY_TYPE);

		Registry.register(Registries.ENTITY_TYPE, CHRISTENING_BOTTLE_ENTITY_KEY.getValue(), CHRISTENING_BOTTLE_ENTITY_TYPE);
		PolymerEntityUtils.registerType(CHRISTENING_BOTTLE_ENTITY_TYPE);

		ItemGroup bigBoatsGroup = PolymerItemGroupUtils.builder()
			.displayName(Text.translatable("itemGroup.big-boats-justfatlard.big_boats"))
			.icon(() -> new ItemStack(CHRISTENING_BOTTLE))
			.entries((context, entries) -> {
				entries.add(new ItemStack(CHRISTENING_BOTTLE));
				entries.add(new ItemStack(HELM_ITEM));
			})
			.build();
		PolymerItemGroupUtils.registerPolymerItemGroup(Identifier.of(MOD_ID, "big_boats"), bigBoatsGroup);

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(CHRISTENING_BOTTLE);
		});
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
			entries.add(HELM_ITEM);
		});

		// Register callback to handle helm interaction clicks
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (entity instanceof InteractionEntity && player instanceof ServerPlayerEntity serverPlayer) {
				// Find the ship that owns this interaction entity
				for (MultiBlockShipEntity ship : world.getEntitiesByClass(
						MultiBlockShipEntity.class,
						entity.getBoundingBox().expand(1.0),
						s -> s.isHelmInteraction(entity))) {
					if (ship.tryMount(serverPlayer)) {
						return ActionResult.SUCCESS;
					}
				}
			}

			// Handle block interactions on moving ships (doors, trapdoors, fence gates)
			if (entity instanceof ShulkerEntity && player instanceof ServerPlayerEntity) {
				Entity vehicle = player.getVehicle();
				if (vehicle instanceof MultiBlockShipEntity ship && !ship.isDocked()) {
					if (ship.isCollisionShulker(entity)) {
						int blockIndex = ShipInteraction.findLookedAtBlock(
							player, ship.getBlocks(), ship.pose());
						if (blockIndex >= 0) {
							return ShipInteraction.tryInteractWithBlock(
								ship.getBlocks(), blockIndex, world,
								new Vec3d(entity.getX(), entity.getY(), entity.getZ()),
								ship::updateShipBlock);
						}
					}
				}
			}

			return ActionResult.PASS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				CommandManager.literal("bigboats")
					.requires(source -> source.getPermissions().hasPermission(
						new Permission.Level(PermissionLevel.GAMEMASTERS)))
					.then(CommandManager.literal("cleanup-lights")
						.executes(BigBoats::cleanupLightsCommand)
					)
			);
		});

		// Clean up player input storage on disconnect to prevent memory leak
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PlayerInputStorage.removePlayer(handler.getPlayer().getUuid());
		});

		// Tick-spread listener for cleanup-lights command (registered once, checks flag each tick)
		ServerTickEvents.END_SERVER_TICK.register(BigBoats::tickCleanup);
	}

	// Tick-spread state for cleanup-lights command.
	// Only one cleanup runs at a time. The tick listener is registered once in onInitialize.
	private static Queue<int[]> cleanupQueue = null;
	private static net.minecraft.server.command.ServerCommandSource cleanupSource = null;
	private static ServerWorld cleanupWorld = null;
	private static int cleanupMinY = 0;
	private static int cleanupMaxY = 0;
	private static int cleanupRadius = 0;
	private static int cleanupRemoved = 0;
	private static final int COLUMNS_PER_TICK = 50;

	private static void tickCleanup(net.minecraft.server.MinecraftServer ignoredServer) {
		if (cleanupQueue == null || cleanupQueue.isEmpty()) return;

		int processed = 0;
		while (!cleanupQueue.isEmpty() && processed < COLUMNS_PER_TICK) {
			int[] col = cleanupQueue.poll();
			for (int y = cleanupMinY; y <= cleanupMaxY; y++) {
				var checkPos = new BlockPos(col[0], y, col[1]);
				if (cleanupWorld.getBlockState(checkPos).isOf(Blocks.LIGHT)) {
					cleanupWorld.setBlockState(checkPos, Blocks.AIR.getDefaultState());
					cleanupRemoved++;
				}
			}
			processed++;
		}

		if (cleanupQueue.isEmpty()) {
			final int count = cleanupRemoved;
			final int r = cleanupRadius;
			cleanupSource.sendFeedback(
				() -> Text.translatable("big-boats.command.cleanup_lights", count, r), true);
			cleanupQueue = null;
			cleanupSource = null;
			cleanupWorld = null;
		}
	}

	private static int cleanupLightsCommand(com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> context) {
		var source = context.getSource();

		if (cleanupQueue != null && !cleanupQueue.isEmpty()) {
			source.sendFeedback(() -> Text.literal("Cleanup already in progress"), false);
			return 0;
		}

		var world = source.getWorld();
		var pos = BlockPos.ofFloored(source.getPosition());
		int radius = ShipConfig.CLEANUP_LIGHT_RADIUS;

		// Build column queue — processing COLUMNS_PER_TICK columns per server tick
		// spreads the work across frames instead of freezing the server.
		Queue<int[]> columns = new ArrayDeque<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				columns.add(new int[]{pos.getX() + x, pos.getZ() + z});
			}
		}

		cleanupQueue = columns;
		cleanupSource = source;
		cleanupWorld = world;
		cleanupMinY = Math.max(world.getBottomY(), pos.getY() - radius);
		cleanupMaxY = Math.min(world.getTopYInclusive(), pos.getY() + radius);
		cleanupRadius = radius;
		cleanupRemoved = 0;

		source.sendFeedback(() -> Text.literal("Cleaning up light blocks..."), false);
		return 1;
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
