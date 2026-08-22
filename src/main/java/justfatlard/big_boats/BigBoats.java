package justfatlard.big_boats;

import justfatlard.big_boats.block.HelmBlock;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import justfatlard.big_boats.ship.ShipConfig;
import justfatlard.big_boats.ship.ShipInteraction;
import justfatlard.big_boats.util.PlayerInputStorage;
import justfatlard.pandorical.api.BlockRegistration;
import justfatlard.pandorical.api.ItemRegistration;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Queue;

public class BigBoats implements ModInitializer {
	public static final String MOD_ID = "big-boats-justfatlard";
	private static final Logger LOGGER = LoggerFactory.getLogger(BigBoats.class);

	private static ResourceKey<Item> itemKeyOf(String name) {
		return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, name));
	}

	private static ResourceKey<Block> blockKeyOf(String name) {
		return ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, name));
	}

	private static ResourceKey<EntityType<?>> entityKeyOf(String name) {
		return ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, name));
	}

	public static final ResourceKey<Item> CHRISTENING_BOTTLE_KEY = itemKeyOf("christening_bottle");
	public static final ChristeningBottleItem CHRISTENING_BOTTLE = new ChristeningBottleItem(
		new Item.Properties()
			.setId(CHRISTENING_BOTTLE_KEY)
			.stacksTo(16)
	);

	public static final ResourceKey<Block> HELM_BLOCK_KEY = blockKeyOf("helm");
	public static final HelmBlock HELM_BLOCK = new HelmBlock(
		BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
			.setId(HELM_BLOCK_KEY)
			.noOcclusion()
	);

	public static final ResourceKey<Item> HELM_ITEM_KEY = itemKeyOf("helm");
	public static final HelmBlock.HelmBlockItem HELM_ITEM = new HelmBlock.HelmBlockItem(
		HELM_BLOCK,
		new Item.Properties()
			.setId(HELM_ITEM_KEY)
	);

	public static final ResourceKey<EntityType<?>> MULTI_BLOCK_SHIP_ENTITY_KEY = entityKeyOf("ship");
	public static final EntityType<MultiBlockShipEntity> MULTI_BLOCK_SHIP_ENTITY_TYPE = EntityType.Builder
		.<MultiBlockShipEntity>of(MultiBlockShipEntity::new, MobCategory.MISC)
		.sized(3.0F, 1.5F) // Large enough for entity tracker to keep ship loaded when nearby
		.eyeHeight(1.0F)
		.clientTrackingRange(ShipConfig.ENTITY_TRACKING_RANGE)
		.updateInterval(1)
		.build(MULTI_BLOCK_SHIP_ENTITY_KEY);

	public static final ResourceKey<EntityType<?>> CHRISTENING_BOTTLE_ENTITY_KEY = entityKeyOf("christening_bottle");
	public static final EntityType<ChristeningBottleEntity> CHRISTENING_BOTTLE_ENTITY_TYPE = EntityType.Builder
		.<ChristeningBottleEntity>of(ChristeningBottleEntity::new, MobCategory.MISC)
		.sized(0.25F, 0.25F)
		.clientTrackingRange(4)
		.updateInterval(10)
		.build(CHRISTENING_BOTTLE_ENTITY_KEY);

	@Override
	public void onInitialize() {
		// Guarded class load: BoatKitDialogue names village-quests types.
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("village-quests-justfatlard")) {
			justfatlard.big_boats.integration.BoatKitDialogue.register();
		}

		if (PandoricalApi.isAvailable()) {
			PandoricalApi.content().registerBlock(MOD_ID + ":helm", new BlockRegistration()
				.baseBlock("minecraft:oak_planks")
				.interactive()
				.model(MOD_ID + ":block/helm"));
			PandoricalApi.content().registerItem(MOD_ID + ":helm", new ItemRegistration()
				.model(MOD_ID + ":item/helm"));
			PandoricalApi.content().registerItem(MOD_ID + ":christening_bottle", new ItemRegistration()
				.model(MOD_ID + ":item/christening_bottle")
				.maxStackSize(16)
				.hasGlint(true));
			PandoricalApi.content().registerModAssets(MOD_ID);
		}

		Registry.register(BuiltInRegistries.BLOCK, HELM_BLOCK_KEY.identifier(), HELM_BLOCK);

		Registry.register(BuiltInRegistries.ITEM, CHRISTENING_BOTTLE_KEY.identifier(), CHRISTENING_BOTTLE);
		Registry.register(BuiltInRegistries.ITEM, HELM_ITEM_KEY.identifier(), HELM_ITEM);

		Registry.register(BuiltInRegistries.ENTITY_TYPE, MULTI_BLOCK_SHIP_ENTITY_KEY.identifier(), MULTI_BLOCK_SHIP_ENTITY_TYPE);
		Registry.register(BuiltInRegistries.ENTITY_TYPE, CHRISTENING_BOTTLE_ENTITY_KEY.identifier(), CHRISTENING_BOTTLE_ENTITY_TYPE);

		// Ships render nothing themselves: the Pandorical structure is the visible object.
		// The christening bottle renders as a normal thrown item.
		PandoricalApi.registerEntityRenderer(MULTI_BLOCK_SHIP_ENTITY_TYPE, "invisible");
		PandoricalApi.registerEntityRenderer(CHRISTENING_BOTTLE_ENTITY_TYPE, "thrown_item");

		ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(MOD_ID, "big_boats"));
		CreativeModeTab bigBoatsGroup = FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.big-boats-justfatlard.big_boats"))
			.icon(() -> new ItemStack(CHRISTENING_BOTTLE))
			.displayItems((context, entries) -> {
				entries.accept(new ItemStack(CHRISTENING_BOTTLE));
				entries.accept(new ItemStack(HELM_ITEM));
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, bigBoatsGroup);

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(CHRISTENING_BOTTLE);
		});
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(HELM_ITEM);
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (entity instanceof Interaction && player instanceof ServerPlayer serverPlayer) {
				// Find the ship that owns this interaction entity
				for (MultiBlockShipEntity ship : world.getEntities(
						net.minecraft.world.level.entity.EntityTypeTest.forClass(MultiBlockShipEntity.class),
						entity.getBoundingBox().inflate(1.0),
						s -> s.isHelmInteraction(entity))) {
					if (ship.tryMount(serverPlayer)) {
						return InteractionResult.SUCCESS;
					}
				}
			}

			// Handle block interactions on moving ships (doors, trapdoors, fence gates)
			if (entity instanceof Shulker && player instanceof ServerPlayer) {
				Entity vehicle = player.getVehicle();
				if (vehicle instanceof MultiBlockShipEntity ship && !ship.isDocked()) {
					if (ship.isCollisionShulker(entity)) {
						int blockIndex = ShipInteraction.findLookedAtBlock(
							player, ship.getBlocks(), ship.pose());
						if (blockIndex >= 0) {
							return ShipInteraction.tryInteractWithBlock(
								ship.getBlocks(), blockIndex, world,
								new Vec3(entity.getX(), entity.getY(), entity.getZ()),
								ship::updateShipBlock);
						}
					}
				}
			}

			return InteractionResult.PASS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				Commands.literal("bigboats")
					.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
					.then(Commands.literal("cleanup-lights")
						.executes(BigBoats::cleanupLightsCommand)
					)
			);
		});

		// Clean up player input storage on disconnect to prevent memory leak
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PlayerInputStorage.removePlayer(handler.getPlayer().getUUID());
		});

		// Tick-spread listener for cleanup-lights command (registered once, checks flag each tick)
		ServerTickEvents.END_SERVER_TICK.register(BigBoats::tickCleanup);

		LOGGER.info("[" + MOD_ID + "] Loaded");
	}

	// Tick-spread state for cleanup-lights command.
	// Only one cleanup runs at a time. The tick listener is registered once in onInitialize.
	private static Queue<int[]> cleanupQueue = null;
	private static net.minecraft.commands.CommandSourceStack cleanupSource = null;
	private static net.minecraft.server.level.ServerLevel cleanupWorld = null;
	private static int cleanupMinY = 0;
	private static int cleanupMaxY = 0;
	private static int cleanupRadius = 0;
	private static int cleanupRemoved = 0;
	private static final int COLUMNS_PER_TICK = 50;

	private static void tickCleanup(MinecraftServer ignoredServer) {
		if (cleanupQueue == null || cleanupQueue.isEmpty()) return;

		int processed = 0;
		while (!cleanupQueue.isEmpty() && processed < COLUMNS_PER_TICK) {
			int[] col = cleanupQueue.poll();
			for (int y = cleanupMinY; y <= cleanupMaxY; y++) {
				var checkPos = new BlockPos(col[0], y, col[1]);
				if (cleanupWorld.getBlockState(checkPos).getBlock() == Blocks.LIGHT) {
					cleanupWorld.setBlock(checkPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					cleanupRemoved++;
				}
			}
			processed++;
		}

		if (cleanupQueue.isEmpty()) {
			final int count = cleanupRemoved;
			final int r = cleanupRadius;
			cleanupSource.sendSuccess(
				() -> Component.translatable("big-boats.command.cleanup_lights", count, r), true);
			cleanupQueue = null;
			cleanupSource = null;
			cleanupWorld = null;
		}
	}

	private static int cleanupLightsCommand(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context) {
		var source = context.getSource();

		if (cleanupQueue != null && !cleanupQueue.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Cleanup already in progress"), false);
			return 0;
		}

		var world = source.getLevel();
		var pos = BlockPos.containing(source.getPosition());
		int radius = ShipConfig.CLEANUP_LIGHT_RADIUS;

		// Processing COLUMNS_PER_TICK columns per server tick spreads the work
		// across frames instead of freezing the server.
		Queue<int[]> columns = new ArrayDeque<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				columns.add(new int[]{pos.getX() + x, pos.getZ() + z});
			}
		}

		cleanupQueue = columns;
		cleanupSource = source;
		cleanupWorld = world;
		cleanupMinY = Math.max(world.getMinY(), pos.getY() - radius);
		cleanupMaxY = Math.min(world.getMaxY(), pos.getY() + radius);
		cleanupRadius = radius;
		cleanupRemoved = 0;

		source.sendSuccess(() -> Component.literal("Cleaning up light blocks..."), false);
		return 1;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
