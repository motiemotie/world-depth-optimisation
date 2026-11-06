package motie.worlddepthoptimisation;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Bedrock that rides up under high ground, for any noise-based world. The vanilla generator builds its terrain as usual, whatever
 * datapack shaped it, and this then removes everything below the floor. So it costs generation a little, and saves memory and
 * rendering afterwards.
 *
 * <p>The floor follows a smoothed surface: read on a coarse grid, each point averaged with its neighbours, and curved between them,
 * so it rolls gently instead of repeating every crag above. A chunk can therefore work out its neighbours' floors without those
 * chunks existing, so the answer never depends on which chunk generated first. Because it is smoothed, rock under a peak can be
 * somewhat thicker than the set depth.
 *
 * <p>A world of vanilla height is never touched. In a taller one, the floor stays at the bottom until the surface is high enough to lift it.
 */
public final class FloatingFloor {
	/** Whether new chunks get the floor. Start with -Dworlddepthoptimisation.enabled=false to begin with it off. */
	public static volatile boolean enabled = !"false".equals(System.getProperty("worlddepthoptimisation.enabled"));

	/** Rock always left between a column's bedrock and its surface, even where the surface falls away faster than the floor. */
	private static final int MIN_THICKNESS = 16;
	private static final int SURFACE_CACHE_LIMIT = 200_000;
	private static final int STATE_FLAGS = 0;

	/** Worlds this tall or shorter are left completely alone: the floor exists to support mods and datapacks that make worlds taller. */
	private static final int VANILLA_HEIGHT = 384;

	/** Spacing of the grid the surface is read on, in blocks. */
	private static final int GRID = 64;

	private record Surfaces(ConcurrentHashMap<Long, Integer> raw, ConcurrentHashMap<Long, Double> smooth) {}

	private static final Map<RandomState, Surfaces> SURFACES = Collections.synchronizedMap(new WeakHashMap<>());

	private FloatingFloor() {}

	/** Whether the floor applies in a world of this size. */
	public static boolean appliesTo(LevelHeightAccessor level) {
		return enabled && level.getHeight() > VANILLA_HEIGHT;
	}

	/** The surface at a grid point. */
	private static int rawSurface(ChunkGenerator generator, RandomState random, LevelHeightAccessor level, Surfaces cache, int gridX, int gridZ) {
		long key = ChunkPos.pack(gridX, gridZ);
		Integer known = cache.raw().get(key);
		if (known != null) {
			return known;
		}
		int surface = generator.getBaseHeight(gridX * GRID, gridZ * GRID, Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1;
		if (cache.raw().size() > SURFACE_CACHE_LIMIT) {
			cache.raw().clear();
		}
		cache.raw().put(key, surface);
		return surface;
	}

	/** The surface at a grid point averaged with its eight neighbours, the nearer ones counting for more. */
	private static double smoothSurface(ChunkGenerator generator, RandomState random, LevelHeightAccessor level, Surfaces cache, int gridX, int gridZ) {
		long key = ChunkPos.pack(gridX, gridZ);
		Double known = cache.smooth().get(key);
		if (known != null) {
			return known;
		}
		double sum = 0.0;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				sum += (2 - Math.abs(dx)) * (2 - Math.abs(dz)) * rawSurface(generator, random, level, cache, gridX + dx, gridZ + dz);
			}
		}
		double smooth = sum / 16.0;
		if (cache.smooth().size() > SURFACE_CACHE_LIMIT) {
			cache.smooth().clear();
		}
		cache.smooth().put(key, smooth);
		return smooth;
	}

	private static double ease(double t) {
		return t * t * (3.0 - 2.0 * t);
	}

	/** The floor of a column, before allowing for the column's own surface. */
	public static int floorAt(ChunkGenerator generator, RandomState random, LevelHeightAccessor level, int x, int z) {
		Surfaces cache = SURFACES.computeIfAbsent(random, k -> new Surfaces(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
		int gridX = Math.floorDiv(x, GRID);
		int gridZ = Math.floorDiv(z, GRID);
		double fx = ease((x - gridX * GRID) / (double) GRID);
		double fz = ease((z - gridZ * GRID) / (double) GRID);
		double nw = smoothSurface(generator, random, level, cache, gridX, gridZ);
		double ne = smoothSurface(generator, random, level, cache, gridX + 1, gridZ);
		double sw = smoothSurface(generator, random, level, cache, gridX, gridZ + 1);
		double se = smoothSurface(generator, random, level, cache, gridX + 1, gridZ + 1);
		double surface = (nw + (ne - nw) * fx) * (1.0 - fz) + (sw + (se - sw) * fx) * fz;
		return Basement.floorY((int) Math.floor(surface), level.getMinY());
	}

	/** Removes everything below the floor and lays the bedrock. Runs right after terrain, when nothing else is using the chunk. */
	public static void carve(ChunkGenerator generator, RandomState random, ChunkAccess chunk) {
		apply(generator, random, chunk, true);
	}

	/**
	 * Restores the shell after all features and structures that can reach this chunk have been placed: anything below the floor is
	 * removed and any hole through it is closed. Deep vanilla features such as geodes use fixed heights and need this.
	 */
	public static void reseal(ChunkGenerator generator, RandomState random, ChunkAccess chunk) {
		apply(generator, random, chunk, false);
	}

	private static void apply(ChunkGenerator generator, RandomState random, ChunkAccess chunk, boolean fresh) {
		if (!appliesTo(chunk)) {
			return;
		}
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		// One column of margin all round, for the skirts.
		int[] floors = new int[18 * 18];
		boolean raised = false;
		for (int dz = -1; dz <= 16; dz++) {
			for (int dx = -1; dx <= 16; dx++) {
				int floor = floorAt(generator, random, chunk, baseX + dx, baseZ + dz);
				floors[(dz + 1) * 18 + dx + 1] = floor;
				raised |= floor > minY;
			}
		}
		if (!raised) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int at = (z + 1) * 18 + x + 1;
				int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
				int floor = Math.max(minY, Math.min(floors[at], surface - MIN_THICKNESS));
				int skirt = Basement.skirtY(floor, floors[at - 1], floors[at + 1], floors[at - 18], floors[at + 18]);
				if (floor <= minY && skirt <= minY) {
					continue;
				}
				int wx = baseX + x;
				int wz = baseZ + z;
				for (int y = minY; y <= floor; y++) {
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
					if (y < skirt && section.hasOnlyAir()) {
						y = Math.min(y | 15, skirt - 1); // to the top of this section, but never past where the bedrock starts
						continue;
					}
					BlockState wanted = y < skirt ? air : bedrock;
					if (fresh) {
						// Directly into the section: lower than the surface, and lit later, so nothing else needs telling.
						section.setBlockState(x, y & 15, z, wanted, false);
					} else {
						BlockState current = chunk.getBlockState(pos.set(wx, y, wz));
						if (current != wanted) {
							if (current.hasBlockEntity()) {
								chunk.removeBlockEntity(pos);
							}
							chunk.setBlockState(pos, wanted, STATE_FLAGS);
						}
					}
				}
			}
		}
	}

	/** The highest floor under a box, sampled every 16 blocks and along its far edges. */
	public static int highestFloor(ChunkGenerator generator, RandomState random, LevelHeightAccessor level, BoundingBox box) {
		return floorUnder(generator, random, level, box, true);
	}

	/** The lowest floor under a box, sampled every 16 blocks and along its far edges. */
	public static int lowestFloor(ChunkGenerator generator, RandomState random, LevelHeightAccessor level, BoundingBox box) {
		return floorUnder(generator, random, level, box, false);
	}

	private static int floorUnder(ChunkGenerator generator, RandomState random, LevelHeightAccessor level, BoundingBox box, boolean highest) {
		int found = highest ? Integer.MIN_VALUE : Integer.MAX_VALUE;
		for (int x = box.minX(); ; x = Math.min(x + 16, box.maxX())) {
			for (int z = box.minZ(); ; z = Math.min(z + 16, box.maxZ())) {
				int floor = floorAt(generator, random, level, x, z);
				found = highest ? Math.max(found, floor) : Math.min(found, floor);
				if (z == box.maxZ()) {
					break;
				}
			}
			if (x == box.maxX()) {
				return found;
			}
		}
	}
}
