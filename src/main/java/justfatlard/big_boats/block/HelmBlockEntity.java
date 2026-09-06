package justfatlard.big_boats.block;

import justfatlard.big_boats.BigBoats;
import justfatlard.big_boats.ship.ShipConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a placed helm remembers: how large a ship it is rated to command.
 *
 * <p>The rating comes off the Tonnage enchantment on the helm item, and it has to live somewhere
 * once that item becomes a block - an enchantment is a component on a stack, and a stack stops
 * existing the moment it is placed. Kept here rather than in the block state so the helm needs no
 * extra blockstate variants and no client-side model work; nothing about it is visible.
 *
 * <p>It rides along on a voyage for free: a ship stores each of its blocks with that block's entity
 * data, so the helm's rating survives being dismantled into a ship and rebuilt at the far end.
 */
public class HelmBlockEntity extends BlockEntity {

	private static final String TAG_TONNAGE = "tonnage";

	private int tonnage = 0;

	public HelmBlockEntity(BlockPos pos, BlockState state) {
		super(BigBoats.HELM_BLOCK_ENTITY_TYPE, pos, state);
	}

	/** Enchantment level, 0 for an unenchanted helm. */
	public int getTonnage() {
		return this.tonnage;
	}

	public void setTonnage(int tonnage) {
		this.tonnage = tonnage;
		this.setChanged();
	}

	/** Largest ship this helm can hold together. */
	public int capacity() {
		return ShipConfig.capacityForTonnage(this.tonnage);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt(TAG_TONNAGE, this.tonnage);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.tonnage = input.getIntOr(TAG_TONNAGE, 0);
	}
}
