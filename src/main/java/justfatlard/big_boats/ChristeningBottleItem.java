package justfatlard.big_boats;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A throwable bottle used to christen ships.
 * Throw at any part of a ship to convert connected solid blocks into a ship entity.
 *
 * <p>Client appearance (model, glint) is declared via Pandorical's content API in
 * {@link BigBoats#onInitialize}; see {@code ItemRegistration}.</p>
 */
public class ChristeningBottleItem extends Item {

	public ChristeningBottleItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);

		world.playSound(
			null,
			user.getX(), user.getY(), user.getZ(),
			SoundEvents.SPLASH_POTION_THROW,
			SoundSource.PLAYERS,
			0.5F,
			0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F)
		);

		if (!world.isClientSide()) {
			ChristeningBottleEntity entity = new ChristeningBottleEntity(world, user, stack);
			entity.shootFromRotation(user, user.getXRot(), user.getYRot(), 0.0F, 1.5F, 1.0F);
			world.addFreshEntity(entity);
		}

		user.awardStat(Stats.ITEM_USED.get(this));
		stack.consume(1, user);

		return InteractionResult.SUCCESS;
	}
}
