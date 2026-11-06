package motie.worlddepthoptimisation.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;

import motie.worlddepthoptimisation.ChunkStageTimer;

/** Times each generation stage, for the benchmark. Works for any chunk generator. Costs nothing unless a benchmark is listening. */
@Mixin(ChunkStep.class)
public class ChunkStepMixin {
	@WrapMethod(method = "apply")
	private CompletableFuture<ChunkAccess> worlddepthoptimisation$time(WorldGenContext context, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
			Operation<CompletableFuture<ChunkAccess>> original) {
		ChunkStatus target = ((ChunkStep) (Object) this).targetStatus();
		// A chunk already past this stage only passes through.
		if (!ChunkStageTimer.listening || !chunk.getPersistedStatus().isBefore(target)) {
			return original.call(context, cache, chunk);
		}
		long start = System.nanoTime();
		return original.call(context, cache, chunk).whenComplete((done, failure) -> ChunkStageTimer.add(target.toString(), System.nanoTime() - start));
	}
}
