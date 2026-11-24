package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

import static net.querz.mca.RegionTestSupport.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RegionPaletteTest {
	private static final int FIXTURE_PALETTE_SIZE = 6;
	private static final int OVERWORLD_FIXTURE_CHUNKS = 5;
	private static MCAFile modernRegion;
	private static MCAFile olderRegion;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@BeforeClass
	public static void readRegions() throws Exception {
		modernRegion = readFixture("overworld/r.0.0.mca");
		olderRegion = readFixture("legacy/r.0.1.mca");
	}

	@Test
	public void readsPlainStringPalette() {
		CompoundTag expected = new CompoundTag();
		expected.putString("Name", "minecraft:bedrock");
		assertBlockState(modernRegion, 0, -64, 0, expected);
	}

	@Test
	public void readsWrappedStringInMixedPalette() {
		CompoundTag expected = new CompoundTag();
		expected.putString("Name", "minecraft:bedrock");
		assertBlockState(modernRegion, 176, -64, 208, expected);
	}

	@Test
	public void readsStateWithPropertiesInMixedPalette() {
		CompoundTag expected = stateWithProperty("minecraft:wall_torch", "facing", "west");
		assertBlockState(modernRegion, 191, -57, 221, expected);
	}

	@Test
	public void readsOlderCompoundPalette() {
		CompoundTag expected = stateWithProperty("minecraft:deepslate", "axis", "y");
		assertBlockState(olderRegion, 3, -63, 512, expected);
	}

	@Test
	public void returnedBlockStateCannotChangeSharedPaletteOrBypassHeightmapInvalidation() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		CompoundTag original = readChunk(input, 0, 0);
		CompoundTag bedrock = defaultState("minecraft:bedrock");
		assertBlockState(region, 1, -64, 0, bedrock);

		CompoundTag returnedState = region.getBlockStateAt(0, -64, 0);
		returnedState.putString("Name", "minecraft:diamond_block");
		File unchanged = writeRegion(region, "getter-mutation-only");
		assertEquals("Getter mutation must not change saved blocks or heightmaps", original, readChunk(unchanged, 0, 0));
		assertBlockState(region, 0, -64, 0, bedrock);
		assertBlockState(region, 1, -64, 0, bedrock);

		region.setBlockStateAt(0, -64, 0, returnedState, false);
		File output = writeRegion(region, "getter-mutation-applied");
		CompoundTag saved = readChunk(output, 0, 0);
		CompoundTag beforeStates = sectionAt(original, -4).getCompoundTag("block_states");
		CompoundTag afterStates = sectionAt(saved, -4).getCompoundTag("block_states");
		ListTag<StringTag> expectedPalette = beforeStates.getListTag("palette").asStringTagList().clone();
		int[] indices = unpackIndices(beforeStates, 4);
		indices[0] = expectedPalette.size();
		expectedPalette.add(new StringTag("minecraft:diamond_block"));
		assertEquals(expectedPalette, afterStates.getListTag("palette"));
		assertArrayEquals(indices, unpackIndices(afterStates, 4));
		assertHeightmapsInvalidated(original, saved);
		assertUnrelatedChunksUnchanged(input, output, 0, 0);
	}

	@Test
	public void reusedCallerTagDoesNotRewriteEarlierPlacements() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		CompoundTag callerState = stateWithProperty("minecraft:jungle_log", "axis", "x");
		region.setBlockStateAt(176, -64, 208, callerState, false);
		region.setBlockStateAt(177, -64, 208, callerState, false);
		callerState.getCompoundTag("Properties").putString("axis", "z");
		region.setBlockStateAt(178, -64, 208, callerState, true);
		callerState.getCompoundTag("Properties").putString("axis", "y");
		callerState.putString("Name", "minecraft:birch_log");

		File output = writeRegion(region, "caller-mutation");
		CompoundTag original = readChunk(input, 11, 13);
		CompoundTag saved = readChunk(output, 11, 13);
		CompoundTag beforeStates = sectionAt(original, -4).getCompoundTag("block_states");
		CompoundTag afterStates = sectionAt(saved, -4).getCompoundTag("block_states");
		ListTag<CompoundTag> expectedPalette = beforeStates.getListTag("palette").asCompoundTagList().clone();
		int[] indices = unpackIndices(beforeStates, 4);
		indices[0] = expectedPalette.size();
		indices[1] = expectedPalette.size();
		expectedPalette.add(encodedJungleLog("x"));
		indices[2] = expectedPalette.size();
		expectedPalette.add(encodedJungleLog("z"));
		assertEquals(expectedPalette, afterStates.getListTag("palette"));
		assertArrayEquals(indices, unpackIndices(afterStates, 4));
		assertHeightmapsInvalidated(original, saved);

		MCAFile reopened = MCAUtil.read(output);
		CompoundTag logX = stateWithProperty("minecraft:jungle_log", "axis", "x");
		assertBlockState(reopened, 176, -64, 208, logX);
		assertBlockState(reopened, 177, -64, 208, logX);
		assertBlockState(reopened, 178, -64, 208, stateWithProperty("minecraft:jungle_log", "axis", "z"));
	}

	@Test
	public void sectionAccessorsReturnDetachedPaletteAndBlockStates() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readSection(input, 11, 13);
		Section section = new Section(original.clone(), 5023, LoadFlags.ALL_DATA);
		section.getBlockStateAt(191, -57, 221).getCompoundTag("Properties").putString("facing", "east");
		section.blocksStates().iterator().next().putString("Name", "minecraft:diamond_block");
		ListTag<CompoundTag> palette = section.getPalette();
		palette.get(5).getCompoundTag("Properties").putString("facing", "north");
		palette.remove(0);
		assertEquals(original, section.updateHandle());
	}

	@Test
	public void roundTripsMixedAndPlainStringPalettes() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		File output = new File(temp.getRoot(), input.getName());
		assertEquals(OVERWORLD_FIXTURE_CHUNKS, MCAUtil.write(MCAUtil.read(input), output));
		assertEquals(readBlockStates(input, 11, 13), readBlockStates(output, 11, 13));
		assertEquals(readBlockStates(input, 0, 0), readBlockStates(output, 0, 0));
	}

	@Test
	public void roundTripsOlderCompoundPalette() throws Exception {
		File input = fixtureFile("legacy/r.0.1.mca");
		File output = new File(temp.getRoot(), input.getName());
		assertEquals(1, MCAUtil.write(MCAUtil.read(input), output));
		assertEquals(readBlockStates(input, 0, 0), readBlockStates(output, 0, 0));
	}

	@Test
	public void promotesPlainStringPaletteAndEditsItAfterReloading() throws Exception {
		RegionTestSupport.verifyPlainPaletteRoundTrip(fixtureFile("overworld/r.0.0.mca"),
				temp.getRoot().toPath(), OVERWORLD_FIXTURE_CHUNKS);
	}
	@Test
	public void retainsPlainStringPaletteForPropertyFreeEdit() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readBlockStates(input, 0, 0);
		ListTag<StringTag> palette = original.getListTag("palette").asStringTagList();
		int[] indices = unpackIndices(original, 4);
		indices[4 * 256 + 3 * 16 + 3] = palette.size();
		palette.add(new StringTag("minecraft:diamond_block"));

		MCAFile region = MCAUtil.read(input);
		region.setBlockStateAt(3, -60, 3, defaultState("minecraft:diamond_block"), false);
		CompoundTag saved = readBlockStates(writeRegion(region, "still-plain"), 0, 0);
		assertEquals(palette, saved.getListTag("palette"));
		assertArrayEquals(indices, unpackIndices(saved, 4));
	}

	@Test
	public void retainsLegacyCompoundPaletteForPropertyBearingEdit() throws Exception {
		File input = fixtureFile("legacy/r.0.1.mca");
		CompoundTag original = readBlockStates(input, 0, 0);
		ListTag<CompoundTag> expected = original.getListTag("palette").asCompoundTagList();
		assertEquals(16, expected.size());
		int[] indices = unpackIndices(original, 4);
		MCAFile region = MCAUtil.read(input);
		CompoundTag log = stateWithProperty("minecraft:jungle_log", "axis", "x");
		region.setBlockStateAt(3, -63, 512, log, false);
		File output = new File(temp.getRoot(), input.getName());
		assertEquals(1, MCAUtil.write(region, output));

		indices[256 + 3] = expected.size();
		expected.add(log);
		CompoundTag saved = readBlockStates(output, 0, 0);
		assertEquals(expected, saved.getListTag("palette"));
		assertArrayEquals(indices, unpackIndices(saved, 5));
		assertBlockState(MCAUtil.read(output), 3, -63, 512, log);
	}

	@Test
	public void preservesExplicitIdWithoutProperties() throws Exception {
		CompoundTag sectionTag = readSection(fixtureFile("overworld/r.0.0.mca"), 11, 13);
		ListTag<CompoundTag> palette = sectionTag.getCompoundTag("block_states")
				.getListTag("palette").asCompoundTagList();
		CompoundTag explicit = new CompoundTag();
		explicit.putString("id", "minecraft:bedrock");
		palette.set(0, explicit);

		Section section = new Section(sectionTag, 5023, LoadFlags.ALL_DATA);
		assertEquals("minecraft:bedrock", section.getBlockStateAt(0, 0, 0).getString("Name"));
		assertEquals(palette, section.updateHandle().getCompoundTag("block_states").getListTag("palette"));
	}

	@Test
	public void savesEditsToMixedPalette() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readBlockStates(input, 11, 13);
		ListTag<?> originalPalette = original.getListTag("palette");
		int originalSize = originalPalette.size();

		MCAFile region = MCAUtil.read(input);
		CompoundTag log = stateWithProperty("minecraft:jungle_log", "axis", "y");
		CompoundTag torch = stateWithProperty("minecraft:wall_torch", "facing", "west");
		CompoundTag diamond = new CompoundTag();
		diamond.putString("Name", "minecraft:diamond_block");
		region.setBlockStateAt(176, -64, 208, log, false);
		region.setBlockStateAt(177, -64, 208, torch, false);
		region.setBlockStateAt(178, -64, 208, diamond, false);

		File output = new File(temp.getRoot(), input.getName());
		assertEquals(OVERWORLD_FIXTURE_CHUNKS, MCAUtil.write(region, output));
		CompoundTag saved = readBlockStates(output, 11, 13);
		ListTag<CompoundTag> savedPalette = saved.getListTag("palette").asCompoundTagList();
		assertEquals(originalSize + 2, savedPalette.size());
		for (int i = 0; i < originalSize; i++) {
			assertEquals("Original palette entry " + i, originalPalette.get(i), savedPalette.get(i));
		}
		CompoundTag expectedLog = new CompoundTag();
		expectedLog.putString("id", "minecraft:jungle_log");
		expectedLog.put("properties", log.getCompoundTag("Properties"));
		assertEquals(expectedLog, savedPalette.get(originalSize));
		CompoundTag expectedDiamond = new CompoundTag();
		expectedDiamond.putString("", "minecraft:diamond_block");
		assertEquals(expectedDiamond, savedPalette.get(originalSize + 1));

		long[] originalIndices = original.getLongArrayTag("data").getValue();
		long[] savedIndices = saved.getLongArrayTag("data").getValue();
		// The fixture and edited section both use four-bit indices, with sixteen entries per long.
		assertEquals(256, originalIndices.length);
		assertEquals(256, savedIndices.length);
		assertEquals(originalSize, fourBitIndex(savedIndices, 0));
		assertEquals(5, fourBitIndex(savedIndices, 1));
		assertEquals(originalSize + 1, fourBitIndex(savedIndices, 2));
		for (int i = 3; i < 4096; i++) {
			assertEquals("Unedited block index " + i,
					fourBitIndex(originalIndices, i), fourBitIndex(savedIndices, i));
		}

		MCAFile reopened = MCAUtil.read(output);
		assertBlockState(reopened, 176, -64, 208, log);
		assertBlockState(reopened, 177, -64, 208, torch);
		assertBlockState(reopened, 178, -64, 208, diamond);
	}

	@Test
	public void preparesMinecraft263IntegrationRegion() throws Exception {
		RegionTestSupport.verifyMixedPaletteRegionEdit(fixtureFile("overworld/r.0.0.mca"),
				temp.getRoot().toPath(), OVERWORLD_FIXTURE_CHUNKS);
	}
	@Test
	public void preparesMissingSectionMinecraft263IntegrationRegion() throws Exception {
		RegionTestSupport.verifyMissingSectionRegionEdit(fixtureFile("overworld/r.0.0.mca"),
				temp.getRoot().toPath(), OVERWORLD_FIXTURE_CHUNKS);
	}
	@Test
	public void editsVanillaNetherRegion() throws Exception {
		File input = fixtureFile("nether/r.0.0.mca");
		ListTag<CompoundTag> fluidTicks = readChunk(input, 7, 1).getListTag("fluid_ticks").asCompoundTagList();
		assertEquals(5, fluidTicks.size());
		for (int i = 0; i < fluidTicks.size(); i++) {
			CompoundTag tick = fluidTicks.get(i);
			assertEquals("minecraft:flowing_lava", tick.getString("i"));
			assertEquals(117 + i * 2, tick.getInt("x"));
			assertEquals(85, tick.getInt("y"));
			assertEquals(21, tick.getInt("z"));
		}
		CompoundTag biomes = sectionAt(readChunk(input, 8, 9), 4).getCompoundTag("biomes");
		ListTag<StringTag> palette = biomes.getListTag("palette").asStringTagList();
		assertEquals(2, palette.size());
		assertEquals("minecraft:nether_wastes", palette.get(0).getValue());
		assertEquals("minecraft:crimson_forest", palette.get(1).getValue());
		assertArrayEquals(new long[]{0xCCCCCCCCCCCCCCCCL}, biomes.getLongArrayTag("data").getValue());
		assertDimensionEdit(input, 120, 83, 24, "minecraft:netherrack");
	}

	@Test
	public void editsVanillaEndRegion() throws Exception {
		File input = fixtureFile("end/r.0.0.mca");
		ListTag<CompoundTag> portals = readChunk(input, 0, 0).getListTag("block_entities").asCompoundTagList();
		assertEquals(14, portals.size());
		int preparedPortals = 0;
		for (CompoundTag portal : portals) {
			assertEquals("minecraft:end_portal", portal.getString("id"));
			if (portal.getInt("y") == 84 && portal.getInt("z") == 10) {
				int x = portal.getInt("x");
				assertTrue(x >= 5 && x <= 11);
				preparedPortals |= 1 << (x - 5);
			} else {
				assertEquals(60, portal.getInt("y"));
			}
		}
		assertEquals(0b1111111, preparedPortals);
		CompoundTag lightingOnly = sectionAt(readChunk(input, 0, 4), -1);
		assertEquals(2, lightingOnly.size());
		assertEquals(2048, lightingOnly.getByteArrayTag("SkyLight").getValue().length);

		File output = assertDimensionEdit(input, 8, 83, 8, "minecraft:end_stone");
		assertEquals("All portal block entities", portals, readChunk(output, 0, 0).getListTag("block_entities"));
		assertEquals("Untouched lighting-only section", lightingOnly, sectionAt(readChunk(output, 0, 4), -1));
	}

	private File assertDimensionEdit(File input, int x, int y, int z, String originalBlock) throws Exception {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		CompoundTag original = readChunk(input, chunkX, chunkZ);
		assertEquals(5023, original.getInt("DataVersion"));
		assertEquals("minecraft:full", original.getString("Status"));

		MCAFile region = MCAUtil.read(input);
		CompoundTag diamond = defaultState("minecraft:diamond_block");
		assertBlockState(region, x, y, z, defaultState(originalBlock));
		region.setBlockStateAt(x, y, z, diamond, false);
		File output = writeRegion(region, "edited-dimension", 2);
		assertBlockState(MCAUtil.read(output), x, y, z, diamond);

		CompoundTag saved = readChunk(output, chunkX, chunkZ);
		CompoundTag beforeStates = sectionAt(original, y >> 4).getCompoundTag("block_states");
		CompoundTag afterStates = sectionAt(saved, y >> 4).getCompoundTag("block_states");
		int[] before = unpackIndices(beforeStates, 4);
		int[] after = unpackIndices(afterStates, 4);
		ListTag<?> beforePalette = beforeStates.getListTag("palette");
		ListTag<?> afterPalette = afterStates.getListTag("palette");
		assertEquals(beforePalette.getTypeClass(), afterPalette.getTypeClass());
		Tag<?> expectedDiamond = afterPalette.getTypeClass() == StringTag.class
				? new StringTag("minecraft:diamond_block") : wrappedState("minecraft:diamond_block");
		int editedIndex = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		for (int i = 0; i < before.length; i++) {
			assertEquals("Serialized block at index " + i, i == editedIndex ? expectedDiamond : beforePalette.get(before[i]),
					afterPalette.get(after[i]));
		}

		for (CompoundTag states : new CompoundTag[]{beforeStates, afterStates}) {
			states.remove("palette");
			states.remove("data");
		}
		assertHeightmapsInvalidated(original, saved);
		assertEquals("Preserved unrelated chunk NBT", original, saved);
		assertUnrelatedChunksUnchanged(input, output, chunkX, chunkZ);
		return output;
	}

	@Test
	public void preservesBlockEntitiesAndScheduledTicksInEditedChunks() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag chestChunk = readChunk(input, 11, 16);
		CompoundTag tickChunk = readChunk(input, 12, 18);
		ListTag<CompoundTag> blockEntities = chestChunk.getListTag("block_entities").asCompoundTagList();
		assertEquals(1, blockEntities.size());
		assertEquals("minecraft:chest", blockEntities.get(0).getString("id"));
		assertTrue(blockEntities.get(0).getListTag("Items").size() > 0);
		assertEquals(0, tickChunk.getListTag("block_entities").size());
		ListTag<CompoundTag> leafTicks = tickChunk.getListTag("block_ticks").asCompoundTagList();
		assertEquals(40, leafTicks.size());
		for (CompoundTag tick : leafTicks) {
			assertEquals("minecraft:oak_leaves", tick.getString("i"));
			assertEquals(1, tick.getInt("t"));
		}
		assertEquals(26, sectionAt(chestChunk, -4).getCompoundTag("block_states").getListTag("palette").size());
		assertEquals(35, sectionAt(tickChunk, -4).getCompoundTag("block_states").getListTag("palette").size());

		ListTag<CompoundTag> inventoryChest = readChunk(input, 13, 16).getListTag("block_entities").asCompoundTagList();
		assertEquals(1, inventoryChest.size());
		assertEquals("minecraft:chest", inventoryChest.get(0).getString("id"));
		assertEquals(210, inventoryChest.get(0).getInt("x"));
		assertEquals(-56, inventoryChest.get(0).getInt("y"));
		assertEquals(264, inventoryChest.get(0).getInt("z"));
		ListTag<CompoundTag> inventory = inventoryChest.get(0).getListTag("Items").asCompoundTagList();
		assertEquals(2, inventory.size());
		assertInventoryItem(inventory.get(0), 0, "minecraft:stone", 3);
		assertInventoryItem(inventory.get(1), 9, "minecraft:stick", 2);

		verifyMetadataEdits(input, "vanilla-metadata");
		File variant = metadataVariant(input, chestChunk, tickChunk);
		verifyMetadataEdits(variant, "synthetic-metadata");
	}

	private static void assertInventoryItem(CompoundTag item, int slot, String id, int count) {
		assertEquals(slot, item.getByte("Slot"));
		assertEquals(id, item.getString("id"));
		assertEquals(count, item.getInt("count"));
	}

	private void verifyMetadataEdits(File input, String name) throws Exception {
		CompoundTag chestChunk = readChunk(input, 11, 16);
		CompoundTag tickChunk = readChunk(input, 12, 18);
		CompoundTag inventoryChunk = readChunk(input, 13, 16);

		MCAFile region = MCAUtil.read(input);
		CompoundTag diamond = defaultState("minecraft:diamond_block");
		region.setBlockStateAt(179, -60, 259, diamond, false);
		region.setBlockStateAt(195, -60, 291, diamond, false);
		region.setBlockStateAt(211, -60, 259, diamond, false);
		File output = writeRegion(region, name);

		MCAFile reopened = MCAUtil.read(output);
		assertBlockState(reopened, 179, -60, 259, diamond);
		assertBlockState(reopened, 195, -60, 291, diamond);
		assertBlockState(reopened, 211, -60, 259, diamond);
		assertMetadataPreservingEdit(chestChunk, readChunk(output, 11, 16), 5);
		assertMetadataPreservingEdit(tickChunk, readChunk(output, 12, 18), 6);
		assertMetadataPreservingEdit(inventoryChunk, readChunk(output, 13, 16), 4);
		assertEquals(readChunk(input, 0, 0), readChunk(output, 0, 0));
		assertEquals(readChunk(input, 11, 13), readChunk(output, 11, 13));
		assertArrayEquals(regionTimestamps(input), regionTimestamps(output));
	}

	private File metadataVariant(File input, CompoundTag chestChunk, CompoundTag tickChunk) throws IOException {
		CompoundTag chest = chestChunk.getListTag("block_entities").asCompoundTagList().get(0);
		assertNotNull(chest.remove("Items"));
		chest.putString("LootTable", "minecraft:chests/simple_dungeon");
		chest.putLong("LootTableSeed", 123L);
		for (CompoundTag tick : tickChunk.getListTag("block_ticks").asCompoundTagList()) {
			tick.putInt("t", -188);
		}

		CompoundTag litChunk = readChunk(input, 11, 13);
		for (CompoundTag chunk : new CompoundTag[]{chestChunk, tickChunk}) {
			chunk.getCompoundTag("structures").getCompoundTag("References")
					.putLongArray("minecraft:village_plains", new long[]{10L | (16L << 32)});
			for (int y = -4; y <= -3; y++) {
				sectionAt(chunk, y).put("BlockLight", sectionAt(litChunk, y).getByteArrayTag("BlockLight"));
			}
		}

		File copy = new File(temp.newFolder(), input.getName());
		Files.copy(input.toPath(), copy.toPath());
		replaceChunkInFixtureCopy(copy, 11, 16, chestChunk);
		replaceChunkInFixtureCopy(copy, 12, 18, tickChunk);
		assertEquals(chestChunk, readChunk(copy, 11, 16));
		assertEquals(tickChunk, readChunk(copy, 12, 18));
		return copy;
	}

	@Test
	public void preservesAbsentBlockEntitiesDuringOrdinaryBlockEdit() throws Exception {
		File input = fixtureCopyWithoutBlockEntities();
		CompoundTag original = readChunk(input, 12, 18);
		assertNull(original.get("block_entities"));
		MCAFile region = MCAUtil.read(input);

		CompoundTag diamond = defaultState("minecraft:diamond_block");
		region.setBlockStateAt(195, -60, 291, diamond, false);
		File output = writeRegion(region, "absent-block-entities-output");

		assertBlockState(MCAUtil.read(output), 195, -60, 291, diamond);
		assertMetadataPreservingEdit(original, readChunk(output, 12, 18), 6);
		assertUnrelatedChunksUnchanged(input, output, 12, 18);
	}

	@Test
	public void createsBlockEntityListWhenAddingFirstBlockEntity() throws Exception {
		MCAFile region = MCAUtil.read(fixtureCopyWithoutBlockEntities());
		region.createMonsterSpawner(195, -60, 291, MinecraftMob.ZOMBIE, (short) 4, (short) 4,
				(short) 200, (short) 800, (short) 16, (short) 6, (short) 20);
		File output = writeRegion(region, "new-block-entity-list");

		CompoundTag saved = readChunk(output, 12, 18);
		ListTag<CompoundTag> blockEntities = saved.getListTag("block_entities").asCompoundTagList();
		assertEquals(1, blockEntities.size());
		CompoundTag spawner = blockEntities.get(0);
		assertEquals("minecraft:mob_spawner", spawner.getString("id"));
		assertEquals(195, spawner.getInt("x"));
		assertEquals(-60, spawner.getInt("y"));
		assertEquals(291, spawner.getInt("z"));
		assertBlockState(MCAUtil.read(output), 195, -60, 291, defaultState("minecraft:spawner"));
	}

	private File fixtureCopyWithoutBlockEntities() throws Exception {
		File source = fixtureFile("overworld/r.0.0.mca");
		CompoundTag chunk = readChunk(source, 12, 18);
		assertNotNull(chunk.remove("block_entities"));
		File input = new File(temp.newFolder("absent-block-entities-input"), source.getName());
		Files.copy(source.toPath(), input.toPath());
		replaceChunkInFixtureCopy(input, 12, 18, chunk);
		return input;
	}

	private static void assertMetadataPreservingEdit(CompoundTag original, CompoundTag saved, int bits) {
		assertEquals(5023, original.getInt("DataVersion"));
		assertEquals("Preserved block_entities", original.get("block_entities"), saved.get("block_entities"));
		for (String key : new String[]{"block_ticks", "fluid_ticks"}) {
			assertNotNull("Fixture must contain " + key, original.getListTag(key));
			assertEquals("Preserved " + key, original.getListTag(key), saved.getListTag(key));
		}
		CompoundTag originalStates = sectionAt(original, -4).getCompoundTag("block_states");
		CompoundTag savedStates = sectionAt(saved, -4).getCompoundTag("block_states");
		int[] expectedIndices = unpackIndices(originalStates, bits);
		ListTag<CompoundTag> expectedPalette = originalStates.getListTag("palette").asCompoundTagList();
		int editedIndex = 4 * 256 + 3 * 16 + 3; // Both edits are at local (3, 4, 3).
		assertEquals(wrappedState("minecraft:air"), expectedPalette.get(expectedIndices[editedIndex]));
		expectedIndices[editedIndex] = expectedPalette.size();
		expectedPalette.add(wrappedState("minecraft:diamond_block"));
		assertEquals("Serialized block palette", expectedPalette, savedStates.getListTag("palette"));
		assertArrayEquals("Only the intended block index changed", expectedIndices, unpackIndices(savedStates, bits));

		for (CompoundTag states : new CompoundTag[]{originalStates, savedStates}) {
			states.remove("palette");
			states.remove("data");
		}
		assertHeightmapsInvalidated(original, saved);
		assertEquals("Preserved all other chunk NBT, including biomes and lighting", original, saved);
	}

	@Test
	public void invalidatesKnownHeightmapsAndPreservesUnknownEntries() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		long[] customHeights = {9L, 17L};
		region.getChunk(11, 13).updateHandle(11, 13).getCompoundTag("Heightmaps")
				.putLongArray("custom_heightmap", customHeights);
		CompoundTag original = readChunk(input, 11, 13);
		original.getCompoundTag("Heightmaps").putLongArray("custom_heightmap", customHeights);

		region.setBlockStateAt(191, -57, 221, stateWithProperty("minecraft:wall_torch", "facing", "east"), false);
		File output = writeRegion(region, "invalidated-heightmaps");
		CompoundTag saved = readChunk(output, 11, 13);
		assertHeightmapsInvalidated(original, saved);
		sectionAt(original, -4).remove("block_states");
		sectionAt(saved, -4).remove("block_states");
		assertEquals(original, saved);
		assertUnrelatedChunksUnchanged(input, output, 11, 13);
	}

	@Test
	public void preservesStoredLightingAndAnAbsentLightingFlagAfterBlockEdit() throws Exception {
		File fixture = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readChunk(fixture, 11, 13);
		CompoundTag originalSection = sectionAt(original, -4);
		assertNotNull(originalSection.getByteArrayTag("SkyLight"));
		assertNotNull(originalSection.getByteArrayTag("BlockLight"));
		CompoundTag neighbor = original.clone();
		neighbor.putInt("xPos", 12);
		assertNotNull(original.remove("isLightOn"));

		File input = new File(temp.newFolder("unlit-input"), fixture.getName());
		Files.copy(fixture.toPath(), input.toPath());
		replaceChunkInFixtureCopy(input, 11, 13, original);
		MCAFile region = MCAUtil.read(input);
		region.setChunk(12, 13, new Chunk(neighbor.clone()));
		CompoundTag air = defaultState("minecraft:air");
		region.setBlockStateAt(191, -57, 221, air, false);
		File output = new File(temp.newFolder("preserved-lighting"), fixture.getName());
		assertEquals(OVERWORLD_FIXTURE_CHUNKS + 1, MCAUtil.write(region, output));
		assertBlockState(MCAUtil.read(output), 191, -57, 221, air);

		CompoundTag saved = readChunk(output, 11, 13);
		assertNull(saved.get("isLightOn"));
		assertHeightmapsInvalidated(original, saved);
		originalSection.remove("block_states");
		sectionAt(saved, -4).remove("block_states");
		assertEquals("Stored lighting and unrelated NBT in the edited chunk", original, saved);
		assertEquals("Adjacent chunk, including its lighting flag and arrays", neighbor, readChunk(output, 12, 13));
	}

	@Test
	public void clearingChunkInvalidatesHeightmapsAndPreservesOtherData() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		MCAFile region = MCAUtil.read(input);
		Chunk chunk = region.getChunk(11, 13);
		CompoundTag handle = chunk.updateHandle(11, 13);
		CompoundTag original = readChunk(input, 11, 13);
		for (CompoundTag root : new CompoundTag[]{handle, original}) {
			root.getCompoundTag("Heightmaps").putLongArray("custom_heightmap", new long[]{9L, 17L});
			sectionAt(root, -4).getCompoundTag("block_states").putString("custom_section_data", "keep");
		}
		assertBlockState(region, 176, -64, 208, defaultState("minecraft:bedrock"));
		chunk.clearChunk();
		File output = writeRegion(region, "cleared-chunk");
		CompoundTag saved = readChunk(output, 11, 13);
		assertBlockState(MCAUtil.read(output), 176, -64, 208, defaultState("minecraft:air"));
		assertHeightmapsInvalidated(original, saved);

		for (CompoundTag sourceSection : original.getListTag("sections").asCompoundTagList()) {
			CompoundTag before = sourceSection.getCompoundTag("block_states");
			if (before == null) {
				continue;
			}
			CompoundTag after = sectionAt(saved, sourceSection.getByte("Y")).getCompoundTag("block_states");
			ListTag<?> palette = after.getListTag("palette");
			assertEquals(1, palette.size());
			assertEquals(before.getListTag("palette").getTypeClass() == StringTag.class
					? new StringTag("minecraft:air") : wrappedState("minecraft:air"), palette.get(0));
			if (after.getLongArrayTag("data") != null) {
				assertArrayEquals(new int[4096], unpackIndices(after, 4));
			}
			for (CompoundTag states : new CompoundTag[]{before, after}) {
				states.remove("palette");
				states.remove("data");
			}
		}
		assertEquals("Unrelated metadata in the cleared chunk", original, saved);
		assertUnrelatedChunksUnchanged(input, output, 11, 13);
	}

	@Test
	public void clearingAlreadyAirChunkKeepsHeightmapsEvenWithUnusedNonAirPaletteEntry() throws Exception {
		File fixture = fixtureFile("overworld/r.0.0.mca");
		CompoundTag airChunk = readChunk(fixture, 0, 0);
		for (CompoundTag section : airChunk.getListTag("sections").asCompoundTagList()) {
			CompoundTag states = section.getCompoundTag("block_states");
			if (states == null) {
				continue;
			}
			ListTag<StringTag> palette = new ListTag<>(StringTag.class);
			palette.add(new StringTag("minecraft:air"));
			if (section.getByte("Y") == -4) {
				palette.add(new StringTag("minecraft:bedrock"));
				states.putLongArray("data", new long[256]);
			} else {
				states.remove("data");
			}
			states.put("palette", palette);
		}
		MCAFile region = MCAUtil.read(fixture);
		region.setChunk(0, 0, new Chunk(airChunk));
		File before = writeRegion(region, "clear-noop-before");
		region.getChunk(0, 0).clearChunk();
		File after = writeRegion(region, "clear-noop-after");
		assertArrayEquals(Files.readAllBytes(before.toPath()), Files.readAllBytes(after.toPath()));
	}

	@Test
	public void preservesHeightmapsOnNoOpAndRejectedEdits() throws Exception {
		MCAFile region = readFixture("overworld/r.0.0.mca");
		File before = writeRegion(region, "heightmaps-before");
		region.setBlockStateAt(191, -57, 221, stateWithProperty("minecraft:wall_torch", "facing", "west"), false);
		CompoundTag air = defaultState("minecraft:air");
		air.put("Properties", new CompoundTag());
		region.setBlockStateAt(176, -48, 208, air, false);
		try {
			region.setBlockStateAt(176, 2048, 208, air, false);
			fail("Expected an out-of-range section Y to be rejected");
		} catch (IllegalArgumentException expected) {
			// The edit fails before changing the existing chunk.
		}
		File after = writeRegion(region, "heightmaps-after");
		assertEquals(readChunk(before, 11, 13), readChunk(after, 11, 13));
	}

	private static int fourBitIndex(long[] packed, int blockIndex) {
		return (int) ((packed[blockIndex / 16] >>> ((blockIndex % 16) * 4)) & 15);
	}

	@Test
	public void repacksFromSixteenToSeventeenStates() throws Exception {
		assertPaletteGrowth(16, 4, 5);
	}

	@Test
	public void repacksFromThirtyTwoToThirtyThreeStates() throws Exception {
		assertPaletteGrowth(32, 5, 6);
	}

	private void assertPaletteGrowth(int sizeBefore, int bitsBefore, int bitsAfter) throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readBlockStates(input, 11, 13);
		MCAFile region = MCAUtil.read(input);
		int[] indices = unpackIndices(original, 4);
		ListTag<CompoundTag> expectedPalette = original.getListTag("palette").asCompoundTagList();
		growPalette(region, expectedPalette, indices, sizeBefore);
		assertSerializedSection(region, "before", expectedPalette, indices, bitsBefore);
		growPalette(region, expectedPalette, indices, sizeBefore + 1);
		assertSerializedSection(region, "after", expectedPalette, indices, bitsAfter);
	}

	@Test
	public void cleanupRemovesAnUnusedStateAndShrinksPackedIndices() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readBlockStates(input, 11, 13);
		ListTag<CompoundTag> expectedPalette = original.getListTag("palette").asCompoundTagList();
		int removedIndex = expectedPalette.size();
		int[] indices = unpackIndices(original, 4);
		MCAFile region = MCAUtil.read(input);
		growPalette(region, expectedPalette, indices, 17);
		assertSerializedSection(region, "before-cleanup", expectedPalette, indices, 5);

		region.setBlockStateAt(176, -64, 208, defaultState("minecraft:bedrock"), false);
		region.cleanupPalettesAndBlockStates();
		indices[0] = 0;
		expectedPalette.remove(removedIndex);
		for (int i = 0; i < indices.length; i++) {
			if (indices[i] > removedIndex) {
				indices[i]--;
			}
		}
		assertSerializedSection(region, "after-cleanup", expectedPalette, indices, 4);
	}

	@Test
	public void savesAndReloadsEditsAcrossNegativeSectionBoundary() throws Exception {
		File input = fixtureFile("overworld/r.0.0.mca");
		CompoundTag original = readChunk(input, 11, 13);
		MCAFile region = MCAUtil.read(input);
		CompoundTag diamond = defaultState("minecraft:diamond_block");
		CompoundTag emerald = defaultState("minecraft:emerald_block");
		region.setBlockStateAt(176, -49, 208, diamond, false);
		region.setBlockStateAt(176, -48, 208, emerald, false);
		File output = writeRegion(region, "negative-boundary");

		CompoundTag saved = readChunk(output, 11, 13);
		CompoundTag originalBelow = sectionAt(original, -4).getCompoundTag("block_states");
		CompoundTag savedBelow = sectionAt(saved, -4).getCompoundTag("block_states");
		int[] expectedBelow = unpackIndices(originalBelow, 4);
		ListTag<CompoundTag> expectedBelowPalette = originalBelow.getListTag("palette").asCompoundTagList().clone();
		expectedBelow[15 * 256] = expectedBelowPalette.size();
		expectedBelowPalette.add(wrappedState("minecraft:diamond_block"));
		assertEquals(expectedBelowPalette, savedBelow.getListTag("palette"));
		assertArrayEquals(expectedBelow, unpackIndices(savedBelow, 4));

		CompoundTag originalAbove = sectionAt(original, -3).getCompoundTag("block_states");
		CompoundTag savedAbove = sectionAt(saved, -3).getCompoundTag("block_states");
		ListTag<?> originalAbovePalette = originalAbove.getListTag("palette");
		assertEquals(1, originalAbovePalette.size());
		assertEquals(2, savedAbove.getListTag("palette").size());
		assertEquals(originalAbovePalette.get(0), savedAbove.getListTag("palette").get(0));
		assertEquals(new StringTag("minecraft:emerald_block"), savedAbove.getListTag("palette").get(1));
		int[] expectedAbove = new int[4096];
		expectedAbove[0] = 1;
		assertArrayEquals(expectedAbove, unpackIndices(savedAbove, 4));

		ListTag<CompoundTag> originalSections = original.getListTag("sections").asCompoundTagList();
		assertEquals(originalSections.size(), saved.getListTag("sections").size());
		for (CompoundTag section : originalSections) {
			int y = section.getByte("Y");
			CompoundTag savedSection = sectionAt(saved, y);
			if (y == -4 || y == -3) {
				section.remove("block_states");
				savedSection.remove("block_states");
			}
			assertEquals("Preserved section data at Y=" + y, section, savedSection);
		}
		original.remove("sections");
		saved.remove("sections");
		assertHeightmapsInvalidated(original, saved);
		assertEquals("Preserved edited chunk metadata", original, saved);
		assertEquals("Preserved unrelated chunk", readChunk(input, 0, 0), readChunk(output, 0, 0));

		MCAFile reopened = MCAUtil.read(output);
		assertBlockState(reopened, 176, -49, 208, diamond);
		assertBlockState(reopened, 176, -48, 208, emerald);
		assertBlockState(reopened, 0, -64, 0, defaultState("minecraft:bedrock"));
	}

	@Test
	public void savesNewNegativeSectionInExistingChunk() throws Exception {
		File fixture = fixtureFile("overworld/r.0.0.mca");
		CompoundTag chunkWithGap = readChunk(fixture, 11, 13);
		removeSection(chunkWithGap, -3);
		MCAFile baseline = MCAUtil.read(fixture);
		baseline.setChunk(11, 13, new Chunk(chunkWithGap));
		File input = writeRegion(baseline, "missing-section-input");
		MCAFile region = MCAUtil.read(input);

		assertNull(region.getBlockStateAt(176, -48, 208));
		region.setBlockStateAt(176, -48, 208, defaultState("minecraft:air"), false);
		assertNull("Setting implicit air must not create a section", region.getBlockStateAt(176, -48, 208));
		CompoundTag log = stateWithProperty("minecraft:jungle_log", "axis", "y");
		CompoundTag diamond = defaultState("minecraft:diamond_block");
		region.setBlockStateAt(176, -48, 208, log, false);
		region.setBlockStateAt(191, -33, 223, diamond, false);
		File output = writeRegion(region, "missing-section-output");

		CompoundTag saved = readChunk(output, 11, 13);
		CompoundTag newSection = sectionAt(saved, -3);
		assertNull(newSection.get("SkyLight"));
		assertNull(newSection.get("BlockLight"));
		CompoundTag states = newSection.getCompoundTag("block_states");
		assertNotNull("New section must contain block_states", states);
		ListTag<CompoundTag> expectedPalette = new ListTag<>(CompoundTag.class);
		expectedPalette.add(wrappedState("minecraft:air"));
		CompoundTag expectedLog = new CompoundTag();
		expectedLog.putString("id", "minecraft:jungle_log");
		expectedLog.put("properties", log.getCompoundTag("Properties"));
		expectedPalette.add(expectedLog);
		expectedPalette.add(wrappedState("minecraft:diamond_block"));
		assertEquals(expectedPalette, states.getListTag("palette"));
		int[] expectedIndices = new int[4096];
		expectedIndices[0] = 1;
		expectedIndices[4095] = 2;
		assertArrayEquals(expectedIndices, unpackIndices(states, 4));
		removeSection(saved, -3);
		CompoundTag original = readChunk(input, 11, 13);
		assertEquals(1, original.getByte("isLightOn"));
		assertNotNull(sectionAt(original, -4).getByteArrayTag("SkyLight"));
		assertNotNull(sectionAt(original, -4).getByteArrayTag("BlockLight"));
		assertHeightmapsInvalidated(original, saved);
		assertEquals("Existing chunk metadata and sections", original, saved);
		assertEquals("Unrelated chunk", readChunk(input, 0, 0), readChunk(output, 0, 0));
		assertArrayEquals(regionTimestamps(input), regionTimestamps(output));

		MCAFile reopened = MCAUtil.read(output);
		assertBlockState(reopened, 176, -48, 208, log);
		assertBlockState(reopened, 191, -33, 223, diamond);
		assertBlockState(reopened, 177, -48, 208, defaultState("minecraft:air"));
	}

	@Test
	public void rejectsBlockEditInMissingChunkWithoutCreatingIt() throws Exception {
		MCAFile region = readFixture("overworld/r.0.0.mca");
		File before = writeRegion(region, "missing-chunk-before");
		assertNull(region.getChunk(1, 0));
		try {
			region.setBlockStateAt(16, -48, 0, defaultState("minecraft:diamond_block"), false);
			fail("Expected a block edit in a missing chunk to be rejected");
		} catch (IllegalStateException expected) {
			// These edits require existing terrain chunks.
		}
		assertNull(region.getChunk(1, 0));
		File after = writeRegion(region, "missing-chunk-after");
		assertArrayEquals(Files.readAllBytes(before.toPath()), Files.readAllBytes(after.toPath()));
	}

	@Test
	public void readsAndWritesElevenBitIndices() {
		assertReadsAndWritesWideIndices(1025, 11);
	}

	@Test
	public void readsAndWritesTwelveBitIndices() {
		assertReadsAndWritesWideIndices(2049, 12);
	}

	private static void assertReadsAndWritesWideIndices(int paletteSize, int bits) {
		CompoundTag rawSection = wideSectionTag(paletteSize, bits);
		int[] expected = wideIndexPattern(paletteSize);
		Section section = new Section(rawSection, 5023, LoadFlags.ALL_DATA);
		assertSectionIndices(section, expected);

		long[] written = new long[820];
		for (int i = 0; i < expected.length; i++) {
			int index = expected.length - 1 - i;
			section.setPaletteIndex(i, expected[index], written);
		}
		section.setBlockStates(written);
		int[] reversed = new int[expected.length];
		for (int i = 0; i < reversed.length; i++) {
			reversed[i] = expected[expected.length - 1 - i];
		}
		assertArrayEquals(reversed, unpackIndices(section.updateHandle().getCompoundTag("block_states"), bits));
		assertSectionIndices(new Section(section.updateHandle(), 5023, LoadFlags.ALL_DATA), reversed);
	}

	@Test
	public void growsAndCleansUpAcrossElevenAndTwelveBits() {
		Section section = new Section(wideSectionTag(2048, 11), 5023, LoadFlags.ALL_DATA);
		int[] expected = wideIndexPattern(2048);

		section.setBlockStateAt(0, -64, 0, defaultState("test:state_2048"), false);
		expected[0] = 2048;
		assertEquals(2049, section.getPalette().size());
		assertArrayEquals(expected, unpackIndices(section.updateHandle().getCompoundTag("block_states"), 12));
		assertSectionIndices(section, expected);

		section.setBlockStateAt(0, -64, 0, defaultState("test:state_0"), true);
		expected[0] = 0;
		assertEquals(2048, section.getPalette().size());
		assertArrayEquals(expected, unpackIndices(section.updateHandle().getCompoundTag("block_states"), 11));
		assertSectionIndices(section, expected);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsPackedLengthThatDoesNotMatchPalette() {
		CompoundTag rawSection = wideSectionTag(1025, 11);
		rawSection.getCompoundTag("block_states").putLongArray("data", new long[819]);
		new Section(rawSection, 5023, LoadFlags.ALL_DATA);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsReplacingPackedDataWithWrongLength() {
		Section section = new Section(wideSectionTag(2049, 12), 5023, LoadFlags.ALL_DATA);
		section.setBlockStates(new long[821]);
	}

	private static CompoundTag wideSectionTag(int paletteSize, int bits) {
		ListTag<CompoundTag> palette = new ListTag<>(CompoundTag.class);
		for (int i = 0; i < paletteSize; i++) {
			palette.add(defaultState("test:state_" + i));
		}
		int[] expected = wideIndexPattern(paletteSize);
		long[] packed = new long[820];
		for (int i = 0; i < expected.length; i++) {
			packed[i / 5] |= (long) expected[i] << ((i % 5) * bits);
		}
		CompoundTag states = new CompoundTag();
		states.put("palette", palette);
		states.putLongArray("data", packed);
		CompoundTag rawSection = new CompoundTag();
		rawSection.putByte("Y", (byte) -4);
		rawSection.put("block_states", states);
		return rawSection;
	}

	private static int[] wideIndexPattern(int paletteSize) {
		int[] indices = new int[4096];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = i % paletteSize;
		}
		return indices;
	}

	private static void assertSectionIndices(Section section, int[] expected) {
		int[] actual = new int[4096];
		for (int i = 0; i < actual.length; i++) {
			actual[i] = section.getPaletteIndex(i);
		}
		assertArrayEquals(expected, actual);
	}

	private static void growPalette(MCAFile region, ListTag<CompoundTag> palette, int[] indices, int targetSize) {
		String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
				"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
		for (int paletteIndex = palette.size(); paletteIndex < targetSize; paletteIndex++) {
			int added = paletteIndex - FIXTURE_PALETTE_SIZE;
			String name = "minecraft:" + colors[added % colors.length]
					+ (added < colors.length ? "_wool" : "_concrete");
			int blockIndex = added * 7;
			region.setBlockStateAt(176 + (blockIndex % 16), -64, 208 + (blockIndex / 16),
					defaultState(name), false);
			palette.add(wrappedState(name));
			indices[blockIndex] = paletteIndex;
		}
	}

	private void assertSerializedSection(MCAFile region, String name, ListTag<CompoundTag> palette,
										int[] indices, int bits) throws IOException {
		CompoundTag saved = readBlockStates(writeRegion(region, name), 11, 13);
		assertEquals(palette, saved.getListTag("palette"));
		assertArrayEquals(indices, unpackIndices(saved, bits));
	}

	private File writeRegion(MCAFile region, String name) throws IOException {
		return writeRegion(region, name, OVERWORLD_FIXTURE_CHUNKS);
	}

	private File writeRegion(MCAFile region, String name, int expectedChunks) throws IOException {
		File output = new File(temp.newFolder(name), "r.0.0.mca");
		assertEquals(expectedChunks, MCAUtil.write(region, output));
		return output;
	}

	private static CompoundTag readBlockStates(File file, int localChunkX, int localChunkZ) throws IOException {
		return readSection(file, localChunkX, localChunkZ).getCompoundTag("block_states");
	}

	private static CompoundTag readSection(File file, int localChunkX, int localChunkZ) throws IOException {
		return sectionAt(readChunk(file, localChunkX, localChunkZ), -4);
	}

	private static MCAFile readFixture(String name) throws Exception {
		return MCAUtil.read(fixtureFile(name));
	}

	private static File fixtureFile(String name) throws Exception {
		URL resource = RegionPaletteTest.class.getResource("palettes/" + name);
		assertNotNull("Missing region fixture " + name, resource);
		return new File(resource.toURI());
	}
}
