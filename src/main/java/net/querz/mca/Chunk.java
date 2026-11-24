package net.querz.mca;
import java.util.concurrent.ThreadLocalRandom;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.FloatTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static net.querz.mca.LoadFlags.*;

/**
 * A decoded MCA chunk. Select its region and slot through {@link MCAFile} before
 * using the direct block methods. Terrain edits use the root-level chunk layout.
 */
public class Chunk implements Iterable<Section> {// todo maybe split this class into region chunk class and entity chunk class?

	/** Historical default used by the legacy {@link #newChunk()} helper. */
	public static final int DEFAULT_DATA_VERSION = 3700;
	private static final String[] BLOCK_HEIGHTMAP_KEYS = {
			"WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"
	};

	private boolean partial;
	private boolean raw;

	private int lastMCAUpdate;

	private CompoundTag data;

	private String status;
	private int xPos, zPos, yPos;
	private int dataVersion;
	private long lastUpdate;
	private long inhabitedTime;

	private CompoundTag heightMaps;
	private ListTag<CompoundTag> blockEntities;
	private ListTag<CompoundTag> blockTicks;
	private ListTag<CompoundTag> fluidTicks;
	CompoundTag structures;
	private Map<Integer, Section> sections = new TreeMap<>();

	private ListTag<ListTag<?>> postProcessing;
	private int[] position;
	private ListTag<CompoundTag> entities;


	Chunk(int lastMCAUpdate) {
		this.lastMCAUpdate = lastMCAUpdate;
	}

	/**
	 * Initializes a chunk from decoded NBT. This constructor retains the supplied
	 * tag and has no region slot against which to check its coordinates.
	 * @param data the mutable chunk root
	 */
	public Chunk(CompoundTag data) {
		this.data = data;
		initReferences(ALL_DATA);
	}

	private void initReferences(long loadFlags) {
		if (data == null) {
			throw new NullPointerException("data cannot be null");
		}

		if ((loadFlags != ALL_DATA) && (loadFlags & RAW) != 0) {
			raw = true;
			return;
		}
		if(data.containsKey("Entities")) {// entity chunk
			dataVersion = data.getInt("DataVersion");
			position = data.getIntArrayTag("Position").getValue();
			entities = data.getListTag("Entities").asCompoundTagList();
		}
		else { //region chunk
			status = data.getString("Status");
			xPos = data.getInt("xPos");
			yPos = data.getInt("yPos");
			zPos = data.getInt("zPos");
			dataVersion = data.getInt("DataVersion");
			inhabitedTime = data.getLong("InhabitedTime");
			lastUpdate = data.getLong("LastUpdate");
			heightMaps = data.getCompoundTag("Heightmaps");
			ListTag<?> blockEntityList = data.getListTag("block_entities");
			blockEntities = blockEntityList == null ? null : blockEntityList.asCompoundTagList();
			structures = data.getCompoundTag("structures");
			blockTicks = data.containsKey("block_ticks") ? data.getListTag("block_ticks").asCompoundTagList() : null;
			fluidTicks = data.containsKey("fluid_ticks") ? data.getListTag("fluid_ticks").asCompoundTagList() : null;
			postProcessing = data.containsKey("PostProcessing") ? data.getListTag("PostProcessing").asListTagList() : null;


			if ((loadFlags & (BLOCK_LIGHTS|BLOCK_STATES|SKY_LIGHT)) != 0 && data.containsKey("sections")) {
				for (CompoundTag section : data.getListTag("sections").asCompoundTagList()) {
					int sectionIndex = section.getNumber("Y").byteValue();
					Section newSection = new Section(section, dataVersion, loadFlags);
					sections.put(sectionIndex, newSection);
				}
			}
		}


		// If we haven't requested the full set of data we can drop the underlying raw data to let the GC handle it.
		if (loadFlags != ALL_DATA) {
			data = null;
			partial = true;
		}
	}

	public void createZombie(int xPos, int yPos, int zPos) { // https://minecraft.fandom.com/wiki/Zombie#Entity_data //todo function for other mobs, function that sets tags common for all mobs
		CompoundTag entity = new CompoundTag();
		//all entities tags
		entity.putShort("Air", (short) 300);

		ListTag<DoubleTag> Pos = new ListTag<>(DoubleTag.class);
		DoubleTag doubleTagX = new DoubleTag(xPos + 0.5);// +0.5 for a mob to spawn in the middle of the block
		DoubleTag doubleTagY = new DoubleTag(yPos);
		DoubleTag doubleTagZ = new DoubleTag(zPos + 0.5);// +0.5 for a mob to spawn in the middle of the block
		Pos.add(doubleTagX);
		Pos.add(doubleTagY);
		Pos.add(doubleTagZ);
		entity.put("Pos", Pos);

		entity.putFloat("FlyingDistance", 0);
		entity.putBoolean("OnGround", true);
		entity.putBoolean("Invulnerable", false);

		ListTag<DoubleTag> Motion = new ListTag<>(DoubleTag.class);
		DoubleTag doubleTagX2 = new DoubleTag(0);
		DoubleTag doubleTagY2 = new DoubleTag(0);
		DoubleTag doubleTagZ2 = new DoubleTag(0);
		Motion.add(doubleTagX2);
		Motion.add(doubleTagY2);
		Motion.add(doubleTagZ2);
		entity.put("Motion", Motion);

		ListTag<FloatTag> Rotation = new ListTag<>(FloatTag.class);
		FloatTag floatTag2 = new FloatTag(0f);
		Rotation.add(floatTag2);
		Rotation.add(floatTag2);
		entity.put("Rotation", Rotation);

		int[] randomNumbers = ThreadLocalRandom.current().ints(4).toArray();
		entity.putIntArray("UUID", randomNumbers);
		//all mobs tags
		entity.putFloat("AbsorptionAmount", 0);

		ListTag<FloatTag> ArmorDropChances = new ListTag<>(FloatTag.class);
		FloatTag floatTag = new FloatTag(0.0085f);
		ArmorDropChances.add(floatTag);
		ArmorDropChances.add(floatTag);
		ArmorDropChances.add(floatTag);
		ArmorDropChances.add(floatTag);
		entity.put("ArmorDropChances", ArmorDropChances);

		ListTag<CompoundTag> ArmorItems = new ListTag<>(CompoundTag.class);
		ArmorItems.add(new CompoundTag());
		ArmorItems.add(new CompoundTag());
		ArmorItems.add(new CompoundTag());
		ArmorItems.add(new CompoundTag());
		entity.put("ArmorItems", ArmorItems);

		entity.putBoolean("CanPickUpLoot", false);
		entity.putShort("DeathTime", (short) 0);
		entity.putBoolean("FallFlying", false);
		entity.putFloat("Health", 20f);
		entity.putFloat("FallDistance", -1);
		entity.putFloat("Fire", -1);
		entity.putInt("HurtByTimestamp", 0);
		entity.putShort("HurtTime", (short) 0);
		entity.putString("id", "minecraft:zombie");

		ListTag<FloatTag> HandDropChances = new ListTag<>(FloatTag.class);
		FloatTag floatTag1 = new FloatTag(0.085f);
		HandDropChances.add(floatTag1);
		HandDropChances.add(floatTag1);
		entity.put("HandDropChances", HandDropChances);

		ListTag<CompoundTag> HandItems = new ListTag<>(CompoundTag.class);
		HandItems.add(new CompoundTag());
		HandItems.add(new CompoundTag());
		entity.put("HandItems", HandItems);

		entity.putBoolean("LeftHanded", false);
		entity.putBoolean("PersistenceRequired", false);

		CompoundTag Brain = new CompoundTag();
		CompoundTag memories = new CompoundTag();
		Brain.put("memories", memories);
		entity.put("Brain", Brain);

		ListTag<CompoundTag> Attributes = new ListTag<>(CompoundTag.class);
		CompoundTag genericMovementSpeed = new CompoundTag();
		genericMovementSpeed.putDouble("Base", 0.23);
		genericMovementSpeed.putString("Name", "minecraft:generic.movement_speed");
		Attributes.add(genericMovementSpeed);
		CompoundTag genericFollowRange = new CompoundTag();
		genericFollowRange.putDouble("Base", 35);
		genericFollowRange.putString("Name", "minecraft:generic.follow_range");
		entity.put("Attributes", Attributes);

		//zombie tags
		entity.putInt("DrownedConversionTime", -1);
		entity.putInt("InWaterTime", -1);
		entity.putBoolean("CanBreakDoors", true);
		entity.putBoolean("IsBaby", false);

		entities.add(entity);
	}
	public void createMonsterSpawner(int x, int y, int z, MinecraftMob entityType, short spawnCount, short spawnRange, short minSpawnDelay, short maxSpawnDelay, short playerActivationRange, short maxNearbyEntities, short delay) {
		CompoundTag mob_spawner = new CompoundTag();
		mob_spawner.putString("Name", "minecraft:spawner");
		setBlockStateAt(x, y, z, mob_spawner, false);

		CompoundTag spawnerData = new CompoundTag();
		spawnerData.putString("id", "minecraft:mob_spawner");
		spawnerData.putInt("x", x);
		spawnerData.putInt("y", y);
		spawnerData.putInt("z", z);
		spawnerData.putShort("SpawnCount", spawnCount);
		spawnerData.putShort("SpawnRange", spawnRange);
		spawnerData.putShort("MinSpawnDelay", minSpawnDelay);
		spawnerData.putShort("MaxSpawnDelay", maxSpawnDelay);
		spawnerData.putShort("RequiredPlayerRange", playerActivationRange);
		spawnerData.putShort("MaxNearbyEntities", maxNearbyEntities);
		spawnerData.putShort("Delay", delay);
		spawnerData.putByte("keepPacked", (byte) 0);

		CompoundTag spawnData = new CompoundTag();
		CompoundTag entity = new CompoundTag();
		entity.putString("id", entityType.getEntityId());
		spawnData.put("entity", entity);
		spawnerData.put("SpawnData", spawnData);

		addBlockEntity(spawnerData);
	}

	public void createChest(int x, int y, int z, String facing, ListTag<CompoundTag> items) {
		CompoundTag chestTag = new CompoundTag();
		chestTag.putString("Name", "minecraft:chest");

		CompoundTag properties = new CompoundTag();
		properties.putString("type", "single");
		properties.putBoolean("waterlogged", false);
		properties.putString("facing", facing); // "north", "south", "east", or "west"

		chestTag.put("Properties", properties);
		setBlockStateAt(x, y, z, chestTag, false);

		CompoundTag chestData = new CompoundTag();
		chestData.putString("id", "minecraft:chest");
		chestData.putInt("x", x);
		chestData.putInt("y", y);
		chestData.putInt("z", z);
		chestData.put("Items", items);
		chestData.putByte("keepPacked", (byte) 0);

		addBlockEntity(chestData);
	}

	private void addBlockEntity(CompoundTag blockEntity) {
		if (blockEntities == null) {
			blockEntities = new ListTag<>(CompoundTag.class);
		}
		blockEntities.add(blockEntity);
	}


	/**
	 * Clears the block states in editable sections to air. If any block changes,
	 * the four standard terrain heightmaps are invalidated. Stored lighting,
	 * block entities, and scheduled ticks are preserved.
	 */
	public void clearChunk() {
		boolean changed = false;
		for (Section section : sections.values()) {
			changed |= section.clearBlocks();
		}
		if (changed) {
			invalidateBlockHeightmaps();
		}
	}

	public void setBiome(MinecraftBiome biome) {
		for(Section section: sections.values()) {
			section.setBiome(biome);
		}
	}

	/**
	 * Writes a record length, the ZLIB compression byte, and compressed chunk NBT
	 * at the current file position. Region headers and sector allocation are
	 * handled by {@link MCAFile}.
	 * @param raf the open output file
	 * @param xPos world chunk X to store for a decoded terrain chunk
	 * @param zPos world chunk Z to store for a decoded terrain chunk
	 * @return the record length in bytes, including the length and compression fields
	 * @throws IOException if serialization or writing fails
	 * @throws UnsupportedOperationException if the chunk was partially loaded
	 */
	public int serialize(RandomAccessFile raf, int xPos, int zPos) throws IOException {
		if (partial) {
			throw new UnsupportedOperationException("Partially loaded chunks cannot be serialized");
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
		try (BufferedOutputStream nbtOut = new BufferedOutputStream(CompressionType.ZLIB.compress(baos))) {
			new NBTSerializer(false).toStream(new NamedTag(null, updateHandle(xPos, zPos)), nbtOut);
		}
		byte[] rawData = baos.toByteArray();
		raf.writeInt(rawData.length + 1); // including the byte to store the compression type
		raf.writeByte(CompressionType.ZLIB.getID());
		raf.write(rawData);
		return rawData.length + 5;
	}

	/**
	 * Loads all data in a single record. See {@link #deserialize(RandomAccessFile, long)}
	 * for the required file position.
	 * @param raf the open input file positioned at the record's compression byte
	 * @throws IOException if the record cannot be read or decoded
	 */
	public void deserialize(RandomAccessFile raf) throws IOException {
		deserialize(raf, ALL_DATA);
	}

	/**
	 * Reads a single chunk record with the file pointer at the compression byte,
	 * immediately after the four-byte record length. This direct API checks record
	 * framing without a region header or expected chunk coordinates.
	 * A successful read replaces the decoded state. If decoding fails, this chunk
	 * retains its previous state.
	 * @param raf the open input file positioned at the record's compression byte
	 * @param loadFlags the requested {@link LoadFlags}
	 * @throws IOException if the record cannot be read or decoded
	 */
	public void deserialize(RandomAccessFile raf, long loadFlags) throws IOException {
		long lengthOffset = raf.getFilePointer() - Integer.BYTES;
		if (lengthOffset < 0) {
			throw new IOException("Missing chunk record length before compression byte");
		}
		raf.seek(lengthOffset);
		int length = raf.readInt();
		if (length <= 1 || length > MCAFile.MAX_INLINE_RECORD_LENGTH || length > raf.length() - raf.getFilePointer()) {
			throw new IOException("Invalid chunk record length " + length);
		}
		deserialize(raf, length, loadFlags);
	}

	void deserialize(RandomAccessFile raf, int length, long loadFlags) throws IOException {
		replaceDecodedState(readChunkRecord(raf, length), loadFlags);
	}

	void deserialize(RandomAccessFile raf, int length, long loadFlags, long expectedChunkX, long expectedChunkZ)
			throws IOException {
		CompoundTag root = readChunkRecord(raf, length);
		validateTerrainCoordinates(root, expectedChunkX, expectedChunkZ);
		replaceDecodedState(root, loadFlags);
	}

	private void replaceDecodedState(CompoundTag root, long loadFlags) {
		// Section initialization can fail after decoding the root and some sections.
		Chunk decoded = new Chunk(lastMCAUpdate);
		decoded.data = root;
		decoded.initReferences(loadFlags);

		data = decoded.data;
		partial = decoded.partial;
		raw = decoded.raw;
		status = decoded.status;
		xPos = decoded.xPos;
		yPos = decoded.yPos;
		zPos = decoded.zPos;
		dataVersion = decoded.dataVersion;
		lastUpdate = decoded.lastUpdate;
		inhabitedTime = decoded.inhabitedTime;
		heightMaps = decoded.heightMaps;
		blockEntities = decoded.blockEntities;
		blockTicks = decoded.blockTicks;
		fluidTicks = decoded.fluidTicks;
		structures = decoded.structures;
		sections = decoded.sections;
		postProcessing = decoded.postProcessing;
		position = decoded.position;
		entities = decoded.entities;
	}

	private static void validateTerrainCoordinates(CompoundTag root, long expectedChunkX, long expectedChunkZ)
			throws IOException {
		// Recognize root-level terrain even when both coordinate tags are missing.
		if (root.containsKey("Entities") || !(root.containsKey("Status") || root.containsKey("sections")
				|| root.containsKey("xPos") || root.containsKey("yPos") || root.containsKey("zPos"))) {
			return;
		}
		Tag<?> x = root.get("xPos");
		Tag<?> z = root.get("zPos");
		if (!(x instanceof IntTag) || !(z instanceof IntTag)
				|| ((IntTag) x).asInt() != expectedChunkX || ((IntTag) z).asInt() != expectedChunkZ) {
			throw new IOException("expected chunk (" + expectedChunkX + ", " + expectedChunkZ + "), found xPos="
					+ describeCoordinate(x) + ", zPos=" + describeCoordinate(z));
		}
	}

	private static String describeCoordinate(Tag<?> coordinate) {
		if (coordinate == null) {
			return "missing";
		}
		if (coordinate instanceof IntTag) {
			return coordinate.getValue().toString();
		}
		return coordinate.getClass().getSimpleName() + " (expected TAG_Int)";
	}

	private static CompoundTag readChunkRecord(RandomAccessFile raf, int length) throws IOException {
		byte compressionTypeByte = raf.readByte();
		CompressionType compressionType = CompressionType.getFromID(compressionTypeByte);
		if (compressionType == null) {
			throw new IOException("Invalid compression type " + Byte.toUnsignedInt(compressionTypeByte));
		}
		byte[] payload = new byte[length - 1];
		raf.readFully(payload);
		return readChunkData(compressionType, payload);
	}

	private static CompoundTag readChunkData(CompressionType compressionType, byte[] payload) throws IOException {
		ByteArrayInputStream compressed = new ByteArrayInputStream(payload);
		if (compressionType != CompressionType.ZLIB) {
			try (InputStream input = new BufferedInputStream(compressionType.decompress(compressed))) {
				return readChunkNbt(input);
			}
		}

		Inflater inflater = new Inflater();
		try (InputStream input = new BufferedInputStream(new InflaterInputStream(compressed, inflater))) {
			CompoundTag root = readChunkNbt(input);
			if (inflater.getRemaining() != 0 || compressed.available() != 0) {
				throw new IOException("Trailing bytes after the chunk ZLIB stream");
			}
			return root;
		} finally {
			inflater.end();
		}
	}

	private static CompoundTag readChunkNbt(InputStream input) throws IOException {
		NamedTag tag = new NBTDeserializer(false).fromStream(input);
		if (tag == null || !(tag.getTag() instanceof CompoundTag)) {
			throw new IOException("Chunk NBT root must be a compound");
		}
		// Reading to EOF also validates the compression trailer after the NBT end tag.
		if (input.read() != -1) {
			throw new IOException("Trailing data after the chunk NBT root");
		}
		return (CompoundTag) tag.getTag();
	}

	/**
	 * Reads a block from this chunk. The caller selects the correct chunk.
	 * X and Z select positions within that chunk using their low four bits.
	 * Existing sections must have block state data.
	 * @param blockX the block X coordinate
	 * @param blockY the world block Y coordinate
	 * @param blockZ the block Z coordinate
	 * @return a detached state or {@code null} when the section is absent
	 */
	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
		Section section = sections.get(MCAUtil.blockToChunk(blockY));
		if (section == null) {
			return null;
		}
		return section.getBlockStateAt(blockX, blockY, blockZ);
	}

	Section checkStructureTarget(int blockX, int blockY, int blockZ) {
		if (raw || partial || entities != null || data == null || !data.containsKey("Status")
				|| !("minecraft:full".equals(status) || "full".equals(status))) {
			throw new IllegalStateException("Structure target at (" + blockX + ", " + blockY + ", " + blockZ
					+ ") requires a fully loaded, generated terrain chunk");
		}
		if (blockEntities != null) {
			for (CompoundTag entity : blockEntities) {
				Tag<?> x = entity.get("x");
				Tag<?> y = entity.get("y");
				Tag<?> z = entity.get("z");
				if (!(x instanceof IntTag) || !(y instanceof IntTag) || !(z instanceof IntTag)) {
					throw new IllegalStateException("Invalid block entity coordinates in structure target chunk");
				}
				if (((IntTag) x).asInt() == blockX && ((IntTag) y).asInt() == blockY && ((IntTag) z).asInt() == blockZ) {
					throw new IllegalStateException("Structure target overlaps a block entity at (" + blockX
							+ ", " + blockY + ", " + blockZ + ")");
				}
			}
		}
		return sections.get(MCAUtil.blockToChunk(blockY));
	}

	/**
	 * Edits this chunk using the rules of
	 * {@link MCAFile#setBlockStateAt(int, int, int, CompoundTag, boolean)}.
	 * The caller selects the correct chunk. A missing section can be created
	 * within the signed section Y range. Use fully loaded generated terrain.
	 * @param blockX the block X coordinate within this chunk, selected by its low four bits
	 * @param blockY the world block Y coordinate
	 * @param blockZ the block Z coordinate within this chunk, selected by its low four bits
	 * @param state the block state with {@code Name} and optional string-valued {@code Properties}
	 * @param cleanup whether to request palette compaction in the edited section
	 * @throws IllegalArgumentException if section Y cannot fit in a signed byte
	 * @throws UnsupportedOperationException if the chunk was loaded in RAW mode
	 */
	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
		checkRaw();
		int sectionIndex = MCAUtil.blockToChunk(blockY);
		if (sectionIndex < Byte.MIN_VALUE || sectionIndex > Byte.MAX_VALUE) {
			throw new IllegalArgumentException("Section Y does not fit in a signed byte: " + sectionIndex);
		}
		Section section = sections.get(sectionIndex);
		boolean newSection = section == null;
		CompoundTag previous = newSection ? null : section.getBlockStateReference(blockX, blockY, blockZ);
		if (sameBlockState(previous, state)) {
			if (!newSection && cleanup) {
				section.cleanupPaletteAndBlockStates();
			}
			return;
		}
		if (newSection) {
			section = Section.newSection(sectionIndex, dataVersion);
		}
		section.setBlockStateAt(blockX, blockY, blockZ, state, cleanup);
		if (newSection) {
			sections.put(sectionIndex, section);
		}
		invalidateBlockHeightmaps();
	}

	private void invalidateBlockHeightmaps() {
		if (heightMaps != null) {
			for (String key : BLOCK_HEIGHTMAP_KEYS) {
				heightMaps.remove(key);
			}
		}
	}

	private static boolean sameBlockState(CompoundTag previous, CompoundTag state) {
		if (previous == null) {
			return isPropertylessState(state, "minecraft:air");
		}
		String name = previous.getString("Name");
		return state.equals(previous) || (isPropertylessState(previous, name) && isPropertylessState(state, name));
	}

	private static boolean isPropertylessState(CompoundTag state, String name) {
		if (!name.equals(state.getString("Name"))) {
			return false;
		}
		CompoundTag properties = state.getCompoundTag("Properties");
		return properties == null ? state.size() == 1 : state.size() == 2 && properties.size() == 0;
	}

	/** @return the chunk timestamp from the MCA header, stored as seconds since the Unix epoch */
	public int getLastMCAUpdate() {
		return lastMCAUpdate;
	}

	/** Compacts the palettes and remaps the packed indices in this chunk's loaded sections. */
	public void cleanupPalettesAndBlockStates() {
		checkRaw();
		for (Section section : sections.values()) {
			if (section != null) {
				section.cleanupPaletteAndBlockStates();
			}
		}
	}

	private void checkRaw() {
		if (raw) {
			throw new UnsupportedOperationException("cannot update field when working with raw data");
		}
	}

	/**
	 * Creates a legacy placeholder with the historical default DataVersion.
	 * @return the placeholder chunk
	 * @see #newChunk(int)
	 */
	public static Chunk newChunk() {
		return newChunk(DEFAULT_DATA_VERSION);
	}

	/**
	 * Creates a legacy placeholder with an empty {@code Level} compound.
	 * Editing modern terrain expects existing generated chunks.
	 * @param dataVersion the version to write in the chunk
	 * @return the placeholder chunk
	 */
	public static Chunk newChunk(int dataVersion) {//todo new default chunk, 'Level' tag is deprecated
		Chunk c = new Chunk(0);
		c.dataVersion = dataVersion;
		c.data = new CompoundTag();
		c.data.put("Level", new CompoundTag());
		c.data.putByte("isLightOn", (byte) 0);
		c.status = "minecraft:full";
		return c;
	}

	/**
	 * Updates and returns the mutable root NBT retained by this chunk. In RAW mode
	 * this returns the decoded root as loaded. Use fully loaded data or RAW mode.
	 * <p>
	 * For decoded chunks this restores {@code DataVersion}. For terrain it also
	 * writes the supplied {@code xPos} and {@code zPos}, restores {@code Status},
	 * {@code LastUpdate}, and {@code InhabitedTime}, and rebuilds {@code sections}
	 * from the loaded sections. Cached {@code Heightmaps}, {@code block_entities},
	 * {@code block_ticks}, {@code fluid_ticks}, {@code structures}, and
	 * {@code PostProcessing} are restored when their cached references are present.
	 * For entity chunks this restores the cached {@code Position} and {@code Entities}.
	 * <p>
	 * Editing the contents of a retained cached compound or list in place changes
	 * that cached object. Replacing or removing its root-level tag leaves the cache
	 * as it was, so a later call or serialization restores the cached tag. Use the
	 * chunk and section APIs to change the decoded sections.
	 * @param xPos world chunk X for a decoded terrain root
	 * @param zPos world chunk Z for a decoded terrain root
	 * @return the live root tag
	 */
	public CompoundTag updateHandle(int xPos, int zPos) {
		if (raw) {
			return data;
		}

		data.putInt("DataVersion", dataVersion);

		if(entities == null) {// region mca chunk
			data.putInt("xPos", xPos);
			data.putInt("zPos", zPos);
			data.putLong("LastUpdate",lastUpdate);
			data.putLong("InhabitedTime", inhabitedTime);
			if (heightMaps != null) {
				data.put("Heightmaps", heightMaps);
			}
			if (blockEntities != null) {
				data.put("block_entities", blockEntities);
			}
			if (structures != null) {
				data.put("structures", structures);
			}
			if (blockTicks != null) {
				data.put("block_ticks", blockTicks);
			}
			if (fluidTicks != null) {
				data.put("fluid_ticks", fluidTicks);
			}
			if (postProcessing != null) {
				data.put("PostProcessing", postProcessing);
			}
			data.putString("Status", status);
			ListTag<CompoundTag> sections = new ListTag<>(CompoundTag.class);
			for (Section section : this.sections.values()) {
				if (section != null) {
					sections.add(section.updateHandle());
				}
			}
			data.put("sections", sections);
		}
		else {// entity mca chunk
			data.putIntArray("Position", position);
			data.put("Entities", entities);
		}

		return data;
	}

	@Override
	public Iterator<Section> iterator() {
		return sections.values().iterator();
	}
}
