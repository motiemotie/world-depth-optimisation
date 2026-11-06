package motie.worlddepthoptimisation;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's chosen world depth, read once from config/worlddepthoptimisation.properties when the game starts. The file is created
 * with its explanation if missing. A bad value never stops the game: it is corrected, with a warning in the log.
 */
public final class WorldDepthConfig {
	public static final int DEFAULT_DEPTH = 352;
	public static final int STEP = 16;
	public static final int MIN_DEPTH = 16;
	/** The deepest any Minecraft world can be. */
	public static final int MAX_DEPTH = 4064;

	private static final Logger LOGGER = LoggerFactory.getLogger("worlddepthoptimisation");
	private static final String FILE_NAME = "worlddepthoptimisation.properties";
	private static final String KEY = "world_depth";
	private static final String DEFAULT_FILE = """
			# World Depth Optimisation
			#
			# world_depth: how many blocks below the surface the bedrock floor sits, wherever the ground is high enough for the floor
			# to rise. Everything below the floor is removed. It must be a multiple of 16, from 16 to 4064. Default: 352
			# This only affects worlds that another mod or datapack has made taller than vanilla's 384 blocks.
			#
			# WARNING: Low depths may create structure generation and other unforeseen errors.
			# WARNING: High depths may create performance issues.
			#
			# Restart the game after changing this. Only newly generated chunks use the new value, so changing it in an
			# existing world can leave a step in the bedrock where new chunks meet old ones.
			world_depth=352
			""";

	private WorldDepthConfig() {}

	/** The depth to use. A test may override it with -Dworlddepthoptimisation.maxdepth=&lt;blocks&gt;, which skips these rules. */
	static int load() {
		Integer override = Integer.getInteger("worlddepthoptimisation.maxdepth");
		if (override != null) {
			LOGGER.info("World depth is {}, set for this run only by a system property", override);
			return override;
		}
		Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		int depth = read(file);
		LOGGER.info("World depth is {}", depth);
		return depth;
	}

	private static int read(Path file) {
		if (!Files.exists(file)) {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULT_FILE);
			} catch (IOException e) {
				LOGGER.warn("Could not create {}: {}. Using a world depth of {}", file, e.getMessage(), DEFAULT_DEPTH);
			}
			return DEFAULT_DEPTH;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			properties.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			LOGGER.warn("Could not read {}: {}. Using a world depth of {}", file, e.getMessage(), DEFAULT_DEPTH);
			return DEFAULT_DEPTH;
		}
		String text = properties.getProperty(KEY);
		if (text == null) {
			LOGGER.warn("{} has no {} setting. Using a world depth of {}", file, KEY, DEFAULT_DEPTH);
			return DEFAULT_DEPTH;
		}
		int value;
		try {
			value = Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			LOGGER.warn("{} in {} is not a whole number: {}. Using a world depth of {}", KEY, file, text.trim(), DEFAULT_DEPTH);
			return DEFAULT_DEPTH;
		}
		return validated(value);
	}

	/** The nearest allowed depth at or below the value, within the allowed range. */
	static int validated(int value) {
		int depth = Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, Math.floorDiv(value, STEP) * STEP));
		if (depth != value) {
			LOGGER.warn("{} must be a multiple of {} from {} to {}, but is {}. Using {}", KEY, STEP, MIN_DEPTH, MAX_DEPTH, value, depth);
		}
		return depth;
	}
}
