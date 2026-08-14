package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.RelativeBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages ship light sources: detects light-emitting blocks and places/updates
 * invisible {@link Blocks#LIGHT} blocks as the ship moves.
 *
 * <p>Lifecycle: {@link #detectFromBlocks} scans for luminous blocks. {@link #spawnLightBlocks}
 * places initial lights on undock. {@link #updatePositions} moves lights as the ship sails
 * (places new before removing old to minimize crash-unsafe windows). {@link #remove} cleans
 * up on dock or entity removal.</p>
 *
 * <p>Light positions are serialized for crash recovery: if the server stops while sailing,
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
			int lightLevel = block.blockState().getLightEmission();
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
	public void spawnLightBlocks(ServerLevel world, ShipPose pose) {
		Set<BlockPos> newPositions = new HashSet<>();

		for (LightSource source : lightSources) {
			Vec3 worldPos = pose.toWorld(source.relativePos());
			BlockPos lightPos = BlockPos.containing(worldPos.x, worldPos.y, worldPos.z);

			if (world.getBlockState(lightPos).isAir()) {
				BlockState lightBlock = Blocks.LIGHT.defaultBlockState()
					.setValue(LightBlock.LEVEL, source.lightLevel());
				world.setBlock(lightPos, lightBlock, Block.UPDATE_CLIENTS);
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
	public void updatePositions(ServerLevel world, ShipPose pose) {
		if (lightSources.isEmpty()) return;

		Set<BlockPos> newPositions = new HashSet<>();
		for (LightSource source : lightSources) {
			Vec3 worldPos = pose.toWorld(source.relativePos());
			BlockPos lightPos = BlockPos.containing(worldPos.x, worldPos.y, worldPos.z);

			if (world.getBlockState(lightPos).isAir()) {
				BlockState lightBlock = Blocks.LIGHT.defaultBlockState()
					.setValue(LightBlock.LEVEL, source.lightLevel());
				world.setBlock(lightPos, lightBlock, Block.UPDATE_CLIENTS);
				newPositions.add(lightPos);
			}
		}

		for (BlockPos pos : placedLightPositions) {
			if (!newPositions.contains(pos)) {
				BlockState state = world.getBlockState(pos);
				if (state.getBlock() == Blocks.LIGHT) {
					world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}

		// Assign as immutable snapshot: safe for concurrent read by the chunk-saving thread
		placedLightPositions = Set.copyOf(newPositions);

		lastLightUpdatePos = pose.helmBlockPos();
		lastLightUpdateYaw = (int) Math.floor(Math.toDegrees(pose.yawRadians()) / 15) * 15;
	}

	/**
	 * Removes all placed light blocks.
	 * Call when docking or removing the ship.
	 */
	public void remove(ServerLevel world) {
		for (BlockPos pos : placedLightPositions) {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() == Blocks.LIGHT) {
				world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
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
	public static void cleanupLightPositions(ServerLevel world, List<BlockPos> positions) {
		for (BlockPos pos : positions) {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() == Blocks.LIGHT) {
				world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
	}

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
