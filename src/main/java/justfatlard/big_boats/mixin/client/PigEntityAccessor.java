package justfatlard.big_boats.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.passive.PigEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to read PigEntity's boost time tracked data.
 * We repurpose this field to transmit ship block count to clients.
 */
@Environment(EnvType.CLIENT)
@Mixin(PigEntity.class)
public interface PigEntityAccessor {
	@Accessor("BOOST_TIME")
	static TrackedData<Integer> getBoostTimeData() {
		throw new AssertionError();
	}
}
