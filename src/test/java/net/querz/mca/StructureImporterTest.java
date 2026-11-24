package net.querz.mca;

import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static net.querz.mca.RegionTestSupport.*;
import static org.junit.Assert.*;

public class StructureImporterTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void readsDuplicatePaletteValuesAndReturnsDetachedStateTags() throws Exception {
		CompoundTag log = stateWithProperty("minecraft:jungle_log", "axis", "y");
		CompoundTag root = structure(2, 3, 1, new CompoundTag[]{log, log}, block(1, 2, 0, 1), block(1, 1, 0, 0));
		root.putString("author", "test");
		root.put("entities", ListTag.createUnchecked(null));
		StructureNbt parsed = read(root);

		assertEquals(3463, parsed.getDataVersion());
		assertEquals("test", parsed.getAuthor());
		assertEquals(2, parsed.getSizeX());
		assertEquals(3, parsed.getSizeY());
		assertEquals(1, parsed.getSizeZ());
		List<CompoundTag> palette = parsed.getPalette();
		assertEquals(2, palette.size());
		assertEquals(palette.get(0), palette.get(1));
		palette.get(0).getCompoundTag("Properties").putString("axis", "z");
		assertEquals(log, parsed.getPalette().get(0));
		StructureNbt.Block first = parsed.getBlocks().get(0);
		assertEquals(1, first.getX());
		assertEquals(2, first.getY());
		assertEquals(0, first.getZ());
		assertEquals(1, first.getStateIndex());
		try {
			parsed.getBlocks().clear();
			fail("Block list must be immutable");
		} catch (UnsupportedOperationException expected) {
			assertEquals(2, parsed.getBlocks().size());
		}
	}

	@Test
	public void rejectsWrongRequiredTagTypesAndInvalidSizes() throws Exception {
		CompoundTag root = line(block(0, 0, 0, 0));
		root.putShort("DataVersion", (short) 3463);
		assertInvalid(root, "$.DataVersion");
		root.putInt("DataVersion", 3463);
		root.putIntArray("size", new int[]{2, 1, 1});
		assertInvalid(root, "$.size");
		root.put("size", coordinates(2, 0, 1));
		assertInvalid(root, "$.size");
		root.put("size", coordinates(2, 1, 1));
		root.put("palette", new ListTag<>(CompoundTag.class));
		assertInvalid(root, "$.palette");
	}

	@Test
	public void rejectsMalformedPaletteNameAndPropertyTypes() throws Exception {
		CompoundTag blockState = defaultState("minecraft:stone");
		CompoundTag root = structure(1, 1, 1, new CompoundTag[]{blockState}, block(0, 0, 0, 0));
		blockState.putInt("Name", 0);
		assertInvalid(root, "$.palette[0].Name");
		blockState.putString("Name", "minecraft:stone[invalid=true]");
		assertInvalid(root, "$.palette[0].Name");
		blockState.putString("Name", "minecraft:stone");
		blockState.putString("Properties", "invalid");
		assertInvalid(root, "$.palette[0].Properties");
		CompoundTag properties = new CompoundTag();
		properties.putBoolean("waterlogged", false);
		blockState.put("Properties", properties);
		assertInvalid(root, "$.palette[0].Properties.waterlogged");
	}

	@Test
	public void rejectsInvalidLateEntriesInsteadOfDefaultingMissingStateToZero() throws Exception {
		CompoundTag late = block(1, 0, 0, 1);
		CompoundTag root = line(block(0, 0, 0, 0), late);
		assertInvalid(root, "$.blocks[1].state");
		late.remove("state");
		assertInvalid(root, "$.blocks[1].state");
		late.putShort("state", (short) 0);
		assertInvalid(root, "$.blocks[1].state");
		late.putInt("state", 0);
		late.putIntArray("pos", new int[]{1, 0, 0});
		assertInvalid(root, "$.blocks[1].pos");
		late.put("pos", coordinates(2, 0, 0));
		assertInvalid(root, "$.blocks[1].pos");
		late.put("pos", coordinates(0, 0, 0));
		assertInvalid(root, "duplicates a block position");
	}

	@Test
	public void rejectsUnsupportedStructurePayloads() throws Exception {
		CompoundTag root = line(block(0, 0, 0, 0));
		root.put("palettes", new ListTag<>(CompoundTag.class));
		assertInvalid(root, "$.palettes");
		root.remove("palettes");
		ListTag<CompoundTag> entities = new ListTag<>(CompoundTag.class);
		entities.add(new CompoundTag());
		root.put("entities", entities);
		assertInvalid(root, "$.entities");
		root.remove("entities");
		root.getListTag("blocks").asCompoundTagList().get(0).put("nbt", new CompoundTag());
		assertInvalid(root, "$.blocks[0].nbt");
		root.putString("unknown", "value");
		assertInvalid(root, "$.unknown");
	}

	@Test
	public void rejectsTruncatedGzipAfterTheNbtRoot() throws Exception {
		Path path = writeStructure(line(block(0, 0, 0, 0)));
		byte[] gzip = Files.readAllBytes(path);
		try (OutputStream out = Files.newOutputStream(path)) {
			out.write(gzip, 0, gzip.length - 4);
		}
		try {
			StructureNbt.read(path);
			fail("Expected the missing gzip trailer to be rejected");
		} catch (IOException expected) {
			// The root is complete, but the compressed file is incomplete.
		}
	}

	@Test
	public void placesExplicitAirOrSkipsItWhileLeavingOmittedPositionsAlone() throws Exception {
		StructureNbt parsed = read(structure(4, 1, 1, new CompoundTag[]{
				defaultState("air"), defaultState("minecraft:cave_air"), defaultState("minecraft:void_air")},
				block(0, 0, 0, 0), block(1, 0, 0, 1), block(2, 0, 0, 2)));
		MCAFile region = MCAUtil.read(fixture("overworld/r.0.0.mca"));
		StructureImporter.place(parsed, region, 3, -64, 3, -64, 384, AirMode.SKIP_EXPLICIT);
		for (int x = 3; x <= 6; x++) {
			assertBlockState(region, x, -64, 3, defaultState("minecraft:bedrock"));
		}
		StructureImporter.place(parsed, region, 3, -64, 3, -64, 384, AirMode.PLACE_EXPLICIT);
		assertBlockState(region, 3, -64, 3, defaultState("minecraft:air"));
		assertBlockState(region, 4, -64, 3, defaultState("minecraft:cave_air"));
		assertBlockState(region, 5, -64, 3, defaultState("minecraft:void_air"));
		assertBlockState(region, 6, -64, 3, defaultState("minecraft:bedrock"));
	}

	@Test
	public void rejectsLateMissingChunkWithoutChangingEarlierTargets() throws Exception {
		MCAFile region = MCAUtil.read(fixture("overworld/r.0.0.mca"));
		StructureNbt parsed = read(line(block(0, 0, 0, 0), block(1, 0, 0, 0)));
		assertUnchanged(region, "missing chunk",
				() -> StructureImporter.place(parsed, region, 15, -64, 3, -64, 384, AirMode.PLACE_EXPLICIT));
		assertNull(region.getChunk(1, 0));
	}

	@Test
	public void skippedAirDoesNotRequireAnExistingChunk() throws Exception {
		MCAFile region = MCAUtil.read(fixture("overworld/r.0.0.mca"));
		CompoundTag diamond = defaultState("minecraft:diamond_block");
		StructureNbt parsed = read(structure(3, 1, 1, new CompoundTag[]{diamond, defaultState("minecraft:air")},
				block(0, 0, 0, 0), block(1, 0, 0, 1)));
		StructureImporter.place(parsed, region, 15, -64, 3, -64, 384, AirMode.SKIP_EXPLICIT);
		assertBlockState(region, 15, -64, 3, diamond);
		assertNull(region.getChunk(1, 0));
	}

	@Test
	public void rejectsLateBlockEntityOverlapWithoutChangingEarlierTargets() throws Exception {
		MCAFile region = MCAUtil.read(fixture("overworld/r.0.0.mca"));
		StructureNbt parsed = read(line(block(0, 0, 0, 0), block(1, 0, 0, 0)));
		assertUnchanged(region, "block entity at (178, -56, 264)",
				() -> StructureImporter.place(parsed, region, 177, -56, 264, -64, 384, AirMode.PLACE_EXPLICIT));
	}

	@Test
	public void preservesBlockEntityNbtWhenEditingAnAdjacentBlock() throws Exception {
		File input = fixture("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		StructureNbt parsed = read(line(block(0, 0, 0, 0)));
		StructureImporter.place(parsed, region, 177, -56, 264, -64, 384, AirMode.PLACE_EXPLICIT);
		File output = save(region, "r.0.0.mca");
		assertBlockState(MCAUtil.read(output), 177, -56, 264, defaultState("minecraft:diamond_block"));
		CompoundTag original = readChunk(input, 11, 16);
		CompoundTag saved = readChunk(output, 11, 16);
		assertEquals(1, original.getListTag("block_entities").size());
		sectionAt(original, -4).remove("block_states");
		sectionAt(saved, -4).remove("block_states");
		assertHeightmapsInvalidated(original, saved);
		assertEquals(original, saved);
		assertUnrelatedChunksUnchanged(input, output, 11, 16);
	}

	@Test
	public void rejectsNoneditableSectionsAndLoadModesBeforePlacement() throws Exception {
		File input = fixture("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		CompoundTag chunk = readChunk(input, 0, 0);
		sectionAt(chunk, -3).remove("block_states");
		region.setChunk(0, 0, new Chunk(chunk));
		StructureNbt parsed = read(structure(1, 2, 1, new CompoundTag[]{defaultState("minecraft:diamond_block")},
				block(0, 0, 0, 0), block(0, 1, 0, 0)));
		assertUnchanged(region, "no block state palette",
				() -> StructureImporter.place(parsed, region, 3, -49, 3, -64, 384, AirMode.PLACE_EXPLICIT));
		for (long flags : new long[]{LoadFlags.RAW, LoadFlags.BLOCK_STATES}) {
			MCAFile incomplete = MCAUtil.read(input, flags);
			assertRejected("fully loaded, generated terrain",
					() -> StructureImporter.place(parsed, incomplete, 3, -49, 3, -64, 384, AirMode.PLACE_EXPLICIT));
		}
	}

	@Test
	public void validatesUntargetedPaletteIndicesInTouchedSectionsBeforeEditing() throws Exception {
		File input = fixture("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		CompoundTag chunk = readChunk(input, 0, 0);
		long[] indices = new long[256];
		indices[255] = 1;
		sectionAt(chunk, -3).getCompoundTag("block_states").putLongArray("data", indices);
		region.setChunk(0, 0, new Chunk(chunk));
		StructureNbt parsed = read(structure(1, 2, 1, new CompoundTag[]{defaultState("minecraft:diamond_block")},
				block(0, 0, 0, 0), block(0, 1, 0, 0)));
		assertUnchanged(region, "Invalid block state index",
				() -> StructureImporter.place(parsed, region, 3, -49, 3, -64, 384, AirMode.PLACE_EXPLICIT));
	}

	@Test
	public void checksConfiguredBuildHeightRegionBoundsAndOverflow() throws Exception {
		MCAFile region = MCAUtil.read(fixture("nether/r.0.0.mca"));
		StructureNbt parsed = read(line(block(0, 0, 0, 0)));
		assertUnchanged(region, "destination build height",
				() -> StructureImporter.place(parsed, region, 120, -1, 24, 0, 256, AirMode.PLACE_EXPLICIT));
		assertUnchanged(region, "destination build height",
				() -> StructureImporter.place(parsed, region, 120, 256, 24, 0, 256, AirMode.PLACE_EXPLICIT));
		assertUnchanged(region, "section Y range",
				() -> StructureImporter.place(parsed, region, 120, 2048, 24, 0, 3000, AirMode.PLACE_EXPLICIT));
		assertUnchanged(region, "outside region",
				() -> StructureImporter.place(parsed, region, 511, 255, 24, 0, 256, AirMode.PLACE_EXPLICIT));
		assertUnchanged(region, "world X/Z coordinate range",
				() -> StructureImporter.place(parsed, region, Integer.MAX_VALUE, 255, 24, 0, 256, AirMode.PLACE_EXPLICIT));
		StructureImporter.place(parsed, region, 120, 255, 24, 0, 256, AirMode.PLACE_EXPLICIT);
		assertBlockState(region, 120, 255, 24, defaultState("minecraft:diamond_block"));
	}

	@Test
	public void crossesNegativeChunkAndSectionBoundariesAndPreservesSerializedData() throws Exception {
		File fixture = fixture("overworld/r.0.0.mca");
		MCAFile region = new MCAFile(-1, -1);
		region.setChunk(0, 0, MCAUtil.read(fixture).getChunk(0, 0));
		CompoundTag next = readChunk(fixture, 0, 0);
		removeSection(next, -3);
		region.setChunk(1, 0, new Chunk(next));
		region.setChunk(11, 13, MCAUtil.read(fixture).getChunk(11, 13));
		File input = save(region, "r.-1.-1.mca");
		MCAFile destination = MCAUtil.read(input);

		CompoundTag log = stateWithProperty("minecraft:jungle_log", "axis", "x");
		CompoundTag diamond = defaultState("minecraft:diamond_block");
		StructureNbt parsed = read(structure(2, 2, 1, new CompoundTag[]{log, diamond, log},
				block(0, 0, 0, 0), block(1, 0, 0, 1), block(1, 1, 0, 2)));
		assertNull(destination.getBlockStateAt(-496, -48, -512));
		StructureImporter.place(parsed, destination, -497, -49, -512, -64, 384, AirMode.PLACE_EXPLICIT);
		File output = save(destination, "r.-1.-1.mca");
		MCAFile reopened = MCAUtil.read(output);
		assertBlockState(reopened, -497, -49, -512, log);
		assertBlockState(reopened, -496, -49, -512, diamond);
		assertBlockState(reopened, -496, -48, -512, log);
		assertBlockState(reopened, -495, -48, -512, defaultState("minecraft:air"));

		CompoundTag serialized = readChunk(output, 0, 0);
		CompoundTag states = sectionAt(serialized, -4).getCompoundTag("block_states");
		int editedIndex = 15 * 256 + 15;
		CompoundTag state = states.getListTag("palette").asCompoundTagList().get(unpackIndices(states, 4)[editedIndex]);
		assertEquals("minecraft:jungle_log", state.getString("id"));
		assertEquals(log.getCompoundTag("Properties"), state.getCompoundTag("properties"));
		CompoundTag newStates = sectionAt(readChunk(output, 1, 0), -3).getCompoundTag("block_states");
		assertEquals(wrappedState("minecraft:air"), newStates.getListTag("palette").get(0));
		int[] newIndices = new int[4096];
		newIndices[0] = 1;
		assertArrayEquals(newIndices, unpackIndices(newStates, 4));

		MCAFile before = MCAUtil.read(input);
		for (int y = -64; y < -48; y++) {
			for (int z = -512; z < -496; z++) {
				for (int x = -512; x < -480; x++) {
					if (y != -49 || z != -512 || (x != -497 && x != -496)) {
						assertBlockState(reopened, x, y, z, before.getBlockStateAt(x, y, z));
					}
				}
			}
		}
		assertEquals(readChunk(input, 11, 13), readChunk(output, 11, 13));
		for (int x = 0; x <= 1; x++) {
			CompoundTag original = readChunk(input, x, 0);
			CompoundTag saved = readChunk(output, x, 0);
			assertEquals(5023, saved.getInt("DataVersion"));
			sectionAt(original, -4).remove("block_states");
			sectionAt(saved, -4).remove("block_states");
			if (x == 1) {
				removeSection(saved, -3);
			}
			assertHeightmapsInvalidated(original, saved);
			assertEquals(original, saved);
		}
		assertArrayEquals(regionTimestamps(input), regionTimestamps(output));
	}

	private StructureNbt read(CompoundTag root) throws IOException {
		return StructureNbt.read(writeStructure(root));
	}

	private Path writeStructure(CompoundTag root) throws IOException {
		Path path = temp.newFile().toPath();
		try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
			new NBTSerializer(false).toStream(new NamedTag("", root), out);
		}
		return path;
	}

	private void assertInvalid(CompoundTag root, String detail) throws IOException {
		try {
			read(root);
			fail("Expected structure read to fail");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(detail));
		}
	}

	private void assertUnchanged(MCAFile region, String detail, Runnable write) throws IOException {
		File before = save(region, "r.0.0.mca");
		assertRejected(detail, write);
		File after = save(region, "r.0.0.mca");
		assertArrayEquals(Files.readAllBytes(before.toPath()), Files.readAllBytes(after.toPath()));
	}

	private static void assertRejected(String detail, Runnable write) {
		try {
			write.run();
			fail("Expected placement to fail before editing");
		} catch (IllegalArgumentException | IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(detail));
		}
	}

	private File save(MCAFile region, String name) throws IOException {
		File output = new File(temp.newFolder(), name);
		MCAUtil.write(region, output);
		return output;
	}

	private static File fixture(String name) throws Exception {
		return Paths.get(StructureImporterTest.class.getResource("palettes/" + name).toURI()).toFile();
	}

	private static CompoundTag line(CompoundTag... blocks) {
		return structure(2, 1, 1, new CompoundTag[]{defaultState("minecraft:diamond_block")}, blocks);
	}

	private static CompoundTag structure(int x, int y, int z, CompoundTag[] states, CompoundTag... entries) {
		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", 3463);
		root.put("size", coordinates(x, y, z));
		ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
		for (CompoundTag state : states) {
			palette.add(state);
		}
		root.put("palette", palette);
		ListTag<CompoundTag> blocks = new ListTag<>(CompoundTag.class);
		for (CompoundTag entry : entries) {
			blocks.add(entry);
		}
		root.put("blocks", blocks);
		return root;
	}

	private static CompoundTag block(int x, int y, int z, int index) {
		CompoundTag entry = new CompoundTag();
		entry.put("pos", coordinates(x, y, z));
		entry.putInt("state", index);
		return entry;
	}

	private static ListTag<IntTag> coordinates(int x, int y, int z) {
		ListTag<IntTag> list = new ListTag<>(IntTag.class);
		list.addInt(x);
		list.addInt(y);
		list.addInt(z);
		return list;
	}
}
