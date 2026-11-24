package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class MCAFileCoordinatesTest {
	private static final int Y = -64;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void acceptsBothEdgesOfRegionZero() throws Exception {
		MCAFile region = regionWithCorners(0, 0);
		assertCornerEdits(region, 0, 0);
	}

	@Test
	public void usesNegativeRegionCoordinatesFromFilename() throws Exception {
		MCAFile source = regionWithCorners(-1, -1);
		File file = new File(temp.getRoot(), "r.-1.-1.mca");
		assertEquals(4, MCAUtil.write(source, file));
		MCAFile region = MCAUtil.read(file);

		assertSame(region.getChunk(0, 0), region.getChunk(-32, -32));
		assertSame(region.getChunk(31, 31), region.getChunk(-1, -1));
		assertCornerEdits(region, -512, -512);

		assertOutsideRegion(() -> region.getBlockStateAt(0, Y, -1));
		assertOutsideRegion(() -> region.getBlockStateAt(-1, Y, 0));
		assertOutsideRegion(() -> region.setBlockStateAt(-513, Y, -1, stone(), false));
		assertOutsideRegion(() -> region.setBlockStateAt(-1, Y, -513, stone(), false));
	}

	@Test
	public void checksRegionAxesIndependently() throws Exception {
		MCAFile region = regionWithCorners(-1, 0);
		assertCornerEdits(region, -512, 0);
		assertOutsideRegion(() -> region.getBlockStateAt(-1, Y, -1));
		assertOutsideRegion(() -> region.setBlockStateAt(0, Y, 511, stone(), false));
	}

	@Test
	public void rejectsOutOfRegionReadsAndWritesWithoutChangingRegionData() throws Exception {
		MCAFile region = regionWithCorners(0, 0);
		File before = new File(temp.newFolder("before"), "r.0.0.mca");
		assertEquals(4, MCAUtil.write(region, before));

		int[][] outside = {{-1, 0}, {512, 0}, {0, -1}, {0, 512}, {512, 16}, {16, 512}};
		for (int[] position : outside) {
			int x = position[0];
			int z = position[1];
			assertOutsideRegion(() -> region.getBlockStateAt(x, Y, z));
			assertOutsideRegion(() -> region.setBlockStateAt(x, Y, z, stone(), false));
		}
		assertNull(region.getChunk(0, 1));
		assertNull(region.getChunk(1, 0));

		File after = new File(temp.newFolder("after"), "r.0.0.mca");
		assertEquals(4, MCAUtil.write(region, after));
		assertArrayEquals(Files.readAllBytes(before.toPath()), Files.readAllBytes(after.toPath()));
	}

	@Test
	public void rejectsFilenameWithoutRegionCoordinates() throws Exception {
		File unnamed = new File(temp.getRoot(), "region.mca");
		Files.copy(fixtureFile().toPath(), unnamed.toPath());
		try {
			MCAUtil.read(unnamed);
			fail("Expected a filename without region coordinates to be rejected");
		} catch (IllegalArgumentException expected) {
			// The filename-based loader requires region coordinates.
		}
	}

	private static void assertCornerEdits(MCAFile region, int firstX, int firstZ) {
		CompoundTag replacement = stone();
		for (int dx : new int[]{0, 511}) {
			for (int dz : new int[]{0, 511}) {
				int x = firstX + dx;
				int z = firstZ + dz;
				assertEquals("minecraft:bedrock", region.getBlockStateAt(x, Y, z).getString("Name"));
				region.setBlockStateAt(x, Y, z, replacement, false);
				assertEquals(replacement, region.getBlockStateAt(x, Y, z));
				assertEquals(replacement, region.getChunk(dx >> 4, dz >> 4).getBlockStateAt(x, Y, z));
			}
		}
	}

	private static MCAFile regionWithCorners(int regionX, int regionZ) throws Exception {
		MCAFile region = new MCAFile(regionX, regionZ);
		for (int x : new int[]{0, 31}) {
			for (int z : new int[]{0, 31}) {
				region.setChunk(x, z, MCAUtil.read(fixtureFile()).getChunk(0, 0));
			}
		}
		return region;
	}

	private static File fixtureFile() throws Exception {
		URL resource = MCAFileCoordinatesTest.class.getResource("palettes/overworld/r.0.0.mca");
		assertNotNull("Missing region fixture r.0.0.mca", resource);
		return new File(resource.toURI());
	}

	private static CompoundTag stone() {
		CompoundTag state = new CompoundTag();
		state.putString("Name", "minecraft:stone");
		return state;
	}

	private static void assertOutsideRegion(Runnable operation) {
		try {
			operation.run();
			fail("Expected out-of-region block coordinates to be rejected");
		} catch (IllegalArgumentException expected) {
			// Both block accessors reject coordinates before selecting a chunk.
		}
	}
}
