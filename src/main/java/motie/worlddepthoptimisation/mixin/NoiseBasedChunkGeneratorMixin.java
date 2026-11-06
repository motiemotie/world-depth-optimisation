package motie.worlddepthoptimisation.mixin;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import motie.worlddepthoptimisation.FloatingFloor;

/** Lays the floor once the vanilla generator has finished a chunk's terrain, and repairs it once no more features can reach the chunk. */
@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {
	@Inject(method = "buildTerrain", at = @At("RETURN"), cancellable = true)
	private void worlddepthoptimisation$carve(ChunkAccess chunk, Blender blender, RandomState random, StructureManager structures, BiomeManager biomes,
			WorldGenRegion region, Set<Holder<Biome>> possible, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (FloatingFloor.enabled) {
			ChunkGenerator self = (ChunkGenerator) (Object) this;
			cir.setReturnValue(cir.getReturnValue().thenApply(done -> {
				FloatingFloor.carve(self, random, done);
				return done;
			}));
		}
	}

	/**
	 * Features write into neighbouring chunks too, so a chunk can be disturbed after its own features are done. By this stage all
	 * its neighbours have finished theirs, so nothing can disturb it again.
	 */
	@Inject(method = "spawnOriginalMobs", at = @At("HEAD"))
	private void worlddepthoptimisation$reseal(WorldGenRegion region, CallbackInfo ci) {
		if (FloatingFloor.enabled) {
			ChunkPos center = region.getCenter();
			FloatingFloor.reseal((ChunkGenerator) (Object) this, region.getLevel().getChunkSource().randomState(), region.getChunk(center.x(), center.z()));
		}
	}
}
