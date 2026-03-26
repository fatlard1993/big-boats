package justfatlard.big_boats.ship;

import justfatlard.big_boats.util.ShipBlockUtils;
import net.minecraft.util.math.Direction;

/**
 * Handles ship velocity and physics calculations.
 * Owns velocity state and provides physics operations.
 */
public class ShipPhysics {
	private double velocityX = 0;
	private double velocityZ = 0;

	/**
	 * Applies acceleration in the ship's forward/backward direction.
	 *
	 * @param throttle Throttle input (-1 to 1, positive = forward, negative = reverse)
	 * @param helmFacing The direction the helm faces (determines forward)
	 * @param yawRadians Current ship rotation in radians
	 */
	public void applyAcceleration(float throttle, Direction helmFacing, float yawRadians) {
		if (throttle == 0) return;

		float baseYawRadians = (float) Math.toRadians(ShipBlockUtils.directionToYaw(helmFacing));
		float totalYawRadians = baseYawRadians + yawRadians;

		velocityX += -Math.sin(totalYawRadians) * throttle * ShipConfig.ACCELERATION;
		velocityZ += Math.cos(totalYawRadians) * throttle * ShipConfig.ACCELERATION;
	}

	public void applyDrag() {
		velocityX *= ShipConfig.DRAG;
		velocityZ *= ShipConfig.DRAG;
	}

	public void clampToMaxSpeed() {
		double currentSpeed = getSpeed();
		if (currentSpeed > ShipConfig.MAX_SPEED) {
			double scale = ShipConfig.MAX_SPEED / currentSpeed;
			velocityX *= scale;
			velocityZ *= scale;
		}
	}

	/**
	 * Stops the ship completely if moving very slowly.
	 * Prevents endless tiny drifting.
	 */
	public void stopIfSlow() {
		if (getSpeed() < 0.001) {
			velocityX = 0;
			velocityZ = 0;
		}
	}

	/**
	 * Resets velocity to zero (e.g., when docking).
	 */
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
