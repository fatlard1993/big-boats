package justfatlard.big_boats.ship;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Handles player interaction with blocks on a moving ship.
 * Stateless helper; call methods with the ship's current state.
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
	public static int findLookedAtBlock(Player player, List<ShipBlock> blocks, ShipPose pose) {
		Vec3 eyePos = player.getEyePosition();
		Vec3 lookVec = player.getViewVector(1.0f);
		double reach = ShipConfig.PLAYER_REACH;

		int closestIndex = -1;
		double closestDist = reach;

		for (int i = 0; i < blocks.size(); i++) {
			ShipBlock block = blocks.get(i);

			Vec3 worldPos = pose.toWorld(block.relativePos());

			double worldX = worldPos.x;
			double worldY = worldPos.y;
			double worldZ = worldPos.z;

			AABB blockBox = new AABB(worldX, worldY, worldZ, worldX + 1, worldY + 1, worldZ + 1);

			java.util.Optional<Vec3> hit = blockBox.clip(eyePos, eyePos.add(lookVec.scale(reach)));
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
	public static InteractionResult tryInteractWithBlock(List<ShipBlock> blocks, int blockIndex,
													Level world, Vec3 soundPos,
													BlockUpdater blockUpdater) {
		if (blockIndex < 0 || blockIndex >= blocks.size()) {
			return InteractionResult.PASS;
		}

		ShipBlock shipBlock = blocks.get(blockIndex);
		BlockState state = shipBlock.blockState();

		if (state.getBlock() instanceof DoorBlock) {
			// Iron doors require redstone in vanilla; don't toggle by hand
			if (state.getBlock() == Blocks.IRON_DOOR) {
				return InteractionResult.PASS;
			}

			BlockState newState = state.cycle(BlockStateProperties.OPEN);
			blockUpdater.update(blockIndex, newState);

			boolean isOpen = newState.getValue(BlockStateProperties.OPEN);
			world.playSound(null, soundPos.x, soundPos.y, soundPos.z,
				isOpen ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
				SoundSource.BLOCKS, 1.0f, 1.0f);

			toggleDoorOtherHalf(blocks, blockIndex, state, isOpen, blockUpdater);
			return InteractionResult.SUCCESS;
		}

		if (state.getBlock() instanceof TrapDoorBlock) {
			// Iron trapdoors require redstone in vanilla; don't toggle by hand
			if (state.getBlock() == Blocks.IRON_TRAPDOOR) {
				return InteractionResult.PASS;
			}

			BlockState newState = state.cycle(BlockStateProperties.OPEN);
			blockUpdater.update(blockIndex, newState);

			boolean isOpen = newState.getValue(BlockStateProperties.OPEN);
			world.playSound(null, soundPos.x, soundPos.y, soundPos.z,
				isOpen ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE,
				SoundSource.BLOCKS, 1.0f, 1.0f);

			return InteractionResult.SUCCESS;
		}

		if (state.getBlock() instanceof FenceGateBlock) {
			BlockState newState = state.cycle(BlockStateProperties.OPEN);
			blockUpdater.update(blockIndex, newState);

			boolean isOpen = newState.getValue(BlockStateProperties.OPEN);
			world.playSound(null, soundPos.x, soundPos.y, soundPos.z,
				isOpen ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
				SoundSource.BLOCKS, 1.0f, 1.0f);

			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
	}

	private static void toggleDoorOtherHalf(List<ShipBlock> blocks, int doorIndex,
											BlockState doorState, boolean isOpen,
											BlockUpdater blockUpdater) {
		DoubleBlockHalf half = doorState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
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

				BlockState otherState = block.blockState().setValue(BlockStateProperties.OPEN, isOpen);
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
