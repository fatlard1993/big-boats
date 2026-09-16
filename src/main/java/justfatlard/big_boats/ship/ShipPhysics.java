package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.core.Direction;

/**
 * Owns ship velocity state and physics operations.
 */
public class ShipPhysics {
	private double velocityX = 0;
	private double velocityZ = 0;

	/** Whether the throttle was forward this tick, for the drag that follows. */
	private boolean powered;

	/**
	 * Applies acceleration in the ship's forward/backward direction: briskly up to harbour speed,
	 * slowly beyond it, so speed is something a ship builds on open water.
	 *
	 * @param throttle Throttle input (-1 to 1, positive = forward, negative = reverse)
	 * @param helmFacing The direction the helm faces (determines forward)
	 * @param yawRadians Current ship rotation in radians
	 */
	public void applyAcceleration(float throttle, Direction helmFacing, float yawRadians) {
		powered = throttle > 0;
		if (throttle == 0) return;

		double headingX = headingX(helmFacing, yawRadians);
		double headingZ = headingZ(helmFacing, yawRadians);
		double ahead = velocityX * headingX + velocityZ * headingZ;
		double rate = throttle > 0 && ahead >= ShipConfig.HARBOUR_SPEED
			? ShipConfig.OPEN_WATER_ACCELERATION : ShipConfig.ACCELERATION;

		velocityX += headingX * throttle * rate;
		velocityZ += headingZ * throttle * rate;
	}

	/** Drag along the heading, lighter under throttle, and the keel's heavier drag across it. */
	public void applyDrag(Direction helmFacing, float yawRadians) {
		double headingX = headingX(helmFacing, yawRadians);
		double headingZ = headingZ(helmFacing, yawRadians);
		double ahead = velocityX * headingX + velocityZ * headingZ;
		double acrossX = velocityX - ahead * headingX;
		double acrossZ = velocityZ - ahead * headingZ;

		ahead *= powered ? ShipConfig.POWERED_DRAG : ShipConfig.DRAG;
		velocityX = ahead * headingX + acrossX * ShipConfig.KEEL;
		velocityZ = ahead * headingZ + acrossZ * ShipConfig.KEEL;
		powered = false;
	}

	/** Top speed ahead; astern, harbour speed, since reversing is for docking. */
	public void clampToMaxSpeed(Direction helmFacing, float yawRadians) {
		double ahead = velocityX * headingX(helmFacing, yawRadians) + velocityZ * headingZ(helmFacing, yawRadians);
		double cap = ahead < 0 ? ShipConfig.HARBOUR_SPEED : ShipConfig.MAX_SPEED;
		double currentSpeed = getSpeed();
		if (currentSpeed > cap) {
			double scale = cap / currentSpeed;
			velocityX *= scale;
			velocityZ *= scale;
		}
	}

	private static double headingX(Direction helmFacing, float yawRadians) {
		return -Math.sin(Math.toRadians(ShipBlockUtils.directionToYaw(helmFacing)) + yawRadians);
	}

	private static double headingZ(Direction helmFacing, float yawRadians) {
		return Math.cos(Math.toRadians(ShipBlockUtils.directionToYaw(helmFacing)) + yawRadians);
	}

	/**
	 * Stops the ship completely when moving very slowly; prevents endless tiny drifting.
	 */
	public void stopIfSlow() {
		if (getSpeed() < 0.001) {
			velocityX = 0;
			velocityZ = 0;
		}
	}

	public void reset() {
		velocityX = 0;
		velocityZ = 0;
	}

	public void stopX() {
		velocityX = 0;
	}

	public void stopZ() {
		velocityZ = 0;
	}

	public double getSpeed() {
		return Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
	}

	public double getVelocityX() {
		return velocityX;
	}

	public double getVelocityZ() {
		return velocityZ;
	}

}
