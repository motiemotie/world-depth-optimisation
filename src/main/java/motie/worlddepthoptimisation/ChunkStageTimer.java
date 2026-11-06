package motie.worlddepthoptimisation;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Time spent in each stage of chunk generation, while a benchmark is listening. This is from a stage starting to its result being
 * ready, so a stage that hands work to another thread, as lighting does, also counts its wait for that thread.
 */
public final class ChunkStageTimer {
	public record Stage(long count, long nanos) {
		public double meanMs() {
			return count == 0L ? 0.0 : nanos / 1e6 / count;
		}
	}

	private record Tally(LongAdder count, LongAdder nanos) {}

	public static volatile boolean listening;

	/** Reading a saved chunk back from disk: recorded with the stages, but not one of them. */
	public static final String DISK_LOAD = "disk_load";

	private static final ConcurrentHashMap<String, Tally> TALLIES = new ConcurrentHashMap<>();

	private ChunkStageTimer() {}

	public static void add(String stage, long nanos) {
		Tally tally = TALLIES.computeIfAbsent(stage, k -> new Tally(new LongAdder(), new LongAdder()));
		tally.count().increment();
		tally.nanos().add(nanos);
	}

	public static void reset() {
		TALLIES.clear();
	}

	public static Map<String, Stage> snapshot() {
		Map<String, Stage> out = new TreeMap<>();
		TALLIES.forEach((stage, tally) -> out.put(stage, new Stage(tally.count().sum(), tally.nanos().sum())));
		return out;
	}
}
