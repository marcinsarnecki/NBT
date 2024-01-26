package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Section implements Comparable<Section> {
	private CompoundTag data;
	int dataVersion;
	private Map<String, List<PaletteIndex>> valueIndexedPalette = new HashMap<>();
	private ListTag<CompoundTag> palette;
	private long[] blockStates;
	private byte[] skyLight;
	private byte[] blockLight;
	private CompoundTag biomes;
	private int Y;


	public Section(CompoundTag sectionRoot, int dataVersion, long loadFlags) {
		data = sectionRoot;
		this.dataVersion = dataVersion;
		Y = sectionRoot.getNumber("Y").byteValue();
		if(sectionRoot.getCompoundTag("block_states") != null) {
			ListTag<?> rawPalette = sectionRoot.getCompoundTag("block_states").getListTag("palette");
			if (rawPalette == null) {
				return;
			}
			this.palette = rawPalette.asCompoundTagList();
			for (int i = 0; i < this.palette.size(); i++) {
				CompoundTag data = this.palette.get(i);
				putValueIndexedPalette(data, i);
			}
			this.blockStates = sectionRoot.getCompoundTag("block_states").getLongArrayTag("data") != null ? sectionRoot.getCompoundTag("block_states").getLongArrayTag("data").getValue() : null;
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

	public void clearSection() {//sets every block to minecraft:air
		if(palette == null) {//special check for empty chunk y=5 on super flat world, without this if it crashes
			return;
		}
		setBlockStates(new long[256]);
		if(palette != null)
			palette.clear();
		if(valueIndexedPalette != null)
			valueIndexedPalette.clear();
		CompoundTag air = new CompoundTag();
		air.putString("Name", "minecraft:air");
		addToPalette(air);
		cleanupPalette();
		CompoundTag block_states = new CompoundTag();
		block_states.put("palette", palette);
		block_states.putLongArray("data", blockStates);
		data.put("block_states", block_states);
		skyLight = null;
		blockLight = null;
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


	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
		return getBlockStateAt(getBlockIndex(blockX, blockY, blockZ));
	}

	private CompoundTag getBlockStateAt(int index) {
		int paletteIndex = getPaletteIndex(index);
		return palette.get(paletteIndex);
	}

	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
		int paletteSizeBefore = palette.size();
		int paletteIndex = addToPalette(state);
		if (paletteSizeBefore != palette.size() && (paletteIndex & (paletteIndex - 1)) == 0) {
			adjustBlockStateBits(null, blockStates);
			cleanup = true;
		}

		setPaletteIndex(getBlockIndex(blockX, blockY, blockZ), paletteIndex, blockStates);

		if (cleanup) {
			cleanupPaletteAndBlockStates();
		}
	}

	public int getPaletteIndex(int blockStateIndex) {
		if(blockStates == null) {
			setBlockStates(new long[256]);
		}
		int bits = blockStates.length >> 6;

		int indicesPerLong = (int) (64D / bits);
		int blockStatesIndex = blockStateIndex / indicesPerLong;
		int startBit = (blockStateIndex % indicesPerLong) * bits;
		return (int) bitRange(blockStates[blockStatesIndex], startBit, startBit + bits);

	}

	public void setPaletteIndex(int blockIndex, int paletteIndex, long[] blockStates) {
		int bits = blockStates.length >> 6;
		int indicesPerLong = (int) (64D / bits);
		int blockStatesIndex = blockIndex / indicesPerLong;
		int startBit = (blockIndex % indicesPerLong) * bits;
		blockStates[blockStatesIndex] = updateBits(blockStates[blockStatesIndex], paletteIndex, startBit, startBit + bits);
	}

	public ListTag<CompoundTag> getPalette() {
		return palette;
	}

	int addToPalette(CompoundTag data) {
		PaletteIndex index;
		if ((index = getValueIndexedPalette(data)) != null) {
			return index.index;
		}
		palette.add(data);
		putValueIndexedPalette(data, palette.size() - 1);
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
	 * This method recalculates the palette and its indices.
	 * This should only be used moderately to avoid unnecessary recalculation of the palette indices.
	 * Recalculating the Palette should only be executed once right before saving the Section to file.
	 */
	public void cleanupPaletteAndBlockStates() {
		if (blockStates != null) {
			Map<Integer, Integer> oldToNewMapping = cleanupPalette();
			adjustBlockStateBits(oldToNewMapping, blockStates);
		}
	}

	private Map<Integer, Integer> cleanupPalette() {
		//create index - palette mapping
		Map<Integer, Integer> allIndices = new HashMap<>();
		for (int i = 0; i < 4096; i++) {
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
				palette.remove(i);
				i--;
			} else {
				putValueIndexedPalette(palette.get(i), i);
				allIndices.put(index, i);
			}
			index++;
		}

		return allIndices;
	}

	void adjustBlockStateBits(Map<Integer, Integer> oldToNewMapping, long[] blockStates) {
		//increases or decreases the amount of bits used per BlockState
		//based on the size of the palette. oldToNewMapping can be used to update indices
		//if the palette had been cleaned up before using MCAFile#cleanupPalette().

		int newBits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
		newBits = Math.max(newBits, 4);

		int newLength = (int) Math.ceil(4096D / (Math.floor(64D / newBits)));
		long[] newBlockStates = (blockStates != null && newBits == blockStates.length / 64) ? blockStates : new long[newLength];

		if (oldToNewMapping != null) {
			for (int i = 0; i < 4096; i++) {
				setPaletteIndex(i, oldToNewMapping.get(getPaletteIndex(i)), newBlockStates);
			}
		} else {
			for (int i = 0; i < 4096; i++) {
				setPaletteIndex(i, getPaletteIndex(i), newBlockStates);
			}
		}
		this.blockStates = newBlockStates;
	}

	/**
	 * Sets the block state indices to a custom value.
	 * @param blockStates The block state indices.
	 * @throws NullPointerException If <code>blockStates</code> is <code>null</code>
	 * @throws IllegalArgumentException When <code>blockStates</code>' length is &lt; 256 or &gt; 4096 and is not a multiple of 64
	 */
	public void setBlockStates(long[] blockStates) {
		if (blockStates == null) {
			throw new NullPointerException("BlockStates cannot be null");
		} else if (blockStates.length % 64 != 0 || blockStates.length < 256 || blockStates.length > 4096) {
			throw new IllegalArgumentException("BlockStates must have a length > 255 and < 4097 and must be divisible by 64");
		}
		this.blockStates = blockStates;
	}

	/**
	 * Creates an empty Section with base values.
	 * @return An empty Section
	 */
	public static Section newSection() {
		Section s = new Section();
		s.blockStates = new long[256];
		s.palette = new ListTag<>(CompoundTag.class);
		CompoundTag air = new CompoundTag();
		air.putString("Name", "minecraft:air");
		s.palette.add(air);
		s.data = new CompoundTag();
		return s;
	}

	public CompoundTag updateHandle(int y) {
		data.putByte("Y", (byte) y);
		CompoundTag blockStatesTag = data.getCompoundTag("block_states");
		if (blockStatesTag == null) {
			blockStatesTag = new CompoundTag();
		}

		if (palette != null) {
			blockStatesTag.put("palette", palette);
		}
		if (blockStates != null) {
			blockStatesTag.putLongArray("data", blockStates);
		}
		if (skyLight != null) {
			data.putByteArray("SkyLight", skyLight);
		}
		if (blockLight != null) {
			data.putByteArray("BlockLight", blockLight);
		}
		if (biomes != null) {
			data.put("biomes", biomes);//chyba trzeba bedzie rozbic
		}
		return data;
	}

	public CompoundTag updateHandle() {
		return updateHandle(Y);
	}

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
			return currentIndex < 4096;
		}

		@Override
		public CompoundTag next() {
			CompoundTag blockState = section.getBlockStateAt(currentIndex);
			currentIndex++;
			return blockState;
		}

		@Override
		public Iterator<CompoundTag> iterator() {
			return this;
		}
	}
}
