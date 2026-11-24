package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static net.querz.mca.RegionTestSupport.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VanillaRegionExportsTest {
	private static File manualSource() {
		File source = new File("manual/input/replacements/overworld/r.0.0.mca");
		assertTrue("Provide the full Minecraft 26.3 Overworld region from the fixture-generation guide at " + source,
				source.isFile());
		return source;
	}

	private static Path newRun(String scenario) throws IOException {
		Path runs = Files.createDirectories(Paths.get("manual", "output", scenario));
		Path run = Files.createTempDirectory(runs, "run-");
		System.out.println("Export directory: " + run.toAbsolutePath());
		return run;
	}

	@Test
	public void exportsPlainPaletteRegion() throws Exception {
		verifyPlainPaletteRoundTrip(manualSource(), newRun("plain-palette"), 1024);
	}

	@Test
	public void exportsMixedPaletteRegion() throws Exception {
		verifyMixedPaletteRegionEdit(manualSource(), newRun("mixed-palette"), 1024);
	}

	@Test
	public void exportsMissingSectionRegion() throws Exception {
		verifyMissingSectionRegionEdit(manualSource(), newRun("missing-section"), 1024);
	}

	@Test
	public void preparesHeightmapComparisonRegions() throws Exception {

		File source = manualSource();
		int firstChunk = 6;
		int endChunk = 9;
		int blockY = -40;
		CompoundTag black = defaultState("minecraft:black_wool");
		CompoundTag white = defaultState("minecraft:white_wool");
		MCAFile region = MCAUtil.read(source);
		for (int x = firstChunk * 16; x < endChunk * 16; x++) {
			for (int z = firstChunk * 16; z < endChunk * 16; z++) {
				region.setBlockStateAt(x, blockY, z, ((x / 8 + z / 8) & 1) == 0 ? black : white, false);
			}
		}

		Path run = newRun("heightmap-ab");
		File a = Files.createDirectory(run.resolve("A-stale")).resolve("r.0.0.mca").toFile();
		File b = Files.createDirectory(run.resolve("B-rebuild")).resolve("r.0.0.mca").toFile();
		assertEquals(1024, MCAUtil.write(region, b));

		// Restore stale entries only for the experimental control.
		for (int z = firstChunk; z < endChunk; z++) {
			for (int x = firstChunk; x < endChunk; x++) {
				CompoundTag maps = region.getChunk(x, z).updateHandle(x, z).getCompoundTag("Heightmaps");
				CompoundTag originalMaps = readChunk(source, x, z).getCompoundTag("Heightmaps");
				for (String key : HEIGHTMAP_KEYS) {
					maps.put(key, originalMaps.get(key));
				}
			}
		}
		assertEquals(1024, MCAUtil.write(region, a));

		assertArrayEquals(regionTimestamps(source), regionTimestamps(a));
		assertArrayEquals(regionTimestamps(source), regionTimestamps(b));
		for (int z = 0; z < 32; z++) {
			for (int x = 0; x < 32; x++) {
				CompoundTag original = readChunk(source, x, z);
				CompoundTag stale = readChunk(a, x, z);
				CompoundTag invalidated = readChunk(b, x, z);
				if (x >= firstChunk && x < endChunk && z >= firstChunk && z < endChunk) {
					verifyHeightmapComparisonChunk(original, stale, invalidated, blockY);
				} else {
					assertEquals("Unrelated chunk in A at " + x + "," + z, original, stale);
					assertEquals("Unrelated chunk in B at " + x + "," + z, original, invalidated);
				}
			}
		}
		System.out.println("Verified heightmap A (stale): " + a.getAbsolutePath());
		System.out.println("Verified heightmap B (rebuild): " + b.getAbsolutePath());
	}

	private static void verifyHeightmapComparisonChunk(CompoundTag original, CompoundTag stale,
			CompoundTag invalidated, int blockY) {
		assertEquals(5023, original.getInt("DataVersion"));
		assertEquals("minecraft:full", original.getString("Status"));
		assertEquals(-4, original.getInt("yPos"));
		CompoundTag staleMaps = stale.getCompoundTag("Heightmaps");
		assertEquals(original.getCompoundTag("Heightmaps"), staleMaps);
		long[] surface = staleMaps.getLongArrayTag("WORLD_SURFACE").getValue();
		assertEquals(37, surface.length);

		CompoundTag expectedB = stale.clone();
		CompoundTag expectedBMaps = expectedB.getCompoundTag("Heightmaps");
		for (String key : HEIGHTMAP_KEYS) {
			assertNotNull(expectedBMaps.remove(key));
		}
		assertEquals("Only the four known heightmaps differ between A and B", expectedB, invalidated);

		int sectionY = blockY >> 4;
		CompoundTag originalSection = sectionAt(original, sectionY);
		assertSingletonStringPalette("minecraft:air", originalSection.getCompoundTag("block_states"));
		CompoundTag states = (CompoundTag) sectionAt(stale, sectionY).remove("block_states");
		originalSection.remove("block_states");
		assertEquals("All unrelated data in the edited chunk", original, stale);

		ListTag<StringTag> expectedPalette = new ListTag<>(StringTag.class);
		expectedPalette.add(new StringTag("minecraft:air"));
		expectedPalette.add(new StringTag("minecraft:black_wool"));
		expectedPalette.add(new StringTag("minecraft:white_wool"));
		assertEquals(expectedPalette, states.getListTag("palette"));
		int[] expectedIndices = new int[4096];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int column = x + 16 * z;
				int firstAvailableOffset = (int) ((surface[column / 7] >>> ((column % 7) * 9)) & 511);
				assertTrue("Original surface must be below the wool", -64 + firstAvailableOffset <= blockY);
				expectedIndices[(blockY & 15) * 256 + column] = 1 + ((x / 8 + z / 8) & 1);
			}
		}
		assertArrayEquals(expectedIndices, unpackIndices(states, 4));
	}
}
