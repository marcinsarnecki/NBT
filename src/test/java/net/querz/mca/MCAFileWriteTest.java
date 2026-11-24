package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.file.Files;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MCAFileWriteTest {
	private static final int SECTOR_SIZE = 4096;
	private static final int NEAR_LIMIT_NBT_BYTES = 1_041_000;
	private static final int OVERSIZED_NBT_BYTES = 1_046_000;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void writesHeaderOnlyRegionToNewFile() throws Exception {
		File destination = new File(temp.getRoot(), "r.0.0.mca");
		assertEquals(0, MCAUtil.write(new MCAFile(0, 0), destination));
		assertEmptyRegion(destination);
	}

	@Test
	public void iteratesAllSlotsInNewAndReopenedEmptyRegions() throws Exception {
		MCAFile region = new MCAFile(0, 0);
		assertAllSlotsEmpty(region);
		File destination = new File(temp.getRoot(), "r.0.0.mca");
		assertEquals(0, MCAUtil.write(region, destination));
		assertAllSlotsEmpty(MCAUtil.read(destination));
	}

	@Test
	public void overwritesRegionAfterRemovingLastChunk() throws Exception {
		File destination = copyFixtureToDestination();
		MCAFile region = MCAUtil.read(destination);
		region.setChunk(0, 0, null);
		assertEquals(1, MCAUtil.write(region, destination));
		region = MCAUtil.read(destination);
		region.setChunk(11, 13, null);
		assertEquals(0, MCAUtil.write(region, destination));
		assertEmptyRegion(destination);
	}

	@Test
	public void writesHeaderOnlyRegionToNewOpenFile() throws Exception {
		File destination = new File(temp.getRoot(), "r.0.0.mca");
		try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
			assertEquals(0, new MCAFile(0, 0).serialize(file));
		}
		assertEmptyRegion(destination);
	}

	@Test
	public void overwritesOpenFileAfterRemovingAllChunks() throws Exception {
		File destination = copyFixtureToDestination();
		MCAFile region = MCAUtil.read(destination);
		region.setChunk(0, 0, null);
		region.setChunk(11, 13, null);
		try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
			file.seek(file.length());
			assertEquals(0, region.serialize(file));
			file.seek(0);
			MCAFile reopened = new MCAFile(0, 0);
			reopened.deserialize(file);
			for (Chunk chunk : reopened) {
				assertNull(chunk);
			}
		}
		assertEmptyRegion(destination);
	}

	@Test
	public void removesOneChunkAndPreservesTheRemainingChunk() throws Exception {
		File destination = copyFixtureToDestination();
		Chunk original = MCAUtil.read(destination, LoadFlags.RAW).getChunk(11, 13);
		MCAFile region = MCAUtil.read(destination);
		region.setChunk(0, 0, null);
		assertEquals(1, MCAUtil.write(region, destination));

		MCAFile reopened = MCAUtil.read(destination, LoadFlags.RAW);
		assertNull(reopened.getChunk(0, 0));
		Chunk remaining = reopened.getChunk(11, 13);
		assertNotNull(remaining);
		assertEquals(original.getLastMCAUpdate(), remaining.getLastMCAUpdate());
		assertEquals(original.updateHandle(11, 13), remaining.updateHandle(11, 13));
		try (RandomAccessFile file = new RandomAccessFile(destination, "r")) {
			assertEquals(0, file.readInt());
			file.seek(SECTOR_SIZE);
			assertEquals(0, file.readInt());
		}
	}

	@Test
	public void writesMatchingRegionNameToDifferentDirectory() throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		File output = new File(temp.newFolder("export"), "r.0.0.mca");
		assertEquals(2, MCAUtil.write(region, output));
		assertEquals(region.getBlockStateAt(0, -64, 0), MCAUtil.read(output).getBlockStateAt(0, -64, 0));
	}

	@Test
	public void rejectsMismatchedXWithoutOverwritingDestination() throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		File export = temp.newFolder("export");
		File destination = new File(export, "r.1.0.mca");
		byte[] before = {1, 2, 3};
		Files.write(destination.toPath(), before);

		assertRegionMismatch(() -> MCAUtil.write(region, destination), "(0, 0)", "(1, 0)");
		assertArrayEquals(before, Files.readAllBytes(destination.toPath()));
		assertArrayEquals(new String[]{"r.1.0.mca"}, export.list());
	}

	@Test
	public void rejectsMismatchedZWithoutCreatingFiles() throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		File export = temp.newFolder("export");
		File destination = new File(export, "r.0.1.mca");
		assertRegionMismatch(() -> MCAUtil.write(region, destination.getPath()), "(0, 0)", "(0, 1)");
		assertArrayEquals(new String[0], export.list());
	}

	@Test
	public void rejectsMismatchBeforeAttemptingToStageTheWrite() throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		File missingDirectory = new File(temp.getRoot(), "missing");
		File destination = new File(missingDirectory, "r.1.0.mca");
		// Attempting to create a staging file here would throw IOException.
		assertRegionMismatch(() -> MCAUtil.write(region, destination), "(0, 0)", "(1, 0)");
		assertFalse(missingDirectory.exists());
	}

	@Test
	public void checksNegativeRegionCoordinatesInPrefixedNames() throws Exception {
		MCAFile region = new MCAFile(-1, -2);
		region.setChunk(0, 0, MCAUtil.read(fixture()).getChunk(0, 0));
		File matching = new File(temp.getRoot(), "backup-r.-1.-2.mca");
		assertEquals(1, MCAUtil.write(region, matching));
		assertEquals("minecraft:bedrock", MCAUtil.read(matching).getBlockStateAt(-512, -64, -1024).getString("Name"));

		File mismatched = new File(temp.getRoot(), "backup-r.-2.-2.mca");
		assertRegionMismatch(() -> MCAUtil.write(region, mismatched), "(-1, -2)", "(-2, -2)");
		assertFalse(mismatched.exists());
	}

	@Test
	public void writesArbitraryExportFilename() throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		File expected = new File(temp.getRoot(), "r.0.0.mca");
		File arbitrary = new File(temp.getRoot(), "region-backup.mca");
		assertEquals(2, MCAUtil.write(region, expected));
		assertEquals(2, MCAUtil.write(region, arbitrary));
		assertArrayEquals(Files.readAllBytes(expected.toPath()), Files.readAllBytes(arbitrary.toPath()));
	}

	@Test
	public void writesChunkRequiring255Sectors() throws Exception {
		MCAFile region = regionWithLargeSecondChunk(NEAR_LIMIT_NBT_BYTES);
		File output = new File(temp.getRoot(), "r.0.0.mca");
		assertEquals(2, MCAUtil.write(region, output));

		try (RandomAccessFile file = new RandomAccessFile(output, "r")) {
			file.seek(4L * (11 + 13 * 32));
			int location = file.readInt();
			assertEquals(255, location & 255);
			long offset = (location >>> 8) * (long) SECTOR_SIZE;
			file.seek(offset);
			long recordSize = 4L + file.readInt();
			assertEquals(255, (recordSize + SECTOR_SIZE - 1) / SECTOR_SIZE);
			assertEquals(offset + 255L * SECTOR_SIZE, file.length());
		}
		assertEquals("minecraft:diamond_block",
				MCAUtil.read(output).getBlockStateAt(0, -64, 0).getString("Name"));
	}

	@Test
	public void writesValidRegionToOpenFile() throws Exception {
		MCAFile region = regionWithLargeSecondChunk(NEAR_LIMIT_NBT_BYTES);
		File expected = new File(temp.getRoot(), "expected-r.0.0.mca");
		assertEquals(2, MCAUtil.write(region, expected));

		File destination = new File(temp.getRoot(), "r.1.0.mca");
		Files.copy(fixture().toPath(), destination.toPath());
		try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
			assertEquals(2, region.serialize(file));
		}
		assertArrayEquals(Files.readAllBytes(expected.toPath()), Files.readAllBytes(destination.toPath()));
	}

	@Test
	public void rejectsOversizedChunkWithoutReplacingExistingFile() throws Exception {
		MCAFile region = regionWithLargeSecondChunk(OVERSIZED_NBT_BYTES);
		File destination = copyFixtureToDestination();
		byte[] before = Files.readAllBytes(destination.toPath());
		assertOversized(() -> MCAUtil.write(region, destination));
		assertArrayEquals(before, Files.readAllBytes(destination.toPath()));
	}

	@Test
	public void rejectsOversizedChunkWithoutPublishingNewFile() throws Exception {
		MCAFile region = regionWithLargeSecondChunk(OVERSIZED_NBT_BYTES);
		File destination = new File(temp.getRoot(), "r.0.0.mca");
		assertOversized(() -> MCAUtil.write(region, destination));
		assertFalse(destination.exists());
	}

	@Test
	public void rejectsOversizedChunkBeforeOverwritingOpenFile() throws Exception {
		MCAFile region = regionWithLargeSecondChunk(OVERSIZED_NBT_BYTES);
		File destination = copyFixtureToDestination();
		byte[] before = Files.readAllBytes(destination.toPath());
		try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
			assertOversized(() -> region.serialize(file));
		}
		assertArrayEquals(before, Files.readAllBytes(destination.toPath()));
	}

	private MCAFile regionWithLargeSecondChunk(int byteCount) throws Exception {
		MCAFile region = MCAUtil.read(fixture());
		CompoundTag large = region.getChunk(11, 13).updateHandle(11, 13).clone();
		byte[] noise = new byte[byteCount];
		new Random(31).nextBytes(noise);
		large.putByteArray("LargeTestPayload", noise);
		region.setChunk(11, 13, new Chunk(large));
		CompoundTag diamond = new CompoundTag();
		diamond.putString("Name", "minecraft:diamond_block");
		region.setBlockStateAt(0, -64, 0, diamond, false);
		return region;
	}

	private File copyFixtureToDestination() throws Exception {
		File destination = new File(temp.getRoot(), "r.0.0.mca");
		Files.copy(fixture().toPath(), destination.toPath());
		return destination;
	}

	private static void assertEmptyRegion(File destination) throws IOException {
		assertEquals(2L * SECTOR_SIZE, Files.size(destination.toPath()));
		assertArrayEquals(new byte[2 * SECTOR_SIZE], Files.readAllBytes(destination.toPath()));
		assertAllSlotsEmpty(MCAUtil.read(destination));
	}

	private static void assertAllSlotsEmpty(MCAFile region) {
		int count = 0;
		for (Chunk chunk : region) {
			assertNull(chunk);
			count++;
		}
		assertEquals(1024, count);
	}

	private File fixture() throws Exception {
		URL url = MCAFileWriteTest.class.getResource("palettes/overworld/r.0.0.mca");
		assertNotNull(url);
		return RegionTestSupport.copyPaletteRecords(new File(url.toURI()),
				new File(temp.newFolder(), "r.0.0.mca"));
	}

	private static void assertRegionMismatch(Write write, String source, String destination) throws IOException {
		try {
			write.run();
			fail("Expected mismatched region coordinates to be rejected");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(source));
			assertTrue(expected.getMessage(), expected.getMessage().contains(destination));
		}
	}

	private static void assertOversized(Write write) throws IOException {
		try {
			write.run();
			fail("Expected the chunk requiring 256 sectors to be rejected");
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("256 sectors"));
		}
	}

	@FunctionalInterface
	private interface Write {
		void run() throws IOException;
	}
}
