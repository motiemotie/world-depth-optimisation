package motie.worlddepthoptimisation.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import motie.worlddepthoptimisation.Basement;
import motie.worlddepthoptimisation.FloatingFloor;

/** Keeps structures above the floor. Only noise-based worlds have a floor to protect. */
@Mixin(ChunkGenerator.class)
public class ChunkGeneratorMixin {
	/**
	 * Vanilla picks the height of underground structures without knowing about the floor, so under high ground it puts them below
	 * the bedrock. Each such structure is moved up as a whole, keeping it intact. Surface structures sit on the ground, far above it.
	 *
	 * <p>The rise clears the lowest floor under the structure, which keeps it buried: clearing the highest would carry the downhill side
	 * out into the open on a steep mountain. Where the floor rises through the structure the bedrock wins, as the floor is repaired after
	 * structures are placed. A stronghold clears the highest floor instead, because its portal room must survive.
	 */
	@Inject(method = "createStructures", at = @At("TAIL"))
	private void worlddepthoptimisation$liftStructures(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk,
			StructureTemplateManager templates, ResourceKey<Level> level, CallbackInfo ci) {
		if (!FloatingFloor.appliesTo(chunk) || !((Object) this instanceof NoiseBasedChunkGenerator)) {
			return;
		}
		ChunkGenerator self = (ChunkGenerator) (Object) this;
		RandomState random = state.randomState();
		for (StructureStart start : List.copyOf(chunk.getAllStarts().values())) {
			Structure structure = start.getStructure();
			if (!start.isValid() || structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES) {
				continue;
			}
			BoundingBox box = start.getBoundingBox();
			int floor = structure.type() == StructureType.STRONGHOLD ? FloatingFloor.highestFloor(self, random, chunk, box) : FloatingFloor.lowestFloor(self, random, chunk, box);
			int lift = Basement.liftToClear(box.minY(), floor, chunk.getMinY());
			if (lift > 0) {
				for (StructurePiece piece : start.getPieces()) {
					piece.move(0, lift, 0);
				}
				// A new start, because the old one has cached its bounding box.
				chunk.setStartForStructure(structure, new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(start.getPieces())));
			}
		}
	}
}
