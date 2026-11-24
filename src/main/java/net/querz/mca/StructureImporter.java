package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Places a validated block-only structure into one in-memory region. */
public final class StructureImporter {
	private StructureImporter() {}

	/**
	 * Places a structure in one in-memory region. Before editing, it checks the full
	 * footprint against the region and build bounds. For each planned write it
	 * checks that the chunk is fully loaded and generated, that existing sections
	 * have usable block data, and that the position has no block entity. Missing
	 * sections can be created and omitted positions are left alone.
	 * <p>
	 * Validation failures leave the region unedited. Unexpected failures during
	 * placement have no rollback. Keep the region free of concurrent edits.
	 * The caller handles block registry compatibility and saves with {@link MCAUtil}.
	 * Placement uses normal block edits and preserves the destination DataVersion.
	 * @param structure a validated block-only structure
	 * @param region the fully loaded destination terrain region
	 * @param originX the world X coordinate of the structure origin
	 * @param originY the world Y coordinate of the structure origin
	 * @param originZ the world Z coordinate of the structure origin
	 * @param minY the inclusive minimum build Y of the destination dimension
	 * @param height the positive build height. The upper bound is {@code minY + height}, exclusive
	 * @param airMode whether to place or skip listed air states
	 * @throws IllegalArgumentException if the structure footprint, section Y range, or build bounds are invalid
	 * @throws IllegalStateException if a target chunk or section cannot be edited or a planned write overlaps a block entity
	 */
	public static void place(StructureNbt structure, MCAFile region, int originX, int originY, int originZ,
							 int minY, int height, AirMode airMode) {
		Objects.requireNonNull(structure, "structure");
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(airMode, "airMode");
		long endX = (long) originX + structure.getSizeX() - 1;
		long endY = (long) originY + structure.getSizeY() - 1;
		long endZ = (long) originZ + structure.getSizeZ() - 1;
		if (endX > Integer.MAX_VALUE || endZ > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Structure footprint exceeds the world X/Z coordinate range");
		}
		if (height <= 0 || originY < minY || endY >= (long) minY + height) {
			throw new IllegalArgumentException("Structure footprint is outside the destination build height");
		}
		if ((originY >> 4) < Byte.MIN_VALUE || (endY >> 4) > Byte.MAX_VALUE) {
			throw new IllegalArgumentException("Structure footprint exceeds the signed MCA section Y range");
		}
		region.checkBlockCoordinates(originX, originZ);
		region.checkBlockCoordinates((int) endX, (int) endZ);

		List<CompoundTag> palette = structure.getPalette();
		boolean[] skip = new boolean[palette.size()];
		if (airMode == AirMode.SKIP_EXPLICIT) {
			for (int i = 0; i < palette.size(); i++) {
				String name = palette.get(i).getString("Name");
				skip[i] = "minecraft:air".equals(name) || "minecraft:cave_air".equals(name)
						|| "minecraft:void_air".equals(name);
			}
		}

		Set<Section> checkedSections = new HashSet<>();
		for (StructureNbt.Block block : structure.getBlocks()) {
			if (skip[block.getStateIndex()]) {
				continue;
			}
			int x = originX + block.getX();
			int y = originY + block.getY();
			int z = originZ + block.getZ();
			Section section = region.requireChunk(x, z).checkStructureTarget(x, y, z);
			if (section != null && checkedSections.add(section)) {
				section.checkStructureEditable(x, y, z);
			}
		}
		for (StructureNbt.Block block : structure.getBlocks()) {
			int index = block.getStateIndex();
			if (!skip[index]) {
				region.setBlockStateAt(originX + block.getX(), originY + block.getY(), originZ + block.getZ(),
						palette.get(index), false);
			}
		}
	}
}
