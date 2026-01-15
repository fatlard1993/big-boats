package justfatlard.big_boats;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.item.PolymerItemGroupUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import justfatlard.big_boats.block.HelmBlock;
import justfatlard.big_boats.ship.MultiBlockShipEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class BigBoats implements ModInitializer {
	public static final String MOD_ID = "big-boats-justfatlard";

	// Tag for blocks that can be converted to boats (craftable/manufactured blocks)
	public static final TagKey<Block> BOATABLE_BLOCKS = TagKey.of(
		RegistryKeys.BLOCK,
		Identifier.of(MOD_ID, "boatable_blocks")
	);

	// Helper methods to create registry keys
	private static RegistryKey<Item> itemKeyOf(String name) {
		return RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, name));
	}

	private static RegistryKey<Block> blockKeyOf(String name) {
		return RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(MOD_ID, name));
	}

	private static RegistryKey<EntityType<?>> entityKeyOf(String name) {
		return RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MOD_ID, name));
	}

	// Christening Bottle item
	public static final RegistryKey<Item> CHRISTENING_BOTTLE_KEY = itemKeyOf("christening_bottle");
	public static final ChristeningBottleItem CHRISTENING_BOTTLE = new ChristeningBottleItem(
		new Item.Settings()
			.registryKey(CHRISTENING_BOTTLE_KEY)
			.maxCount(16)
	);

	// Helm Block
	public static final RegistryKey<Block> HELM_BLOCK_KEY = blockKeyOf("helm");
	public static final HelmBlock HELM_BLOCK = new HelmBlock(
		AbstractBlock.Settings.copy(Blocks.OAK_PLANKS)
			.registryKey(HELM_BLOCK_KEY)
	);

	// Helm Block Item
	public static final RegistryKey<Item> HELM_ITEM_KEY = itemKeyOf("helm");
	public static final HelmBlock.HelmBlockItem HELM_ITEM = new HelmBlock.HelmBlockItem(
		HELM_BLOCK,
		new Item.Settings()
			.registryKey(HELM_ITEM_KEY)
	);

	// Multi-Block Ship Entity
	public static final RegistryKey<EntityType<?>> MULTI_BLOCK_SHIP_ENTITY_KEY = entityKeyOf("ship");
	public static final EntityType<MultiBlockShipEntity> MULTI_BLOCK_SHIP_ENTITY_TYPE = EntityType.Builder
		.<MultiBlockShipEntity>create(MultiBlockShipEntity::new, SpawnGroup.MISC)
		.dimensions(3.0F, 1.5F) // Larger for easier interaction
		.eyeHeight(1.0F)
		.maxTrackingRange(16)
		.build(MULTI_BLOCK_SHIP_ENTITY_KEY);

	// Christening Bottle Projectile Entity
	public static final RegistryKey<EntityType<?>> CHRISTENING_BOTTLE_ENTITY_KEY = entityKeyOf("christening_bottle");
	public static final EntityType<ChristeningBottleEntity> CHRISTENING_BOTTLE_ENTITY_TYPE = EntityType.Builder
		.<ChristeningBottleEntity>create(ChristeningBottleEntity::new, SpawnGroup.MISC)
		.dimensions(0.25F, 0.25F)
		.maxTrackingRange(4)
		.trackingTickInterval(10)
		.build(CHRISTENING_BOTTLE_ENTITY_KEY);

	// Keep old BlockBoatEntity for backwards compatibility (will be removed later)
	public static final RegistryKey<EntityType<?>> BLOCK_BOAT_ENTITY_KEY = entityKeyOf("block_boat");
	public static final EntityType<BlockBoatEntity> BLOCK_BOAT_ENTITY_TYPE = EntityType.Builder
		.<BlockBoatEntity>create(BlockBoatEntity::new, SpawnGroup.MISC)
		.dimensions(1.375F, 0.5625F)
		.eyeHeight(0.5625F)
		.maxTrackingRange(10)
		.build(BLOCK_BOAT_ENTITY_KEY);

	@Override
	public void onInitialize() {
		// Register mod assets with Polymer resource pack system
		PolymerResourcePackUtils.addModAssets(MOD_ID);
		PolymerResourcePackUtils.markAsRequired();

		// Register blocks
		Registry.register(Registries.BLOCK, HELM_BLOCK_KEY.getValue(), HELM_BLOCK);

		// Register Polymer block models for the helm (ship wheel)
		HELM_BLOCK.registerPolymerModels();

		// Register items
		Registry.register(Registries.ITEM, CHRISTENING_BOTTLE_KEY.getValue(), CHRISTENING_BOTTLE);
		Registry.register(Registries.ITEM, HELM_ITEM_KEY.getValue(), HELM_ITEM);

		// Register entity types
		Registry.register(Registries.ENTITY_TYPE, MULTI_BLOCK_SHIP_ENTITY_KEY.getValue(), MULTI_BLOCK_SHIP_ENTITY_TYPE);
		PolymerEntityUtils.registerType(MULTI_BLOCK_SHIP_ENTITY_TYPE);

		Registry.register(Registries.ENTITY_TYPE, CHRISTENING_BOTTLE_ENTITY_KEY.getValue(), CHRISTENING_BOTTLE_ENTITY_TYPE);
		PolymerEntityUtils.registerType(CHRISTENING_BOTTLE_ENTITY_TYPE);

		Registry.register(Registries.ENTITY_TYPE, BLOCK_BOAT_ENTITY_KEY.getValue(), BLOCK_BOAT_ENTITY_TYPE);
		PolymerEntityUtils.registerType(BLOCK_BOAT_ENTITY_TYPE);

		// Create Polymer item group
		ItemGroup bigBoatsGroup = PolymerItemGroupUtils.builder()
			.displayName(Text.literal("Big Boats"))
			.icon(() -> new ItemStack(CHRISTENING_BOTTLE))
			.entries((context, entries) -> {
				entries.add(new ItemStack(CHRISTENING_BOTTLE));
				entries.add(new ItemStack(HELM_ITEM));
			})
			.build();
		PolymerItemGroupUtils.registerPolymerItemGroup(Identifier.of(MOD_ID, "big_boats"), bigBoatsGroup);

		// Add to vanilla creative tabs
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(CHRISTENING_BOTTLE);
		});
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
			entries.add(HELM_ITEM);
		});

		// Register callback to handle helm interaction clicks
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (entity instanceof InteractionEntity) {
				// Find the ship that owns this interaction entity
				for (MultiBlockShipEntity ship : world.getEntitiesByClass(
						MultiBlockShipEntity.class,
						entity.getBoundingBox().expand(1.0),
						s -> s.isHelmInteraction(entity))) {
					if (player.startRiding(ship)) {
						return ActionResult.SUCCESS;
					}
				}
			}
			return ActionResult.PASS;
		});

		System.out.println("[big-boats] Loaded Big Boats mod - Multi-block ship system enabled!");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
