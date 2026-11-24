package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A 16 by 16 by 16 chunk section. String, mixed, and legacy on-disk palettes
 * are exposed as compound states with {@code Name} and optional {@code Properties}.
 * The palette size determines the packed index width, with a minimum of four bits.
 * Direct section changes bypass chunk-level heightmap invalidation. Use
 * {@link MCAFile#setBlockStateAt(int, int, int, CompoundTag, boolean)} for terrain edits.
 */
public class Section implements Comparable<Section> {
	private static final int BLOCK_COUNT = 4096;
	private static final int MINECRAFT_26_3_DATA_VERSION = 5023;

	private CompoundTag data;
	int dataVersion;
	private Map<String, List<PaletteIndex>> valueIndexedPalette = new HashMap<>();
	private ListTag<CompoundTag> palette;
	private long[] blockStates;
	private byte[] skyLight;
	private byte[] blockLight;
	private CompoundTag biomes;
	private int Y;
	private boolean stringPaletteFormat = false;
	// Non-null for modern compound palettes. Track explicit states without properties by identity.
	private Set<CompoundTag> explicitDefaultStates;

	private static CompoundTag parseBlockState(String raw) {
		CompoundTag result = new CompoundTag();

		int bracket = raw.indexOf('[');

		if (bracket == -1) {
			result.putString("Name", raw);
			return result;
		}

		result.putString("Name", raw.substring(0, bracket));

		int end = raw.lastIndexOf(']');

		if (end <= bracket) {
			throw new IllegalArgumentException(
					"Invalid block state: " + raw
			);
		}

		String propertiesText = raw.substring(bracket + 1, end);

		CompoundTag properties = new CompoundTag();

		for (String property : propertiesText.split(",")) {
			String[] pair = property.split("=", 2);

			if (pair.length != 2) {
				throw new IllegalArgumentException(
						"Invalid property: " + property
				);
			}

			properties.putString(pair[0].trim(), pair[1].trim());
		}

		result.put("Properties", properties);

		return result;
	}
	/**
	 * Initializes a section backed by the supplied NBT.
	 * @param sectionRoot the mutable NBT for the section, including its signed {@code Y}
	 * @param dataVersion the enclosing chunk's DataVersion, used when serializing the palette
	 * @param loadFlags the chunk loading flags, retained for compatibility
	 * @throws IllegalArgumentException if packed block data is missing for a multi-entry palette
	 * or its length does not match the palette width
	 */
	public Section(CompoundTag sectionRoot, int dataVersion, long loadFlags) {
		data = sectionRoot;
		this.dataVersion = dataVersion;
		Y = sectionRoot.getNumber("Y").byteValue();
		if(sectionRoot.getCompoundTag("block_states") != null) {
			ListTag<?> rawPalette = sectionRoot.getCompoundTag("block_states").getListTag("palette");
			if (rawPalette == null) {
				return;
			}
			if (rawPalette.getTypeClass() == StringTag.class) {
				stringPaletteFormat = true;

				this.palette = new ListTag<>(CompoundTag.class);

				for (int i = 0; i < rawPalette.size(); i++) {
					String name = ((StringTag) rawPalette.get(i)).getValue();
					CompoundTag state = parseBlockState(name);
					this.palette.add(state);
				}
			} else {
				this.palette = readCompoundPalette(rawPalette.asCompoundTagList());
			}
			for (int i = 0; i < this.palette.size(); i++) {
				CompoundTag data = this.palette.get(i);
				putValueIndexedPalette(data, i);
			}
			if (sectionRoot.getCompoundTag("block_states").getLongArrayTag("data") != null) {
				setBlockStates(sectionRoot.getCompoundTag("block_states").getLongArrayTag("data").getValue());
			} else if (palette.size() > 1) {
				throw new IllegalArgumentException("Missing packed block states for palette of size " + palette.size());
			}
		}
		else {
			this.blockStates = null;
			this.palette = null;
		}
		this.blockLight = sectionRoot.getByteArrayTag("BlockLight") != null ? sectionRoot.getByteArrayTag("BlockLight").getValue() : null;
		this.skyLight = sectionRoot.getByteArrayTag("SkyLight") != null ? sectionRoot.getByteArrayTag("SkyLight").getValue() : null;
		this.biomes = sectionRoot.getCompoundTag("biomes");
	}

	Section() {}

	private static boolean isWrappedString(CompoundTag entry) {
		return entry.size() == 1 && entry.get("") instanceof StringTag;
	}

	private ListTag<CompoundTag> readCompoundPalette(ListTag<CompoundTag> rawPalette) {
		for (CompoundTag entry : rawPalette) {
			if (isWrappedString(entry) || entry.get("id") instanceof StringTag) {
				return normalizeModernPalette(rawPalette);
			}
		}
		return rawPalette;
	}

	private ListTag<CompoundTag> normalizeModernPalette(ListTag<CompoundTag> rawPalette) {
		explicitDefaultStates = Collections.newSetFromMap(new IdentityHashMap<>());
		ListTag<CompoundTag> normalized = new ListTag<>(CompoundTag.class);
		for (CompoundTag entry : rawPalette) {
			CompoundTag state = entry;
			if (isWrappedString(entry)) {
				state = parseBlockState(entry.getString(""));
			} else if (entry.get("id") instanceof StringTag) {
				state = entry.clone();
				state.put("Name", state.remove("id"));
				if (state.containsKey("properties")) {
					state.put("Properties", state.remove("properties"));
				} else {
					explicitDefaultStates.add(state);
				}
			}
			normalized.add(state);
		}
		return normalized;
	}

	private ListTag<?> getSerializedPalette() {
		if (explicitDefaultStates != null
				|| (stringPaletteFormat && dataVersion >= MINECRAFT_26_3_DATA_VERSION && hasExplicitProperties())) {
			ListTag<CompoundTag> result = new ListTag<>(CompoundTag.class);
			for (CompoundTag state : palette) {
				CompoundTag entry;
				if (state.size() == 1 && (explicitDefaultStates == null || !explicitDefaultStates.contains(state))) {
					entry = new CompoundTag();
					entry.putString("", state.getString("Name"));
				} else {
					entry = state.clone();
					entry.put("id", entry.remove("Name"));
					if (entry.containsKey("Properties")) {
						entry.put("properties", entry.remove("Properties"));
					}
				}
				result.add(entry);
			}
			return result;
		}
		if (!stringPaletteFormat) {
			return palette;
		}

		ListTag<StringTag> result = new ListTag<>(StringTag.class);

		for (CompoundTag state : palette) {
			StringBuilder name = new StringBuilder(state.getString("Name"));

			CompoundTag properties = state.getCompoundTag("Properties");

			if (properties != null && !properties.keySet().isEmpty()) {
				name.append("[");

				boolean first = true;

				for (String key : properties.keySet()) {
					if (!first) {
						name.append(",");
					}

					name.append(key)
							.append("=")
							.append(properties.getString(key));

					first = false;
				}

				name.append("]");
			}

			result.add(new StringTag(name.toString()));
		}

		return result;
	}

	private boolean hasExplicitProperties() {
		for (CompoundTag state : palette) {
			CompoundTag properties = state.getCompoundTag("Properties");
			if (properties != null && properties.size() > 0) {
				return true;
			}
		}
		return false;
	}

	void putValueIndexedPalette(CompoundTag data, int index) {
		PaletteIndex leaf = new PaletteIndex(data, index);
		String name = data.getString("Name");
		List<PaletteIndex> leaves = valueIndexedPalette.get(name);
		if (leaves == null) {
			leaves = new ArrayList<>(1);
			leaves.add(leaf);
			valueIndexedPalette.put(name, leaves);
		} else {
			for (PaletteIndex pal : leaves) {
				if (pal.data.equals(data)) {
					return;
				}
			}
			leaves.add(leaf);
		}
	}

	PaletteIndex getValueIndexedPalette(CompoundTag data) {
		List<PaletteIndex> leaves = valueIndexedPalette.get(data.getString("Name"));
		if (leaves == null) {
			return null;
		}
		for (PaletteIndex leaf : leaves) {
			if (leaf.data.equals(data)) {
				return leaf;
			}
		}
		return null;
	}

	@Override
	public int compareTo(Section o) {
		if (o == null) {
			return -1;
		}
		return Integer.compare(Y, o.Y);
	}

	/** Sets all blocks in a section with a palette to {@code minecraft:air}. */
	public void clearSection() {
		clearBlocks();
	}

	boolean clearBlocks() {
		if (palette == null || !hasNonAirBlocks()) {
			return false;
		}
		palette.clear();
		if (explicitDefaultStates != null) {
			explicitDefaultStates.clear();
		}
		valueIndexedPalette.clear();
		CompoundTag air = new CompoundTag();
		air.putString("Name", "minecraft:air");
		addToPalette(air);
		blockStates = new long[packedLength(bitsPerBlock())];
		updateBlockStatesTag();
		return true;
	}

	private boolean hasNonAirBlocks() {
		int count = palette.size() == 1 ? 1 : BLOCK_COUNT;
		for (int i = 0; i < count; i++) {
			if (!"minecraft:air".equals(getBlockStateReference(i).getString("Name"))) {
				return true;
			}
		}
		return false;
	}

	public void setBiome(MinecraftBiome minecraftBiome) {
		if(biomes != null) {
			biomes.clear();
			StringTag biome = new StringTag();
			biome.setValue(minecraftBiome.getBiomeId());
			ListTag<StringTag> palette = new ListTag<>(StringTag.class);
			palette.add(biome);
			biomes.put("palette", palette);
		}
	}


	private static class PaletteIndex {
		CompoundTag data;
		int index;

		PaletteIndex(CompoundTag data, int index) {
			this.data = data;
			this.index = index;
		}
	}


	/**
	 * Reads a state from this section's palette. Requires loaded block state data.
	 * Each coordinate is reduced modulo 16.
	 * @param blockX the block X coordinate
	 * @param blockY the block Y coordinate
	 * @param blockZ the block Z coordinate
	 * @return a detached copy of the state, including its properties
	 */
	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
		return getBlockStateReference(blockX, blockY, blockZ).clone();
	}

	void checkStructureEditable(int blockX, int blockY, int blockZ) {
		if (palette == null || palette.size() == 0) {
			throw new IllegalStateException("Structure target section at (" + blockX + ", " + blockY + ", " + blockZ
					+ ") has no block state palette");
		}
		for (int i = 0; i < BLOCK_COUNT; i++) {
			if (getPaletteIndex(i) >= palette.size()) {
				throw new IllegalStateException("Invalid block state index " + i + " in structure target section at ("
						+ blockX + ", " + blockY + ", " + blockZ + ")");
			}
		}
	}

	// Internal state comparison uses the palette entry without copying it.
	CompoundTag getBlockStateReference(int blockX, int blockY, int blockZ) {
		return getBlockStateReference(getBlockIndex(blockX, blockY, blockZ));
	}

	private CompoundTag getBlockStateReference(int index) {
		int paletteIndex = getPaletteIndex(index);
		return palette.get(paletteIndex);
	}

	/**
	 * Sets a state in an existing palette and repacks the indices when the width changes.
	 * Each coordinate is reduced modulo 16. This method copies new palette entries.
	 * @param blockX the block X coordinate
	 * @param blockY the block Y coordinate
	 * @param blockZ the block Z coordinate
	 * @param state a compound with a {@code Name} string and optional string-valued {@code Properties}
	 * @param cleanup whether to request palette compaction. Some palette growth also triggers compaction
	 */
	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
		int paletteSizeBefore = palette.size();
		int oldBits = bitsPerBlock();
		int paletteIndex = addToPalette(state);
		int newBits = bitsPerBlock();
		if (oldBits != newBits || blockStates == null) {
			adjustBlockStateBits(null, oldBits);
		}
		if (paletteSizeBefore != palette.size() && (paletteIndex & (paletteIndex - 1)) == 0) {
			cleanup = true;
		}

		writePaletteIndex(getBlockIndex(blockX, blockY, blockZ), paletteIndex, blockStates, newBits);

		if (cleanup) {
			cleanupPaletteAndBlockStates();
		}
	}

	/**
	 * Reads a packed palette index. A section without packed data uses index zero.
	 * @param blockStateIndex the linear position from 0 to 4095, ordered with X varying fastest, then Z, then Y
	 * @return the palette index
	 */
	public int getPaletteIndex(int blockStateIndex) {
		if (blockStates == null) {
			return 0;
		}
		return readPaletteIndex(blockStateIndex, blockStates, bitsPerBlock());
	}

	private static int readPaletteIndex(int blockStateIndex, long[] blockStates, int bits) {
		int indicesPerLong = Long.SIZE / bits;
		int blockStatesIndex = blockStateIndex / indicesPerLong;
		int startBit = (blockStateIndex % indicesPerLong) * bits;
		return (int) bitRange(blockStates[blockStatesIndex], startBit, startBit + bits);
	}

	/**
	 * Writes an index into the supplied array using this section's palette width.
	 * The caller supplies a valid position and palette index.
	 * @param blockIndex the linear block position, as in {@link #getPaletteIndex(int)}
	 * @param paletteIndex the palette index to write
	 * @param blockStates the array to modify
	 * @throws IllegalArgumentException if the array length does not match the palette width
	 */
	public void setPaletteIndex(int blockIndex, int paletteIndex, long[] blockStates) {
		int bits = bitsPerBlock();
		validatePackedLength(blockStates, bits);
		writePaletteIndex(blockIndex, paletteIndex, blockStates, bits);
	}

	private static void writePaletteIndex(int blockIndex, int paletteIndex, long[] blockStates, int bits) {
		int indicesPerLong = Long.SIZE / bits;
		int blockStatesIndex = blockIndex / indicesPerLong;
		int startBit = (blockIndex % indicesPerLong) * bits;
		blockStates[blockStatesIndex] = updateBits(blockStates[blockStatesIndex], paletteIndex, startBit, startBit + bits);
	}

	/** @return a detached snapshot of the palette, or {@code null} if there is no palette */
	public ListTag<CompoundTag> getPalette() {
		return palette == null ? null : palette.clone();
	}

	int addToPalette(CompoundTag data) {
		PaletteIndex index;
		if ((index = getValueIndexedPalette(data)) != null) {
			return index.index;
		}
		CompoundTag stored = data.clone();
		palette.add(stored);
		putValueIndexedPalette(stored, palette.size() - 1);
		return palette.size() - 1;
	}

	int getBlockIndex(int blockX, int blockY, int blockZ) {
		return (blockY & 0xF) * 256 + (blockZ & 0xF) * 16 + (blockX & 0xF);
	}

	static long updateBits(long n, long m, int i, int j) {
		//replace i to j in n with j - i bits of m
		long mShifted = i > 0 ? (m & ((1L << j - i) - 1)) << i : (m & ((1L << j - i) - 1)) >>> -i;
		return ((n & ((j > 63 ? 0 : (~0L << j)) | (i < 0 ? 0 : ((1L << i) - 1L)))) | mShifted);
	}

	static long bitRange(long value, int from, int to) {
		int waste = 64 - to;
		return (value << waste) >>> (waste + from);
	}

	/**
	 * Removes unused palette entries other than index zero and remaps packed
	 * indices. Cleanup is optional before serialization.
	 */
	public void cleanupPaletteAndBlockStates() {
		if (blockStates != null) {
			int oldBits = bitsPerBlock();
			Map<Integer, Integer> oldToNewMapping = cleanupPalette();
			adjustBlockStateBits(oldToNewMapping, oldBits);
		}
	}

	private Map<Integer, Integer> cleanupPalette() {
		//create index - palette mapping
		Map<Integer, Integer> allIndices = new HashMap<>();
		for (int i = 0; i < BLOCK_COUNT; i++) {
			int paletteIndex = getPaletteIndex(i);
			allIndices.put(paletteIndex, paletteIndex);
		}
		//delete unused blocks from palette
		//start at index 1 because we need to keep minecraft:air
		int index = 1;
		valueIndexedPalette = new HashMap<>(valueIndexedPalette.size());
		putValueIndexedPalette(palette.get(0), 0);
		for (int i = 1; i < palette.size(); i++) {
			if (!allIndices.containsKey(index)) {
				CompoundTag removed = palette.remove(i);
				if (explicitDefaultStates != null) {
					explicitDefaultStates.remove(removed);
				}
				i--;
			} else {
				putValueIndexedPalette(palette.get(i), i);
				allIndices.put(index, i);
			}
			index++;
		}

		return allIndices;
	}

	private void adjustBlockStateBits(Map<Integer, Integer> oldToNewMapping, int oldBits) {
		int newBits = bitsPerBlock();
		long[] newBlockStates = blockStates != null && oldBits == newBits
				? blockStates : new long[packedLength(newBits)];
		if (blockStates != null) {
			for (int i = 0; i < BLOCK_COUNT; i++) {
				int index = readPaletteIndex(i, blockStates, oldBits);
				int newIndex = oldToNewMapping == null ? index : oldToNewMapping.get(index);
				writePaletteIndex(i, newIndex, newBlockStates, newBits);
			}
		}
		this.blockStates = newBlockStates;
	}

	private int bitsPerBlock() {
		return Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(0, palette.size() - 1)));
	}

	private static int packedLength(int bits) {
		int indicesPerLong = Long.SIZE / bits;
		return (BLOCK_COUNT + indicesPerLong - 1) / indicesPerLong;
	}

	private static void validatePackedLength(long[] blockStates, int bits) {
		int expected = packedLength(bits);
		if (blockStates.length != expected) {
			throw new IllegalArgumentException("Expected " + expected + " packed longs for " + bits
					+ "-bit block states, got " + blockStates.length);
		}
	}

	/**
	 * Replaces the packed index array. The array is retained by reference.
	 * The caller is responsible for supplying valid index values.
	 * @param blockStates the packed block state indices
	 * @throws NullPointerException If <code>blockStates</code> is <code>null</code>
	 * @throws IllegalArgumentException If the array length does not match the current palette width
	 */
	public void setBlockStates(long[] blockStates) {
		if (blockStates == null) {
			throw new NullPointerException("BlockStates cannot be null");
		}
		validatePackedLength(blockStates, bitsPerBlock());
		this.blockStates = blockStates;
	}

	/**
	 * Creates an empty Section with base values.
	 * @return An empty Section
	 */
	public static Section newSection() {
		return newSection(0, 0);
	}

	static Section newSection(int y, int dataVersion) {
		Section s = new Section();
		s.Y = y;
		s.dataVersion = dataVersion;
		s.palette = new ListTag<>(CompoundTag.class);
		if (dataVersion >= MINECRAFT_26_3_DATA_VERSION) {
			s.explicitDefaultStates = Collections.newSetFromMap(new IdentityHashMap<>());
		}
		CompoundTag air = new CompoundTag();
		air.putString("Name", "minecraft:air");
		s.addToPalette(air);
		s.blockStates = new long[packedLength(s.bitsPerBlock())];
		s.data = new CompoundTag();
		return s;
	}

	private void updateBlockStatesTag() {
		CompoundTag blockStatesTag = data.getCompoundTag("block_states");
		if (blockStatesTag == null && (palette != null || blockStates != null)) {
			blockStatesTag = new CompoundTag();
			data.put("block_states", blockStatesTag);
		}

		if (palette != null) {
			blockStatesTag.put("palette", getSerializedPalette());
		}
		if (blockStates != null) {
			validatePackedLength(blockStates, bitsPerBlock());
			blockStatesTag.putLongArray("data", blockStates);
		}
	}

	/**
	 * Updates the retained NBT and encodes the supplied Y as a signed byte.
	 * @param y the section Y to write
	 * @return the mutable section root
	 */
	public CompoundTag updateHandle(int y) {
		data.putByte("Y", (byte) y);
		updateBlockStatesTag();
		if (skyLight != null) {
			data.putByteArray("SkyLight", skyLight);
		}
		if (blockLight != null) {
			data.putByteArray("BlockLight", blockLight);
		}
		if (biomes != null) {
			data.put("biomes", biomes);
		}
		return data;
	}

	/** @return the mutable NBT updated with this section's stored Y and palette */
	public CompoundTag updateHandle() {
		return updateHandle(Y);
	}

	/**
	 * Iterates over detached copies of the block states. Requires a loaded palette.
	 * @return a single-use iterable over 4,096 positions, with X varying fastest, then Z, then Y
	 */
	public Iterable<CompoundTag> blocksStates() {
		return new BlockIterator(this);
	}

	private static class BlockIterator implements Iterable<CompoundTag>, Iterator<CompoundTag> {

		private Section section;
		private int currentIndex;

		public BlockIterator(Section section) {
			this.section = section;
			currentIndex = 0;
		}

		@Override
		public boolean hasNext() {
			return currentIndex < BLOCK_COUNT;
		}

		@Override
		public CompoundTag next() {
			CompoundTag blockState = section.getBlockStateReference(currentIndex).clone();
			currentIndex++;
			return blockState;
		}

		@Override
		public Iterator<CompoundTag> iterator() {
			return this;
		}
	}
}
