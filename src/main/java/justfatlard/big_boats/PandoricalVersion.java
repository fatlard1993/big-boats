package justfatlard.big_boats;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * What the Pandorical running beside this can do. fabric.mod.json lets this load with an older one
 * than its newest features need, so those are used only where the running one has them.
 */
public final class PandoricalVersion {
	private PandoricalVersion() {}

	public static boolean atLeast(String version) {
		return FabricLoader.getInstance().getModContainer("pandorical").map(mod -> {
			try {
				return mod.getMetadata().getVersion().compareTo(Version.parse(version)) >= 0;
			} catch (VersionParsingException e) {
				return false;
			}
		}).orElse(false);
	}
}
