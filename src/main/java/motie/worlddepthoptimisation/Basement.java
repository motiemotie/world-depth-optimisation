package motie.worlddepthoptimisation;

/**
 * The rules of the floating floor, free of Minecraft. The bedrock of a column runs from its skirt (the lowest floor among itself
 * and its four neighbours) up to its own floor, so the shell stays watertight where a tall column stands next to a low one.
 */
public final class Basement {
	/** Blocks from a column's surface down to its bedrock floor, as chosen in the config file. */
	public static final int MAX_DEPTH = WorldDepthConfig.load();

	/** Blocks of rock kept between the bedrock floor and the bottom of a structure that had to be lifted clear of it. */
	public static final int STRUCTURE_CLEARANCE = 3;

	private Basement() {}

	/** Highest bedrock block of a column with the given surface: the world bottom, or {@link #MAX_DEPTH} below the surface. */
	public static int floorY(int surfaceY, int minY) {
		return Math.max(minY, surfaceY - MAX_DEPTH);
	}

	/** Lowest bedrock block of a column: its floor, or lower if a neighbour's floor is lower. */
	public static int skirtY(int floor, int floorWest, int floorEast, int floorNorth, int floorSouth) {
		return Math.min(floor, Math.min(Math.min(floorWest, floorEast), Math.min(floorNorth, floorSouth)));
	}

	/**
	 * How far a buried structure must rise so its bottom sits above the given floor height: 0 when it is already clear. Nothing exists
	 * below the floor, so a structure placed there would hang in the void and break up through the bedrock.
	 */
	public static int liftToClear(int structureMinY, int floorY, int minY) {
		if (floorY <= minY) {
			return 0;
		}
		return Math.max(0, floorY + STRUCTURE_CLEARANCE + 1 - structureMinY);
	}
}
