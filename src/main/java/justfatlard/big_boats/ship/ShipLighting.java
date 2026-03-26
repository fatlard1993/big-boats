package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages ship light sources — detecting light-emitting blocks and placing/updating
 * invisible {@link Blocks#LIGHT} blocks as the ship moves.
 *
 * <p>Lifecycle: {@link #detectFromBlocks} scans for luminous blocks. {@link #spawnLightBlocks}
 * places initial lights on undock. {@link #updatePositions} moves lights as the ship sails
 * (places new before removing old to minimize crash-unsafe windows). {@link #remove} cleans
 * up on dock or entity removal.</p>
 *
 * <p>Light positions are serialized for crash recovery — if the server stops while sailing,
 * {@link #cleanupLightPositions} removes stale lights on reload.</p>
 */
public class ShipLighting {
	private static final Logger LOGGER = LoggerFactory.getLogger(ShipLighting.class);

	private record LightSource(RelativeBlockPos relativePos, int lightLevel) {}

	private List<LightSource> lightSources = new ArrayList<>();
	// Volatile: getPlacedLightPositions() is called from chunk-saving thread via writeCustomData.
	// Reassigned (not mutated) in updatePositions and remove.
	private volatile Set<BlockPos> placedLightPositions = Set.of();
	private BlockPos lastLightUpdatePos = null;
	private int lastLightUpdateYaw = 0;

	public void detectFromBlocks(List<ShipBlock> blocks) {
		lightSources.clear();

		for (ShipBlock block : blocks) {
			int lightLevel = block.blockState().getLuminance();
			if (lightLevel > 0) {
				lightSources.add(new LightSource(block.relativePos(), lightLevel));
			}
		}
		if (!lightSources.isEmpty()) {
			LOGGER.debug("Detected {} light sources in ship", lightSources.size());
		}
	}

	/**
	 * Detects light sources and spawns light blocks at their current positions.
	 * Call when undocking.
	 */
	public void spawnLightBlocks(ServerWorld world, ShipPose pose) {
		Set<BlockPos> newPositions = new HashSet<>();

		for (LightSource source : lightSources) {
			Vec3d worldPos = pose.toWorld(source.relativePos());
			BlockPos lightPos = BlockPos.ofFloored(worldPos.x, worldPos.y, worldPos.z);

			if (world.getBlockState(lightPos).isAir()) {
				BlockState lightBlock = Blocks.LIGHT.getDefaultState()
					.with(LightBlock.LEVEL_15, source.lightLevel());
				world.setBlockState(lightPos, lightBlock, Block.NOTIFY_LISTENERS);
				newPositions.add(lightPos);
			}
		}
		placedLightPositions = Set.copyOf(newPositions);

		lastLightUpdatePos = pose.helmBlockPos();
		lastLightUpdateYaw = (int) Math.floor(Math.toDegrees(pose.yawRadians()) / 15) * 15;
	}

	/**
	 * Updates light block positions as the ship moves/rotates.
	 * Places new lights before removing old ones to avoid crash-unsafe window.
	 */
	public void updatePositions(ServerWorld world, ShipPose pose) {
		if (lightSources.isEmpty()) return;

		Set<BlockPos> newPositions = new HashSet<>();
		for (LightSource source : lightSources) {
			Vec3d worldPos = pose.toWorld(source.relativePos());
			BlockPos lightPos = BlockPos.ofFloored(worldPos.x, worldPos.y, worldPos.z);

			if (world.getBlockState(lightPos).isAir()) {
				BlockState lightBlock = Blocks.LIGHT.getDefaultState()
					.with(LightBlock.LEVEL_15, source.lightLevel());
				world.setBlockState(lightPos, lightBlock, Block.NOTIFY_LISTENERS);
				newPositions.add(lightPos);
			}
		}

		for (BlockPos pos : placedLightPositions) {
			if (!newPositions.contains(pos)) {
				BlockState state = world.getBlockState(pos);
				if (state.isOf(Blocks.LIGHT)) {
					world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
				}
			}
		}

		// Assign as immutable snapshot — safe for concurrent read by chunk-saving thread
		placedLightPositions = Set.copyOf(newPositions);

		lastLightUpdatePos = pose.helmBlockPos();
		lastLightUpdateYaw = (int) Math.floor(Math.toDegrees(pose.yawRadians()) / 15) * 15;
	}

	/**
	 * Removes all placed light blocks.
	 * Call when docking or removing the ship.
	 */
	public void remove(ServerWorld world) {
		for (BlockPos pos : placedLightPositions) {
			BlockState state = world.getBlockState(pos);
			if (state.isOf(Blocks.LIGHT)) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
			}
		}
		placedLightPositions = Set.of();
		lightSources.clear();
	}

	/**
	 * Returns a copy of the currently placed light positions.
	 * Used for serialization before dock() clears them.
	 */
	public List<BlockPos> getPlacedLightPositions() {
		return new ArrayList<>(placedLightPositions);
	}

	/**
	 * Removes LIGHT blocks at the given positions.
	 * Used for crash recovery when light positions were serialized but not cleaned up.
	 */
	public static void cleanupLightPositions(ServerWorld world, List<BlockPos> positions) {
		for (BlockPos pos : positions) {
			BlockState state = world.getBlockState(pos);
			if (state.isOf(Blocks.LIGHT)) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
			}
		}
	}

	/**
	 * Checks if light positions need updating based on ship pose.
	 */
	public boolean needsUpdate(ShipPose pose) {
		if (lightSources.isEmpty()) {
			return false;
		}

		BlockPos currentPos = pose.helmBlockPos();
		double yawDegrees = Math.toDegrees(pose.yawRadians());
		int currentYawBucket = (int) Math.floor(yawDegrees / 15) * 15;

		boolean posChanged = lastLightUpdatePos == null || !lastLightUpdatePos.equals(currentPos);
		boolean yawChanged = currentYawBucket != lastLightUpdateYaw;

		return posChanged || yawChanged;
	}

	public boolean hasLightSources() {
		return !lightSources.isEmpty();
	}

}
