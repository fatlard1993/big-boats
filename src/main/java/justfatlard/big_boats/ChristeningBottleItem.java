package justfatlard.big_boats;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * A throwable bottle used to christen ships.
 * Throw at a helm block to convert connected boatable blocks into a ship entity.
 */
public class ChristeningBottleItem extends Item implements PolymerItem {
	private final Identifier modelId;

	public ChristeningBottleItem(Settings settings) {
		super(settings);
		this.modelId = Identifier.of(BigBoats.MOD_ID, "christening_bottle");
	}

	@Override
	public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
		return Items.SPLASH_POTION;
	}

	@Override
	public Identifier getPolymerItemModel(ItemStack itemStack, PacketContext context) {
		return this.modelId;
	}

	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);

		// Play throw sound
		world.playSound(
			null,
			user.getX(), user.getY(), user.getZ(),
			SoundEvents.ENTITY_SPLASH_POTION_THROW,
			SoundCategory.PLAYERS,
			0.5F,
			0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F)
		);

		if (!world.isClient()) {
			// Create and spawn the projectile
			ChristeningBottleEntity entity = new ChristeningBottleEntity(world, user, stack);
			entity.setVelocity(user, user.getPitch(), user.getYaw(), 0.0F, 0.5F, 1.0F);
			world.spawnEntity(entity);
		}

		user.incrementStat(Stats.USED.getOrCreateStat(this));
		stack.decrementUnlessCreative(1, user);

		return ActionResult.SUCCESS;
	}
}
