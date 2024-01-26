package net.querz.mca;
import java.util.concurrent.ThreadLocalRandom;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.FloatTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static net.querz.mca.LoadFlags.*;

public class Chunk implements Iterable<Section> {// todo maybe split this class into region chunk class and entity chunk class?

	public static final int DEFAULT_DATA_VERSION = 3700;

	private boolean partial;
	private boolean raw;

	private int lastMCAUpdate;

	private CompoundTag data;

	private String status;
	private int xPos, zPos, yPos;
	private int dataVersion;
	private long lastUpdate;
	private long inhabitedTime;

	private CompoundTag heightMaps;// keys: "OCEAN_FLOOR", "WORLD_SURFACE", "MOTION_BLOCKING_NO_LEAVES", "MOTION_BLOCKING"
	private boolean isLightOn;
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
	 * Create a new chunk based on raw base data from a region file.
	 * @param data The raw base data to be used.
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
			isLightOn = data.getByteTag("isLightOn").asBoolean();
			blockEntities = data.getListTag("block_entities").asCompoundTagList();
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

		blockEntities.add(spawnerData);
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

		blockEntities.add(chestData);
	}


	public void clearChunk() {
		for(Section section: sections.values()) {
			section.clearSection();
		}
	}

	public void setBiome(MinecraftBiome biome) {
		for(Section section: sections.values()) {
			section.setBiome(biome);
		}
	}

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

	public void deserialize(RandomAccessFile raf) throws IOException {
		deserialize(raf, ALL_DATA);
	}

	public void deserialize(RandomAccessFile raf, long loadFlags) throws IOException {
		byte compressionTypeByte = raf.readByte();
		CompressionType compressionType = CompressionType.getFromID(compressionTypeByte);
		if (compressionType == null) {
			throw new IOException("invalid compression type " + compressionTypeByte);
		}
		BufferedInputStream dis = new BufferedInputStream(compressionType.decompress(new FileInputStream(raf.getFD())));//normalny stream bajtow, ktory idzie do nbt deserializera
		NamedTag tag = new NBTDeserializer(false).fromStream(dis);
		if (tag != null && tag.getTag() instanceof CompoundTag) {
			data = (CompoundTag) tag.getTag();
			initReferences(loadFlags);
		} else {
			throw new IOException("invalid data tag: " + (tag == null ? "null" : tag.getClass().getName()));
		}
	}

	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
		Section section = sections.get(MCAUtil.blockToChunk(blockY));
		if (section == null) {
			return null;
		}
		return section.getBlockStateAt(blockX, blockY, blockZ);
	}

	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
		checkRaw();
		int sectionIndex = MCAUtil.blockToChunk(blockY);
		Section section = sections.get(sectionIndex);
		if (section == null) {
			sections.put(sectionIndex, section = Section.newSection());//czy tu nie ma bledu? sekcja musi miec y !
		}
		section.setBlockStateAt(blockX, blockY, blockZ, state, cleanup);
	}

	public int getLastMCAUpdate() {
		return lastMCAUpdate;
	}

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

	public static Chunk newChunk() {
		return newChunk(DEFAULT_DATA_VERSION);
	}

	public static Chunk newChunk(int dataVersion) {//todo new default chunk, 'Level' tag is deprecated
		Chunk c = new Chunk(0);
		c.dataVersion = dataVersion;
		c.data = new CompoundTag();
		c.data.put("Level", new CompoundTag());
		c.status = "minecraft:full";
		return c;
	}

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
			data.putByte("isLightOn", (byte) (isLightOn ? 1 : 0));
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
