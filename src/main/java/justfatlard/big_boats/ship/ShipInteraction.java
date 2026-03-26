package justfatlard.big_boats.ship;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Handles player interaction with blocks on a moving ship.
 * Stateless helper — call methods with the ship's current state.
 */
public final class ShipInteraction {

	/**
	 * Finds which block the player is looking at via raycast.
	 * Returns the block index, or -1 if no block is targeted.
	 *
	 * Performance: O(n) linear scan over all blocks. Acceptable for the current
	 * MAX_BLOCKS limit of 2000. If this limit increases significantly, consider
	 * spatial indexing (octree or spatial hash).
	 */
	public static int findLookedAtBlock(PlayerEntity player, List<ShipBlock> blocks, ShipPose pose) {
		Vec3d eyePos = player.getEyePos();
		Vec3d lookVec = player.getRotationVec(1.0f);
		double reach = ShipConfig.PLAYER_REACH;

		int closestIndex = -1;
		double closestDist = reach;

		for (int i = 0; i < blocks.size(); i++) {
			ShipBlock block = blocks.get(i);

			Vec3d worldPos = pose.toWorld(block.relativePos());

			double worldX = worldPos.x;
			double worldY = worldPos.y;
			double worldZ = worldPos.z;

			Box blockBox = new Box(worldX, worldY, worldZ, worldX + 1, worldY + 1, worldZ + 1);

			java.util.Optional<Vec3d> hit = blockBox.raycast(eyePos, eyePos.add(lookVec.multiply(reach)));
			if (hit.isPresent()) {
				double dist = hit.get().distanceTo(eyePos);
				if (dist < closestDist) {
					closestDist = dist;
					closestIndex = i;
				}
			}
		}

		return closestIndex;
	}

	/**
	 * Tries to interact with a ship block (doors, trapdoors, fence gates).
	 * Returns SUCCESS if interaction happened, PASS otherwise.
	 *
	 * @param blockUpdater callback to update the block state in the ship's block list and display
	 */
	public static ActionResult tryInteractWithBlock(List<ShipBlock> blocks, int blockIndex,
													World world, Vec3d soundPos,
													BlockUpdater blockUpdater) {
		if (blockIndex < 0 || blockIndex >= blocks.size()) {
			return ActionResult.PASS;
		}

		ShipBlock shipBlock = blocks.get(blockIndex);
		BlockState state = shipBlock.blockState();

		if (state.getBlock() instanceof DoorBlock) {
			// Iron doors require redstone in vanilla — don't toggle by hand
			if (state.isOf(Blocks.IRON_DOOR)) {
				return ActionResult.PASS;
			}

			BlockState newState = state.cycle(Properties.OPEN);
			blockUpdater.update(blockIndex, newState);

			boolean isOpen = newState.get(Properties.OPEN);
			world.playSound(null, soundPos.x, soundPos.y, soundPos.z,
				isOpen ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
				SoundCategory.BLOCKS, 1.0f, 1.0f);

			toggleDoorOtherHalf(blocks, blockIndex, state, isOpen, blockUpdater);
			return ActionResult.SUCCESS;
		}

		if (state.getBlock() instanceof TrapdoorBlock) {
			// Iron trapdoors require redstone in vanilla — don't toggle by hand
			if (state.isOf(Blocks.IRON_TRAPDOOR)) {
				return ActionResult.PASS;
			}

			BlockState newState = state.cycle(Properties.OPEN);
			blockUpdater.update(blockIndex, newState);

			boolean isOpen = newState.get(Properties.OPEN);
			world.playSound(null, soundPos.x, soundPos.y, soundPos.z,
				isOpen ? SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE,
				SoundCategory.BLOCKS, 1.0f, 1.0f);

			return ActionResult.SUCCESS;
		}

		if (state.getBlock() instanceof FenceGateBlock) {
			BlockState newState = state.cycle(Properties.OPEN);
			blockUpdater.update(blockIndex, newState);

			boolean isOpen = newState.get(Properties.OPEN);
			world.playSound(null, soundPos.x, soundPos.y, soundPos.z,
				isOpen ? SoundEvents.BLOCK_FENCE_GATE_OPEN : SoundEvents.BLOCK_FENCE_GATE_CLOSE,
				SoundCategory.BLOCKS, 1.0f, 1.0f);

			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	private static void toggleDoorOtherHalf(List<ShipBlock> blocks, int doorIndex,
											BlockState doorState, boolean isOpen,
											BlockUpdater blockUpdater) {
		DoubleBlockHalf half = doorState.get(Properties.DOUBLE_BLOCK_HALF);
		int yOffset = (half == DoubleBlockHalf.LOWER) ? 1 : -1;

		ShipBlock doorBlock = blocks.get(doorIndex);
		int targetY = doorBlock.relativePos().y() + yOffset;

		for (int i = 0; i < blocks.size(); i++) {
			if (i == doorIndex) continue;

			ShipBlock block = blocks.get(i);
			if (block.relativePos().x() == doorBlock.relativePos().x()
				&& block.relativePos().y() == targetY
				&& block.relativePos().z() == doorBlock.relativePos().z()
				&& block.blockState().getBlock() instanceof DoorBlock) {

				BlockState otherState = block.blockState().with(Properties.OPEN, isOpen);
				blockUpdater.update(i, otherState);
				break;
			}
		}
	}

	/**
	 * Callback for updating a block's state in the ship's block list and display.
	 */
	@FunctionalInterface
	public interface BlockUpdater {
		void update(int index, BlockState newState);
	}

	private ShipInteraction() {}
}
