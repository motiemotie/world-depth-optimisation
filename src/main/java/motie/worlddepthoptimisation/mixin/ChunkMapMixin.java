package motie.worlddepthoptimisation.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import motie.worlddepthoptimisation.ChunkStageTimer;

/** Times chunks being read back from disk, for the benchmark. A chunk that has never been saved comes back empty, and is not counted. */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {
	@WrapMethod(method = "scheduleChunkLoad")
	private CompletableFuture<ChunkAccess> worlddepthoptimisation$timeLoad(ChunkPos pos, Operation<CompletableFuture<ChunkAccess>> original) {
		if (!ChunkStageTimer.listening) {
			return original.call(pos);
		}
		long start = System.nanoTime();
		return original.call(pos).whenComplete((chunk, failure) -> {
			if (chunk != null && chunk.getPersistedStatus() != ChunkStatus.EMPTY) {
				ChunkStageTimer.add(ChunkStageTimer.DISK_LOAD, System.nanoTime() - start);
			}
		});
	}
}
