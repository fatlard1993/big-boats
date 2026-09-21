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
	/**
	 * Every light block this ship is answerable for: the ones burning now, and the ones it has
	 * moved off and not yet taken out.
	 *
	 * <p>One field, because the save thread reads both and the two together are the answer to
	 * "what has this ship left in the world". Held apart, a save could land between the two
	 * stores and record a set that was true of neither moment, and a crash then left light
	 * blocks burning that nothing knew about.
	 *
	 * @param placed  lit now
	 * @param retiring moved off, cleared one update later so the two never gap
	 */
	private record Lights(Set<BlockPos> placed, Set<BlockPos> retiring) {
		static final Lights NONE = new Lights(Set.of(), Set.of());

		/** Everything standing in the world, whichever half it is in. */
		Set<BlockPos> all() {
			Set<BlockPos> everywhere = new HashSet<>(placed);
			everywhere.addAll(retiring);
			return everywhere;
		}
	}

	// Volatile: read from the chunk-saving thread by getPlacedLightPositions, reassigned (never
	// mutated) here.
	private volatile Lights lights = Lights.NONE;
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
			BlockPos lightPos = BlockPos.containing(worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5);

			if (world.getBlockState(lightPos).isAir()) {
				BlockState lightBlock = Blocks.LIGHT.defaultBlockState()
					.setValue(LightBlock.LEVEL, source.lightLevel());
				world.setBlock(lightPos, lightBlock, Block.UPDATE_CLIENTS);
				newPositions.add(lightPos);
			}
		}
		lights = new Lights(Set.copyOf(newPositions), Set.of());

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
			BlockPos lightPos = BlockPos.containing(worldPos.x + 0.5, worldPos.y + 0.5, worldPos.z + 0.5);

			BlockState existing = world.getBlockState(lightPos);
			BlockState wanted = Blocks.LIGHT.defaultBlockState()
				.setValue(LightBlock.LEVEL, source.lightLevel());

			if (existing.isAir()) {
				world.setBlock(lightPos, wanted, Block.UPDATE_CLIENTS);
				newPositions.add(lightPos);
			} else if (lights.placed().contains(lightPos) && existing.is(Blocks.LIGHT)) {
				// A light that has not moved is already exactly right, and this is where the
				// flicker came from: it is not air, so it never made the new set, so the sweep
				// below took it out as though it had been left behind - and the next update put it
				// straight back. Every light aboard blinking on and off for as long as the ship
				// was under way, because standing still read as being gone.
				if (!existing.equals(wanted)) world.setBlock(lightPos, wanted, Block.UPDATE_CLIENTS);
				newPositions.add(lightPos);
			}
		}

		// Old lights are dropped one update LATE, not in the same breath as the new ones.
		//
		// This is where the flicker was. An update only happens because the ship moved, so every
		// light is always hopping to a new block, and taking the old one out in the same tick as
		// the new one goes in leaves the client relighting from scratch each time - which it does
		// visibly. Letting the previous position linger through one cycle means there is never an
		// instant when the ship is lit by fewer blocks than it should be, and the overlap costs a
		// single extra light block, one block behind, on a ship that is moving anyway.
		for (BlockPos pos : lights.retiring()) {
			if (newPositions.contains(pos)) continue;
			if (world.getBlockState(pos).is(Blocks.LIGHT)) {
				world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}

		Set<BlockPos> retiring = new HashSet<>(lights.placed());
		retiring.removeAll(newPositions);

		// Both sets in one store, because the saving thread reads them together: published
		// separately, a save landing between the two wrote the old placed set and the new
		// pending set, and every light in the gap was left burning with nothing recording it.
		lights = new Lights(Set.copyOf(newPositions), Set.copyOf(retiring));

		lastLightUpdatePos = pose.helmBlockPos();
		lastLightUpdateYaw = (int) Math.floor(Math.toDegrees(pose.yawRadians()) / 15) * 15;
	}

	/** On docking or removal, when no later update will come round to clear them. */
	public void remove(ServerLevel world) {
		// Both sets: a light the ship has moved off is still a light block standing in the world
		// until its cycle comes round, and docking is exactly when that cycle never arrives.
		Set<BlockPos> everywhere = lights.all();

		for (BlockPos pos : everywhere) {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() == Blocks.LIGHT) {
				world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		lights = Lights.NONE;
		lightSources.clear();
	}

	/** For the save, which is the only record a crash leaves of what is still lit. */
	public List<BlockPos> getPlacedLightPositions() {
		// Both sets, because this is what a restart uses to find and clear the ship's lights, and a
		// light waiting out its cycle is as real as any other.
		return List.copyOf(lights.all());
	}

	/** Crash recovery: lights that were saved but never taken out of the world. */
	public static void cleanupLightPositions(ServerLevel world, List<BlockPos> positions) {
		for (BlockPos pos : positions) {
			// Called while the ship is being loaded, so the chunks around it are mid-flight.
			// Reading an absent one loads it, which is chunk loading re-entered from inside
			// chunk loading; the ship asks again next tick through its own sweep.
			if (!world.isLoaded(pos)) continue;
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
