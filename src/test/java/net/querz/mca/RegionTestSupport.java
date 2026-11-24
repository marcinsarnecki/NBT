package net.querz.mca;

import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

final class RegionTestSupport {
	private RegionTestSupport() {}

	private static final int SECTOR_SIZE = 4096;
	static final String[] HEIGHTMAP_KEYS = {
			"WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"
	};

	static void verifyPlainPaletteRoundTrip(File input, Path run, int expectedChunks) throws Exception {
		File first = Files.createDirectory(run.resolve("first")).resolve("r.0.0.mca").toFile();
		File second = Files.createDirectory(run.resolve("reopened")).resolve("r.0.0.mca").toFile();
		CompoundTag original = readChunk(input, 0, 0);
		assertEquals(5023, original.getInt("DataVersion"));
		CompoundTag states = sectionAt(original, -4).getCompoundTag("block_states");
		ListTag<StringTag> originalPalette = states.getListTag("palette").asStringTagList();
		ListTag<CompoundTag> expectedPalette = new ListTag<>(CompoundTag.class);
		for (StringTag state : originalPalette) {
			expectedPalette.add(wrappedState(state.getValue()));
		}
		int[] indices = unpackIndices(states, 4);
		int logXIndex = 4 * 256 + 3 * 16 + 3;
		int logYIndex = logXIndex + 1;
		int logZIndex = logXIndex + 2;
		StringTag air = new StringTag("minecraft:air");
		assertEquals(air, originalPalette.get(indices[logXIndex]));
		assertEquals(air, originalPalette.get(indices[logYIndex]));
		assertEquals(air, originalPalette.get(indices[logZIndex]));

		CompoundTag logX = stateWithProperty("minecraft:jungle_log", "axis", "x");
		MCAFile region = MCAUtil.read(input);
		region.setBlockStateAt(3, -60, 3, logX, false);
		assertEquals(expectedChunks, MCAUtil.write(region, first));
		indices[logXIndex] = expectedPalette.size();
		expectedPalette.add(encodedJungleLog("x"));
		assertPlainPaletteSave(original, first, expectedPalette, indices);

		CompoundTag logY = stateWithProperty("minecraft:jungle_log", "axis", "y");
		CompoundTag logZ = stateWithProperty("minecraft:jungle_log", "axis", "z");
		region = MCAUtil.read(first);
		assertBlockState(region, 3, -60, 3, logX);
		region.setBlockStateAt(4, -60, 3, logY, false);
		region.setBlockStateAt(5, -60, 3, logZ, true);
		assertEquals(expectedChunks, MCAUtil.write(region, second));
		indices[logYIndex] = expectedPalette.size();
		expectedPalette.add(encodedJungleLog("y"));
		indices[logZIndex] = expectedPalette.size();
		expectedPalette.add(encodedJungleLog("z"));
		assertPlainPaletteSave(original, second, expectedPalette, indices);
		assertUnrelatedChunksUnchanged(input, first, 0, 0);
		assertUnrelatedChunksUnchanged(input, second, 0, 0);
	}

	static void verifyMixedPaletteRegionEdit(File input, Path run, int expectedChunks) throws Exception {
		File output = run.resolve("r.0.0.mca").toFile();
		CompoundTag original = readChunk(input, 11, 13);
		assertEquals(5023, original.getInt("DataVersion"));

		MCAFile region = MCAUtil.read(input);
		region.setBlockStateAt(181, -60, 211, stateWithProperty("minecraft:jungle_log", "axis", "y"), false);
		region.setBlockStateAt(180, -60, 211, stateWithProperty("minecraft:wall_torch", "facing", "west"), false);
		region.setBlockStateAt(183, -60, 211, defaultState("minecraft:diamond_block"), false);
		assertEquals(expectedChunks, MCAUtil.write(region, output));

		verifyIntegrationEdits(original, readChunk(output, 11, 13));
		assertUnrelatedChunksUnchanged(input, output, 11, 13);
	}

	static void verifyMissingSectionRegionEdit(File source, Path run, int expectedChunks) throws Exception {
		File input = Files.createDirectory(run.resolve("input")).resolve("r.0.0.mca").toFile();
		File output = Files.createDirectory(run.resolve("output")).resolve("r.0.0.mca").toFile();
		CompoundTag preparedChunk = readChunk(source, 0, 0);
		assertEquals(5023, preparedChunk.getInt("DataVersion"));
		CompoundTag removed = sectionAt(preparedChunk, 6);
		assertEquals(3, removed.size());
		assertSingletonStringPalette("minecraft:air", removed.getCompoundTag("block_states"));
		assertSingletonStringPalette("minecraft:plains", removed.getCompoundTag("biomes"));
		removeSection(preparedChunk, 6);
		Files.copy(source.toPath(), input.toPath());
		replaceChunkInFixtureCopy(input, 0, 0, preparedChunk);

		CompoundTag inputChunk = readChunk(input, 0, 0);
		assertNull("Section Y=6 must be absent from the input NBT", findSection(inputChunk, 6));
		assertEquals(preparedChunk, inputChunk);
		MCAFile region = MCAUtil.read(input);
		assertNull(region.getBlockStateAt(8, 100, 8));
		region.setBlockStateAt(8, 100, 8, defaultState("minecraft:diamond_block"), false);
		assertEquals(expectedChunks, MCAUtil.write(region, output));

		CompoundTag savedChunk = readChunk(output, 0, 0);
		CompoundTag section = sectionAt(savedChunk, 6);
		assertEquals(6, section.getByte("Y"));
		CompoundTag states = section.getCompoundTag("block_states");
		assertNotNull(states);
		ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
		palette.add(wrappedState("minecraft:air"));
		palette.add(wrappedState("minecraft:diamond_block"));
		assertEquals(palette, states.getListTag("palette"));
		int[] expectedIndices = new int[4096];
		expectedIndices[4 * 256 + 8 * 16 + 8] = 1;
		assertArrayEquals(expectedIndices, unpackIndices(states, 4));

		removeSection(savedChunk, 6);
		assertHeightmapsInvalidated(inputChunk, savedChunk);
		assertEquals("Preserved other sections and chunk metadata", inputChunk, savedChunk);
		assertUnrelatedChunksUnchanged(source, output, 0, 0);
	}

	static CompoundTag encodedJungleLog(String axis) {
		CompoundTag entry = new CompoundTag();
		entry.putString("id", "minecraft:jungle_log");
		CompoundTag properties = new CompoundTag();
		properties.putString("axis", axis);
		entry.put("properties", properties);
		return entry;
	}

	private static void assertPlainPaletteSave(CompoundTag original, File output,
			ListTag<CompoundTag> palette, int[] indices) throws IOException {
		CompoundTag saved = readChunk(output, 0, 0);
		CompoundTag states = (CompoundTag) sectionAt(saved, -4).remove("block_states");
		assertEquals("Raw serialized palette", palette, states.getListTag("palette"));
		assertArrayEquals("All serialized block indices", indices, unpackIndices(states, 4));
		CompoundTag originalMetadata = original.clone();
		sectionAt(originalMetadata, -4).remove("block_states");
		assertHeightmapsInvalidated(originalMetadata, saved);
		assertEquals("Unrelated chunk data", originalMetadata, saved);
	}

	private static void verifyIntegrationEdits(CompoundTag original, CompoundTag saved) {
		CompoundTag originalStates = (CompoundTag) sectionAt(original, -4).remove("block_states");
		CompoundTag savedStates = (CompoundTag) sectionAt(saved, -4).remove("block_states");
		assertHeightmapsInvalidated(original, saved);
		assertEquals("Unedited data in target chunk", original, saved);

		ListTag<CompoundTag> expectedPalette = originalStates.getListTag("palette").asCompoundTagList();
		int[] expectedIndices = unpackIndices(originalStates, 4);
		// Local indices for (181, -60, 211), (180, -60, 211), and (183, -60, 211).
		int log = 4 * 256 + 3 * 16 + 5;
		int torch = log - 1;
		int diamond = log + 2;
		for (int index : new int[]{log, torch, diamond}) {
			assertEquals("Expected air before edit at local index " + index,
					wrappedState("minecraft:air"), expectedPalette.get(expectedIndices[index]));
		}
		CompoundTag expectedTorch = new CompoundTag();
		expectedTorch.putString("id", "minecraft:wall_torch");
		CompoundTag facing = new CompoundTag();
		facing.putString("facing", "west");
		expectedTorch.put("properties", facing);
		assertEquals(expectedTorch, expectedPalette.get(5));
		expectedIndices[torch] = 5;

		CompoundTag expectedLog = new CompoundTag();
		expectedLog.putString("id", "minecraft:jungle_log");
		CompoundTag axis = new CompoundTag();
		axis.putString("axis", "y");
		expectedLog.put("properties", axis);
		expectedIndices[log] = expectedPalette.size();
		expectedPalette.add(expectedLog);
		expectedIndices[diamond] = expectedPalette.size();
		expectedPalette.add(wrappedState("minecraft:diamond_block"));

		assertEquals("Raw on-disk palette", expectedPalette, savedStates.getListTag("palette"));
		assertArrayEquals("Raw on-disk block indices", expectedIndices, unpackIndices(savedStates, 4));
	}

	static void assertSingletonStringPalette(String name, CompoundTag container) {
		assertNotNull(container);
		assertEquals(1, container.size());
		ListTag<StringTag> palette = new ListTag<>(StringTag.class);
		palette.add(new StringTag(name));
		assertEquals(palette, container.getListTag("palette"));
	}

	// Keep the two palette records in slot order for tests that rely on a sparse region.
	static File copyPaletteRecords(File source, File destination) throws IOException {
		try (RandomAccessFile input = new RandomAccessFile(source, "r");
				RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
			output.setLength(0);
			output.write(new byte[2 * SECTOR_SIZE]);
			int nextSector = 2;
			for (int slot : new int[]{0, 11 + 13 * 32}) {
				long entry = slot * 4L;
				input.seek(entry);
				int location = input.readInt();
				assertTrue("Missing fixture slot " + slot, location != 0);
				input.seek(SECTOR_SIZE + entry);
				int timestamp = input.readInt();
				input.seek((location >>> 8) * (long) SECTOR_SIZE);
				int length = input.readInt();
				assertTrue(length >= 2 && length <= (location & 255) * SECTOR_SIZE - 4);
				byte[] payload = new byte[length];
				input.readFully(payload);
				int sectors = (4 + length + SECTOR_SIZE - 1) / SECTOR_SIZE;

				output.seek(entry);
				output.writeInt((nextSector << 8) | sectors);
				output.seek(SECTOR_SIZE + entry);
				output.writeInt(timestamp);
				output.seek(nextSector * (long) SECTOR_SIZE);
				output.writeInt(length);
				output.write(payload);
				output.write(new byte[sectors * SECTOR_SIZE - 4 - length]);
				nextSector += sectors;
			}
		}
		return destination;
	}

	// Decode the raw chunk with generic NBT I/O, bypassing MCAFile, Chunk, and Section.
	static CompoundTag readChunk(File file, int localChunkX, int localChunkZ) throws IOException {
		byte[] payload;
		try (RandomAccessFile region = new RandomAccessFile(file, "r")) {
			region.seek(4L * (localChunkX + 32 * localChunkZ));
			int location = region.readInt();
			if (location == 0) {
				return null;
			}
			region.seek(4096L * (location >>> 8));
			int length = region.readInt();
			assertEquals("Expected ZLIB compression", 2, region.readUnsignedByte());
			payload = new byte[length - 1];
			region.readFully(payload);
		}
		try (InflaterInputStream nbt = new InflaterInputStream(new ByteArrayInputStream(payload))) {
			return (CompoundTag) new NBTDeserializer(false).fromStream(nbt).getTag();
		}
	}

	static CompoundTag sectionAt(CompoundTag chunk, int y) {
		CompoundTag section = findSection(chunk, y);
		assertNotNull("Missing section Y=" + y, section);
		return section;
	}

	static CompoundTag findSection(CompoundTag chunk, int y) {
		for (CompoundTag section : chunk.getListTag("sections").asCompoundTagList()) {
			if (section.getByte("Y") == y) {
				return section;
			}
		}
		return null;
	}

	static int[] unpackIndices(CompoundTag blockStates, int bits) {
		long[] data = blockStates.getLongArrayTag("data").getValue();
		int[] indices = new int[4096];
		int perLong = 64 / bits;
		assertEquals((indices.length + perLong - 1) / perLong, data.length);
		for (int i = 0; i < indices.length; i++) {
			indices[i] = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
		}
		return indices;
	}

	static byte[] regionTimestamps(File file) throws IOException {
		byte[] timestamps = new byte[4096];
		try (RandomAccessFile region = new RandomAccessFile(file, "r")) {
			region.seek(4096);
			region.readFully(timestamps);
		}
		return timestamps;
	}

	static void assertUnrelatedChunksUnchanged(File input, File output, int editedX, int editedZ)
			throws IOException {
		assertArrayEquals(regionTimestamps(input), regionTimestamps(output));
		for (int z = 0; z < 32; z++) {
			for (int x = 0; x < 32; x++) {
				if (x != editedX || z != editedZ) {
					assertEquals("Unedited chunk (" + x + ", " + z + ")",
							readChunk(input, x, z), readChunk(output, x, z));
				}
			}
		}
	}

	// Replace one record in a scratch copy without changing the production writer or other slots.
	static void replaceChunkInFixtureCopy(File input, int x, int z, CompoundTag chunk) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DeflaterOutputStream compressed = new DeflaterOutputStream(bytes)) {
			new NBTSerializer(false).toStream(new NamedTag(null, chunk), compressed);
		}
		byte[] payload = bytes.toByteArray();
		try (RandomAccessFile region = new RandomAccessFile(input, "rw")) {
			region.seek(4L * (x + 32 * z));
			int location = region.readInt();
			assertTrue("Prepared chunk must fit the original sector allocation",
					payload.length + 5 <= (location & 255) * 4096);
			region.seek((location >>> 8) * 4096L);
			region.writeInt(payload.length + 1);
			region.writeByte(2);
			region.write(payload);
		}
	}

	static void assertHeightmapsInvalidated(CompoundTag original, CompoundTag saved) {
		CompoundTag expected = (CompoundTag) original.remove("Heightmaps");
		assertNotNull(expected);
		for (String key : HEIGHTMAP_KEYS) {
			assertNotNull("Fixture must contain " + key, expected.remove(key));
		}
		assertEquals("Preserved heightmap entries", expected, saved.remove("Heightmaps"));
	}

	static void removeSection(CompoundTag chunk, int y) {
		ListTag<CompoundTag> sections = chunk.getListTag("sections").asCompoundTagList();
		for (int i = 0; i < sections.size(); i++) {
			if (sections.get(i).getByte("Y") == y) {
				sections.remove(i);
				return;
			}
		}
		throw new AssertionError("Missing section Y=" + y);
	}

	static CompoundTag wrappedState(String name) {
		CompoundTag state = new CompoundTag();
		state.putString("", name);
		return state;
	}

	static CompoundTag defaultState(String name) {
		CompoundTag state = new CompoundTag();
		state.putString("Name", name);
		return state;
	}

	static CompoundTag stateWithProperty(String name, String key, String value) {
		CompoundTag state = new CompoundTag();
		state.putString("Name", name);
		CompoundTag properties = new CompoundTag();
		properties.putString(key, value);
		state.put("Properties", properties);
		return state;
	}

	static void assertBlockState(MCAFile region, int x, int y, int z, CompoundTag expected) {
		assertEquals("Block state at (" + x + ", " + y + ", " + z + ")",
				expected, region.getBlockStateAt(x, y, z));
	}
}
