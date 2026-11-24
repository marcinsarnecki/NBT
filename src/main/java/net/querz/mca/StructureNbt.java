package net.querz.mca;

import net.querz.io.MaxDepthReachedException;
import net.querz.nbt.io.NBTInputStream;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.EndTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * An immutable, single-palette Minecraft structure containing block states only.
 * Positions are relative to the structure origin. Placement and region I/O
 * are separate operations.
 */
public final class StructureNbt {
	private static final Pattern BLOCK_ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+");

	private final int sizeX, sizeY, sizeZ;
	private final int dataVersion;
	private final String author;
	private final List<CompoundTag> palette;
	private final List<Block> blocks;

	private StructureNbt(int[] size, int dataVersion, String author, List<CompoundTag> palette, List<Block> blocks) {
		sizeX = size[0];
		sizeY = size[1];
		sizeZ = size[2];
		this.dataVersion = dataVersion;
		this.author = author;
		this.palette = palette;
		this.blocks = Collections.unmodifiableList(blocks);
	}

	/**
	 * Reads gzip-compressed structure NBT and validates tag types, dimensions,
	 * unique in-bounds positions, and palette references. The file may contain
	 * an author and an empty entity list. Alternate palettes, per-block payloads,
	 * nonempty entity lists, and other unsupported tags are rejected.
	 * Duplicate tag names within a compound and unknown list element type IDs are rejected.
	 * @param path the structure file
	 * @return the structure with its source DataVersion
	 * @throws IOException if the file cannot be read or contains invalid or unsupported structure NBT
	 */
	public static StructureNbt read(Path path) throws IOException {
		try (InputStream file = Files.newInputStream(path); GZIPInputStream gzip = new GZIPInputStream(file)) {
			Tag<?> tag = NBTInputStream.strict(gzip).readTag(Tag.DEFAULT_MAX_DEPTH).getTag();
			if (gzip.read() != -1) {
				throw new IOException("Trailing data after the structure NBT root");
			}
			return parse(requireType(tag, CompoundTag.class, "$"));
		} catch (NegativeArraySizeException | MaxDepthReachedException e) {
			throw new IOException("Invalid structure NBT", e);
		}
	}

	private static StructureNbt parse(CompoundTag root) throws IOException {
		checkKeys(root, "$", "DataVersion", "author", "size", "palette", "blocks", "entities");
		int dataVersion = requireType(root.get("DataVersion"), IntTag.class, "$.DataVersion").asInt();
		String author = root.containsKey("author")
				? requireType(root.get("author"), StringTag.class, "$.author").getValue() : null;
		int[] size = readCoordinates(root.get("size"), "$.size");
		for (int dimension : size) {
			if (dimension <= 0) {
				throw new IOException("$.size dimensions must be positive");
			}
		}
		if (root.containsKey("entities") && readCompoundList(root.get("entities"), "$.entities").size() != 0) {
			throw new IOException("$.entities is unsupported when nonempty");
		}
		List<CompoundTag> palette = readPalette(root.get("palette"));
		List<Block> blocks = readBlocks(root.get("blocks"), size, palette.size());
		return new StructureNbt(size, dataVersion, author, palette, blocks);
	}

	private static List<CompoundTag> readPalette(Tag<?> tag) throws IOException {
		ListTag<?> entries = readCompoundList(tag, "$.palette");
		if (entries.size() == 0) {
			throw new IOException("$.palette must contain at least one state");
		}
		List<CompoundTag> palette = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			String path = "$.palette[" + i + "]";
			CompoundTag state = requireType(entries.get(i), CompoundTag.class, path);
			checkKeys(state, path, "Name", "Properties");
			String name = requireType(state.get("Name"), StringTag.class, path + ".Name").getValue();
			if (!BLOCK_ID.matcher(name).matches()) {
				throw new IOException(path + ".Name is not a valid block identifier: " + name);
			}
			if (state.containsKey("Properties")) {
				CompoundTag properties = requireType(state.get("Properties"), CompoundTag.class, path + ".Properties");
				for (String key : properties.keySet()) {
					requireType(properties.get(key), StringTag.class, path + ".Properties." + key);
				}
			}
			CompoundTag stored = state.clone();
			if (name.indexOf(':') < 0) {
				stored.putString("Name", "minecraft:" + name);
			}
			palette.add(stored);
		}
		return palette;
	}

	private static List<Block> readBlocks(Tag<?> tag, int[] size, int paletteSize) throws IOException {
		ListTag<?> entries = readCompoundList(tag, "$.blocks");
		List<Block> blocks = new ArrayList<>(entries.size());
		Set<List<Integer>> positions = new HashSet<>();
		for (int i = 0; i < entries.size(); i++) {
			String path = "$.blocks[" + i + "]";
			CompoundTag entry = requireType(entries.get(i), CompoundTag.class, path);
			checkKeys(entry, path, "pos", "state");
			int index = requireType(entry.get("state"), IntTag.class, path + ".state").asInt();
			if (index < 0 || index >= paletteSize) {
				throw new IOException(path + ".state has an out-of-range palette index: " + index);
			}
			int[] pos = readCoordinates(entry.get("pos"), path + ".pos");
			for (int axis = 0; axis < 3; axis++) {
				if (pos[axis] < 0 || pos[axis] >= size[axis]) {
					throw new IOException(path + ".pos is outside $.size: " + Arrays.toString(pos));
				}
			}
			if (!positions.add(Arrays.asList(pos[0], pos[1], pos[2]))) {
				throw new IOException(path + ".pos duplicates a block position: " + Arrays.toString(pos));
			}
			blocks.add(new Block(pos, index));
		}
		return blocks;
	}

	private static int[] readCoordinates(Tag<?> tag, String path) throws IOException {
		ListTag<?> list = requireType(tag, ListTag.class, path);
		if (list.size() != 3 || list.getTypeClass() != IntTag.class) {
			throw new IOException(path + " must be a TAG_List of exactly three TAG_Int values");
		}
		return new int[]{((IntTag) list.get(0)).asInt(), ((IntTag) list.get(1)).asInt(),
				((IntTag) list.get(2)).asInt()};
	}

	private static ListTag<?> readCompoundList(Tag<?> tag, String path) throws IOException {
		ListTag<?> list = requireType(tag, ListTag.class, path);
		if (list.getTypeClass() != CompoundTag.class && !(list.size() == 0 && list.getTypeClass() == EndTag.class)) {
			throw new IOException(path + " must be a TAG_List of TAG_Compound values");
		}
		return list;
	}

	private static <T extends Tag<?>> T requireType(Tag<?> tag, Class<T> type, String path) throws IOException {
		if (!type.isInstance(tag)) {
			throw new IOException(path + " must be a " + type.getSimpleName());
		}
		return type.cast(tag);
	}

	private static void checkKeys(CompoundTag tag, String path, String... keys) throws IOException {
		List<String> allowed = Arrays.asList(keys);
		for (String key : tag.keySet()) {
			if (!allowed.contains(key)) {
				throw new IOException("Unsupported structure tag " + path + "." + key);
			}
		}
	}

	/** @return the size in blocks along X */
	public int getSizeX() { return sizeX; }

	/** @return the size in blocks along Y */
	public int getSizeY() { return sizeY; }

	/** @return the size in blocks along Z */
	public int getSizeZ() { return sizeZ; }

	/** @return the source DataVersion recorded in the structure */
	public int getDataVersion() { return dataVersion; }

	/** @return the author, or {@code null} if the file omits it */
	public String getAuthor() { return author; }

	/**
	 * Returns a read-only list of detached states with {@code Name} and optional
	 * string-valued {@code Properties}. Palette order and duplicate entries are preserved.
	 * @return the states in source palette order
	 */
	public List<CompoundTag> getPalette() {
		List<CompoundTag> copy = new ArrayList<>(palette.size());
		for (CompoundTag state : palette) {
			copy.add(state.clone());
		}
		return Collections.unmodifiableList(copy);
	}

	/** @return the read-only list of entries in source order */
	public List<Block> getBlocks() { return blocks; }

	/** One listed position and its source palette index. */
	public static final class Block {
		private final int x, y, z, stateIndex;

		private Block(int[] pos, int stateIndex) {
			x = pos[0];
			y = pos[1];
			z = pos[2];
			this.stateIndex = stateIndex;
		}

		/** @return the X offset from the structure origin */
		public int getX() { return x; }

		/** @return the Y offset from the structure origin */
		public int getY() { return y; }

		/** @return the Z offset from the structure origin */
		public int getZ() { return z; }

		/** @return the zero-based index in {@link StructureNbt#getPalette()} */
		public int getStateIndex() { return stateIndex; }
	}
}
