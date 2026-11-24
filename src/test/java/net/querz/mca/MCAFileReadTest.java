package net.querz.mca;

import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MCAFileReadTest {
	private static final int SECTOR_SIZE = 4096;
	private static final int LENGTH_BYTES = 4;
	private static final int SECOND_SLOT = 11 + 13 * 32;
	private static final long[] LOAD_MODES = {
			LoadFlags.ALL_DATA, LoadFlags.BLOCK_STATES, LoadFlags.RAW, LoadFlags.HEIGHTMAPS
	};

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void readsBothRecordsInValidZlibRegion() throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		assertEquals("minecraft:bedrock", region.getBlockStateAt(0, -64, 0).getString("Name"));
		assertEquals("minecraft:wall_torch", region.getBlockStateAt(191, -57, 221).getString("Name"));
	}

	@Test
	public void readsDirectChunkAtCompressionByte() throws Exception {
		try (RandomAccessFile file = new RandomAccessFile(fixture(), "r")) {
			int location = file.readInt();
			long offset = sectorOffset(location);
			file.seek(offset);
			int length = file.readInt();
			Chunk chunk = new Chunk(0);
			chunk.deserialize(file);
			assertEquals(offset + LENGTH_BYTES + length, file.getFilePointer());
			assertEquals("minecraft:bedrock", chunk.getBlockStateAt(0, -64, 0).getString("Name"));
		}
	}

	@Test
	public void reloadingChunkReplacesSectionsAndTerrainMetadata() throws Exception {
		CompoundTag first = singleSectionTerrain(-4, "minecraft:bedrock");
		first.put("Heightmaps", new CompoundTag());
		first.put("structures", new CompoundTag());
		for (String key : new String[]{"block_entities", "block_ticks", "fluid_ticks"}) {
			first.put(key, new ListTag<>(CompoundTag.class));
		}
		first.put("PostProcessing", new ListTag<>(ListTag.class));
		CompoundTag second = singleSectionTerrain(0, "minecraft:stone");
		second.putInt("DataVersion", 4790);
		second.putLong("LastUpdate", 42);
		second.putLong("InhabitedTime", 24);
		second.putString("Unrelated", "second record");

		Chunk chunk = new Chunk(123);
		reload(chunk, writeRecord("r.0.0.mca", 0, first), LoadFlags.ALL_DATA);
		assertEquals("minecraft:bedrock", chunk.getBlockStateAt(0, -64, 0).getString("Name"));
		reload(chunk, writeRecord("r.0.0.mca", 0, second), LoadFlags.ALL_DATA);
		assertNull("Section from the first record", chunk.getBlockStateAt(0, -64, 0));
		assertEquals("minecraft:stone", chunk.getBlockStateAt(0, 0, 0).getString("Name"));
		assertEquals(123, chunk.getLastMCAUpdate());
		assertEquals(second, serializeAndReadRaw(chunk));
	}

	@Test
	public void reloadingChunkReplacesLoadingModeAndExposesOnlyCurrentData() throws Exception {
		File first = writeRecord("r.0.0.mca", 0, singleSectionTerrain(-4, "minecraft:bedrock"));
		CompoundTag second = singleSectionTerrain(0, "minecraft:stone");
		File next = writeRecord("r.0.0.mca", 0, second);
		for (long from : LOAD_MODES) {
			for (long to : LOAD_MODES) {
				Chunk chunk = new Chunk(0);
				reload(chunk, first, from);
				reload(chunk, next, to);
				String transition = "Loading mode " + from + " to " + to;
				assertNull(transition, chunk.getBlockStateAt(0, -64, 0));
				if (to == LoadFlags.ALL_DATA || to == LoadFlags.BLOCK_STATES) {
					assertEquals(transition, "minecraft:stone", chunk.getBlockStateAt(0, 0, 0).getString("Name"));
				} else {
					assertFalse(transition, chunk.iterator().hasNext());
				}
				if (to == LoadFlags.RAW) {
					assertEquals(transition, second, chunk.updateHandle(7, 8));
					assertEquals(transition, second, serializeAndReadRaw(chunk));
				} else if (to == LoadFlags.ALL_DATA) {
					assertEquals(transition, second, serializeAndReadRaw(chunk));
					CompoundTag diamond = new CompoundTag();
					diamond.putString("Name", "minecraft:diamond_block");
					chunk.setBlockStateAt(0, 0, 0, diamond, false);
					assertEquals(transition, diamond, chunk.getBlockStateAt(0, 0, 0));
				} else {
					try (RandomAccessFile output = new RandomAccessFile(temp.newFile(), "rw")) {
						try {
							chunk.serialize(output, 0, 0);
							fail(transition + " must remain read-only for serialization");
						} catch (UnsupportedOperationException expected) {
							assertEquals(0, output.length());
						}
					}
				}
			}
		}
	}

	@Test
	public void reloadingChunkReplacesTerrainAndEntityCaches() throws Exception {
		CompoundTag terrain = singleSectionTerrain(0, "minecraft:stone");
		File terrainFile = writeRecord("r.0.0.mca", 0, terrain);
		CompoundTag entities = new CompoundTag();
		entities.putInt("DataVersion", 5023);
		entities.putIntArray("Position", new int[]{0, 0});
		entities.put("Entities", new ListTag<>(CompoundTag.class));
		File entityFile = writeRecord("r.0.0.mca", 0, entities);

		Chunk chunk = new Chunk(0);
		reload(chunk, terrainFile, LoadFlags.ALL_DATA);
		reload(chunk, entityFile, LoadFlags.ALL_DATA);
		assertNull(chunk.getBlockStateAt(0, 0, 0));
		assertEquals(entities, serializeAndReadRaw(chunk));
		reload(chunk, terrainFile, LoadFlags.ALL_DATA);
		assertEquals(terrain, serializeAndReadRaw(chunk));
	}

	@Test
	public void failedChunkReloadPreservesThePreviousRootAndSections() throws Exception {
		CompoundTag original = singleSectionTerrain(-4, "minecraft:bedrock");
		Chunk chunk = new Chunk(original);
		CompoundTag before = original.clone();
		CompoundTag replacement = singleSectionTerrain(0, "minecraft:stone");
		replacement.putLong("LastUpdate", 123);
		CompoundTag invalidSection = sectionWithBlock(1, "minecraft:stone");
		invalidSection.getCompoundTag("block_states").putLongArray("data", new long[1]);
		replacement.getListTag("sections").asCompoundTagList().add(invalidSection);
		File malformed = writeRecord("r.0.0.mca", 0, replacement);
		try {
			reload(chunk, malformed, LoadFlags.ALL_DATA);
			fail("Expected the invalid packed array in the second section to be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("packed longs"));
		}
		assertSame(original, chunk.updateHandle(0, 0));
		assertNull("Valid prefix section from the failed record", chunk.getBlockStateAt(0, 0, 0));
		assertEquals(before, serializeAndReadRaw(chunk));

		changeFirstRecordLength(malformed, -1);
		try {
			reload(chunk, malformed, LoadFlags.RAW);
			fail("Expected the truncated ZLIB record to be rejected");
		} catch (IOException expected) {
			assertSame(original, chunk.updateHandle(0, 0));
			assertEquals(before, serializeAndReadRaw(chunk));
		}
	}

	@Test
	public void rejectsMismatchedXAndZIndependentlyInAllLoadModes() throws Exception {
		CompoundTag terrain = terrainRecord();
		terrain.putInt("xPos", 1);
		File wrongX = writeRecord("r.0.0.mca", 0, terrain);
		assertCoordinateRejected(wrongX, "xPos=1", "zPos=0");

		terrain.putInt("xPos", 0);
		terrain.putInt("zPos", -1);
		assertCoordinateRejected(writeRecord("r.0.0.mca", 0, terrain), "xPos=0", "zPos=-1");

		// Direct chunk reads have no region or slot against which to validate xPos.
		try (RandomAccessFile file = new RandomAccessFile(wrongX, "r")) {
			file.seek(2L * SECTOR_SIZE + LENGTH_BYTES);
			Chunk chunk = new Chunk(0);
			chunk.deserialize(file);
			assertEquals("minecraft:bedrock", chunk.getBlockStateAt(0, -64, 0).getString("Name"));
		}
	}

	@Test
	public void rejectsMissingCoordinatesEvenAtWorldChunkZero() throws Exception {
		CompoundTag terrain = terrainRecord();
		terrain.remove("xPos");
		assertCoordinateRejected(writeRecord("r.0.0.mca", 0, terrain), "xPos=missing", "zPos=0");
		terrain.remove("zPos");
		assertCoordinateRejected(writeRecord("r.0.0.mca", 0, terrain), "xPos=missing", "zPos=missing");
		terrain.putInt("xPos", 0);
		assertCoordinateRejected(writeRecord("r.0.0.mca", 0, terrain), "xPos=0", "zPos=missing");
	}

	@Test
	public void requiresIntTagsForEachCoordinate() throws Exception {
		CompoundTag terrain = terrainRecord();
		terrain.putShort("xPos", (short) 0);
		assertCoordinateRejected(writeRecord("r.0.0.mca", 0, terrain), "xPos=ShortTag", "zPos=0", "TAG_Int");
		terrain.putInt("xPos", 0);
		terrain.putString("zPos", "0");
		assertCoordinateRejected(writeRecord("r.0.0.mca", 0, terrain), "xPos=0", "zPos=StringTag", "TAG_Int");
	}

	@Test
	public void validatesSignedCoordinatesAtNegativeRegionBoundaries() throws Exception {
		CompoundTag terrain = terrainRecord();
		terrain.putInt("xPos", -32);
		terrain.putInt("zPos", -64);
		File first = writeRecord("r.-1.-2.mca", 0, terrain);
		terrain.putInt("xPos", -1);
		terrain.putInt("zPos", -33);
		File last = writeRecord("r.-1.-2.mca", 1023, terrain);
		for (long mode : LOAD_MODES) {
			assertNotNull(MCAUtil.read(first, mode).getChunk(-32, -64));
			assertNotNull(MCAUtil.read(last, mode).getChunk(-1, -33));
		}
		assertEquals("minecraft:bedrock", MCAUtil.read(first).getBlockStateAt(-512, -64, -1024).getString("Name"));
		assertEquals("minecraft:bedrock", MCAUtil.read(last).getBlockStateAt(-1, -64, -513).getString("Name"));

		terrain.putInt("zPos", -32);
		File mismatch = writeRecord("r.-1.-2.mca", 1023, terrain);
		assertCoordinateRejectedAt(mismatch, "region (-1, -2)", "local slot 1023 (31, 31)",
				"expected chunk (-1, -33)", "xPos=-1", "zPos=-32");
	}

	@Test
	public void readsTerrainFixturesInAllLoadModes() throws Exception {
		assertFixtureLoads("overworld/r.0.0.mca", 191, -57, 221, "minecraft:wall_torch",
				0, 11 + 13 * 32, 11 + 16 * 32, 13 + 16 * 32, 12 + 18 * 32);
		assertFixtureLoads("nether/r.0.0.mca", 120, 83, 24, "minecraft:netherrack", 7 + 32, 8 + 9 * 32);
		assertFixtureLoads("end/r.0.0.mca", 8, 83, 8, "minecraft:end_stone", 0, 4 * 32);
		assertFixtureLoads("legacy/r.0.1.mca", 3, -63, 512, "minecraft:deepslate", 0);
	}

	private static void assertFixtureLoads(String name, int x, int y, int z, String block,
			Integer... expectedSlots) throws Exception {
		File input = fixture(name);
		for (long mode : LOAD_MODES) {
			MCAFile region = MCAUtil.read(input, mode);
			String context = name + " at load mode " + mode;
			List<Integer> occupiedSlots = new ArrayList<>();
			for (int slot = 0; slot < 1024; slot++) {
				Chunk chunk = region.getChunk(slot);
				if (chunk != null) {
					occupiedSlots.add(slot);
					if (mode == LoadFlags.RAW) {
						assertEquals(context + " slot " + slot, RegionTestSupport.readChunk(input, slot % 32, slot / 32),
								chunk.updateHandle(0, 0));
					}
				}
			}
			assertEquals(context, Arrays.asList(expectedSlots), occupiedSlots);
			if (mode == LoadFlags.ALL_DATA || mode == LoadFlags.BLOCK_STATES) {
				CompoundTag state = region.getBlockStateAt(x, y, z);
				assertNotNull(context, state);
				assertEquals(context, block, state.getString("Name"));
			} else {
				assertFalse(context, region.getChunk(x >> 4, z >> 4).iterator().hasNext());
			}
		}
	}

	@Test
	public void keepsLegacyRawAndEntityRecordHandling() throws Exception {
		CompoundTag legacy = new CompoundTag();
		CompoundTag level = new CompoundTag();
		level.putInt("xPos", 7);
		level.putInt("zPos", 8);
		legacy.put("Level", level);
		File legacyFile = writeRecord("r.0.0.mca", 0, legacy);
		assertEquals(legacy, MCAUtil.read(legacyFile, LoadFlags.RAW).getChunk(0).updateHandle(0, 0));

		CompoundTag entities = new CompoundTag();
		entities.putInt("DataVersion", 5023);
		entities.putIntArray("Position", new int[]{0, 0});
		entities.put("Entities", new ListTag<>(CompoundTag.class));
		File entityFile = writeRecord("r.0.0.mca", 0, entities);
		for (long mode : LOAD_MODES) {
			assertNotNull(MCAUtil.read(entityFile, mode).getChunk(0));
		}
	}

	@Test
	public void rejectsNonpositiveLengthAndEmptyPayload() throws Exception {
		File copy = fixture();
		for (int length : new int[]{-1, 0, 1}) {
			try (RandomAccessFile file = new RandomAccessFile(copy, "rw")) {
				int location = file.readInt();
				file.seek(sectorOffset(location));
				file.writeInt(length);
			}
			assertRejected(copy);
		}
	}

	@Test
	public void rejectsLengthThatExcludesPartOfZlibTrailer() throws Exception {
		File copy = fixture();
		changeFirstRecordLength(copy, -1);
		assertRejected(copy);
	}

	@Test
	public void rejectsLengthThatIncludesBytesAfterZlibStream() throws Exception {
		File copy = fixture();
		changeFirstRecordLength(copy, 1);
		assertRejected(copy);
	}

	@Test
	public void rejectsBadZlibChecksumAfterCompleteNbt() throws Exception {
		File copy = fixture();
		try (RandomAccessFile file = new RandomAccessFile(copy, "rw")) {
			long offset = sectorOffset(file.readInt());
			file.seek(offset);
			long lastByte = offset + LENGTH_BYTES + file.readInt() - 1L;
			file.seek(lastByte);
			int checksumByte = file.readUnsignedByte();
			file.seek(lastByte);
			file.writeByte(checksumByte ^ 1);
		}
		assertRejected(copy);
	}

	@Test
	public void rejectsLengthThatExceedsSectorAllocation() throws Exception {
		File copy = fixture();
		try (RandomAccessFile file = new RandomAccessFile(copy, "rw")) {
			int location = file.readInt();
			file.seek(sectorOffset(location));
			file.writeInt((location & 255) * SECTOR_SIZE - LENGTH_BYTES + 1);
		}
		assertRejected(copy);
	}

	@Test
	public void rejectsTruncatedLastRecord() throws Exception {
		File copy = fixture();
		try (RandomAccessFile file = new RandomAccessFile(copy, "rw")) {
			file.seek(SECOND_SLOT * (long) LENGTH_BYTES);
			long offset = sectorOffset(file.readInt());
			file.seek(offset);
			int originalLength = file.readInt();
			file.setLength(offset + LENGTH_BYTES + originalLength - 1L);
		}
		assertRejected(copy);
	}

	private static void changeFirstRecordLength(File copy, int difference) throws IOException {
		try (RandomAccessFile file = new RandomAccessFile(copy, "rw")) {
			int location = file.readInt();
			long offset = sectorOffset(location);
			file.seek(offset);
			int originalLength = file.readInt();
			file.seek(offset);
			file.writeInt(originalLength + difference);
		}
	}

	private static long sectorOffset(int location) {
		return (location >>> 8) * (long) SECTOR_SIZE;
	}

	private static void assertRejected(File file) {
		try {
			MCAUtil.read(file);
			fail("Expected an invalid region record to be rejected");
		} catch (IOException expected) {
			// The region reader must fail before returning the malformed chunk.
		}
	}

	private File fixture() throws Exception {
		return RegionTestSupport.copyPaletteRecords(fixture("overworld/r.0.0.mca"),
				new File(temp.newFolder(), "r.0.0.mca"));
	}

	private static File fixture(String name) throws Exception {
		URL url = MCAFileReadTest.class.getResource("palettes/" + name);
		assertNotNull(url);
		return new File(url.toURI());
	}

	private CompoundTag terrainRecord() throws Exception {
		return MCAUtil.read(fixture(), LoadFlags.RAW).getChunk(0).updateHandle(0, 0);
	}

	private static CompoundTag singleSectionTerrain(int sectionY, String block) {
		CompoundTag terrain = new CompoundTag();
		terrain.putInt("DataVersion", 5023);
		terrain.putInt("xPos", 0);
		terrain.putInt("zPos", 0);
		terrain.putString("Status", "minecraft:full");
		terrain.putLong("LastUpdate", 0);
		terrain.putLong("InhabitedTime", 0);
		ListTag<CompoundTag> sections = new ListTag<>(CompoundTag.class);
		sections.add(sectionWithBlock(sectionY, block));
		terrain.put("sections", sections);
		return terrain;
	}

	private static CompoundTag sectionWithBlock(int sectionY, String block) {
		CompoundTag section = new CompoundTag();
		section.putByte("Y", (byte) sectionY);
		CompoundTag blockStates = new CompoundTag();
		ListTag<StringTag> palette = new ListTag<>(StringTag.class);
		palette.addString(block);
		blockStates.put("palette", palette);
		section.put("block_states", blockStates);
		return section;
	}

	private static void reload(Chunk chunk, File input, long mode) throws IOException {
		try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
			file.seek(2L * SECTOR_SIZE + LENGTH_BYTES);
			chunk.deserialize(file, mode);
		}
	}

	private CompoundTag serializeAndReadRaw(Chunk chunk) throws IOException {
		File output = temp.newFile();
		try (RandomAccessFile file = new RandomAccessFile(output, "rw")) {
			file.writeInt((2 << 8) | 1);
			file.seek(2L * SECTOR_SIZE);
			chunk.serialize(file, 0, 0);
		}
		return RegionTestSupport.readChunk(output, 0, 0);
	}

	private File writeRecord(String filename, int slot, CompoundTag nbt) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DeflaterOutputStream compressed = new DeflaterOutputStream(bytes)) {
			new NBTSerializer(false).toStream(new NamedTag(null, nbt), compressed);
		}
		byte[] payload = bytes.toByteArray();
		assertTrue(payload.length + LENGTH_BYTES + 1 <= SECTOR_SIZE);
		File region = new File(temp.newFolder(), filename);
		try (RandomAccessFile file = new RandomAccessFile(region, "rw")) {
			file.setLength(3L * SECTOR_SIZE);
			file.seek(slot * (long) LENGTH_BYTES);
			file.writeInt((2 << 8) | 1);
			file.seek(2L * SECTOR_SIZE);
			file.writeInt(payload.length + 1);
			file.writeByte(2);
			file.write(payload);
		}
		return region;
	}

	private static void assertCoordinateRejected(File file, String... details) {
		assertCoordinateRejectedAt(file, "region (0, 0)", "local slot 0 (0, 0)", "expected chunk (0, 0)", details);
	}

	private static void assertCoordinateRejectedAt(File file, String region, String slot, String position,
			String... details) {
		for (long mode : LOAD_MODES) {
			try {
				MCAUtil.read(file, mode);
				fail("Expected invalid coordinates to be rejected in load mode " + mode);
			} catch (IOException expected) {
				String message = expected.getMessage();
				assertTrue(message, message.contains(region));
				assertTrue(message, message.contains(slot));
				assertTrue(message, message.contains(position));
				for (String detail : details) {
					assertTrue(message, message.contains(detail));
				}
			}
		}
	}
}
