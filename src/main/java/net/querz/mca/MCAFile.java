package net.querz.mca;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * One in-memory MCA region with up to 32 by 32 chunk slots.
 * Block methods take world coordinates. Chunk-slot methods take local indices
 * or reduce chunk X/Z modulo 32.
 */
public class MCAFile implements Iterable<Chunk> {
	private static final int SECTOR_SIZE = 4096;
	private static final int HEADER_SIZE = 2 * SECTOR_SIZE;
	private static final int MAX_CHUNK_SECTORS = 255;
	static final int MAX_INLINE_RECORD_LENGTH = MAX_CHUNK_SECTORS * SECTOR_SIZE - Integer.BYTES;

	/**
	 * Historical default DataVersion constant. Loaded chunks use their own stored versions.
	 * */
	public static final int DEFAULT_DATA_VERSION = 1628;

	private final int regionX, regionZ;
	private Chunk[] chunks;

	/**
	 * MCAFile represents a world save file used by Minecraft to store world
	 * data on the hard drive.
	 * This constructor needs the x- and z-coordinates of the stored region,
	 * which can usually be taken from the file name {@code r.x.z.mca}
	 * @param regionX The x-coordinate of this region.
	 * @param regionZ The z-coordinate of this region.
	 * */
	public MCAFile(int regionX, int regionZ) {
		this.regionX = regionX;
		this.regionZ = regionZ;
	}

	void checkWriteRegion(MCAFile destination) {
		if (regionX != destination.regionX || regionZ != destination.regionZ) {
			throw new IllegalArgumentException("Cannot write region (" + regionX + ", " + regionZ
					+ ") to a filename for region (" + destination.regionX + ", " + destination.regionZ + ")");
		}
	}

	/**
	 * Reads the entire region. See {@link #deserialize(RandomAccessFile, long)} for validation.
	 * @param raf The {@code RandomAccessFile} to read from.
	 * @throws IOException If something went wrong during deserialization.
	 * */
	public void deserialize(RandomAccessFile raf) throws IOException {
		deserialize(raf, LoadFlags.ALL_DATA);
	}

	/**
	 * Reads an MCA region starting at the file header. The decoded records must fit
	 * their declared lengths and allocations. Modern terrain records must contain
	 * root-level integer {@code xPos} and {@code zPos} matching their header slots
	 * and this region's coordinates. The check also applies to partial and RAW
	 * terrain loads. Opaque legacy records and separate entity records have
	 * distinct layouts outside that coordinate check.
	 * @param raf The {@code RandomAccessFile} to read from.
	 * @param loadFlags A logical or of {@link LoadFlags} constants indicating what data should be loaded
	 * @throws IOException if the region header, record framing, or checked chunk coordinates are invalid,
	 * or if reading fails
	 * */
	public void deserialize(RandomAccessFile raf, long loadFlags) throws IOException {
		long fileLength = raf.length();
		if (fileLength < HEADER_SIZE) {
			throw new IOException("Incomplete MCA region header");
		}
		Chunk[] loadedChunks = new Chunk[1024];
		for (int i = 0; i < 1024; i++) {
			raf.seek(i * Integer.BYTES);
			int location = raf.readInt();
			if (location == 0) {
				continue;
			}

			int sectorOffset = location >>> 8;
			int sectorCount = location & 0xFF;
			long recordOffset = (long) sectorOffset * SECTOR_SIZE;
			if (sectorOffset < 2 || sectorCount == 0 || recordOffset > fileLength - Integer.BYTES) {
				throw new IOException("Invalid chunk location at region slot " + i);
			}
			raf.seek(SECTOR_SIZE + i * Integer.BYTES);
			int timestamp = raf.readInt();
			raf.seek(recordOffset);
			int length = raf.readInt();
			if (length <= 1 || length > sectorCount * SECTOR_SIZE - Integer.BYTES
					|| length > fileLength - recordOffset - Integer.BYTES) {
				throw new IOException("Invalid chunk record length " + length + " at region slot " + i);
			}
			int localX = i % 32;
			int localZ = i / 32;
			long expectedChunkX = 32L * regionX + localX;
			long expectedChunkZ = 32L * regionZ + localZ;
			Chunk chunk = new Chunk(timestamp);
			try {
				chunk.deserialize(raf, length, loadFlags, expectedChunkX, expectedChunkZ);
			} catch (IOException e) {
				throw new IOException("Could not decode chunk record in region (" + regionX + ", " + regionZ
						+ ") at local slot " + i + " (" + localX + ", " + localZ + "): " + e.getMessage(), e);
			}
			loadedChunks[i] = chunk;
		}
		chunks = loadedChunks;
	}

	/**
	 * Calls {@link MCAFile#serialize(RandomAccessFile, boolean)} without updating any timestamps.
	 * @see MCAFile#serialize(RandomAccessFile, boolean)
	 * @param raf The {@code RandomAccessFile} to write to.
	 * @return The amount of chunks written to the file.
	 * @throws IOException If something went wrong during serialization.
	 * */
	public int serialize(RandomAccessFile raf) throws IOException {
		return serialize(raf, false);
	}

	/**
	 * Stages all chunk records before writing from offset zero to the supplied open file.
	 * The file is truncated to the serialized length. An empty region writes only its
	 * 8,192-byte header. Filename checks apply to {@link MCAUtil#write(MCAFile, File, boolean)}.
	 * An I/O failure during the final copy can leave the open file partially written.
	 * @param raf The {@code RandomAccessFile} to write to.
	 * @param changeLastUpdate whether to update the chunk timestamps in the region header
	 * @return The amount of chunks written to the file.
	 * @throws IOException if writing fails or a chunk record exceeds 255 sectors
	 * @throws UnsupportedOperationException if the region contains a partially loaded chunk
	 * */
	public int serialize(RandomAccessFile raf, boolean changeLastUpdate) throws IOException {
		Path staged = Files.createTempFile("mca-region-", ".tmp");
		try {
			int chunksWritten;
			try (RandomAccessFile stagingFile = new RandomAccessFile(staged.toFile(), "rw")) {
				chunksWritten = serializeToStagingFile(stagingFile, changeLastUpdate);
			}
			raf.seek(0);
			long length = Files.copy(staged, Channels.newOutputStream(raf.getChannel()));
			raf.setLength(length);
			return chunksWritten;
		} finally {
			Files.deleteIfExists(staged);
		}
	}

	// Only write here to a fresh, disposable file.
	int serializeToStagingFile(RandomAccessFile raf, boolean changeLastUpdate) throws IOException {
		raf.write(new byte[HEADER_SIZE]);
		int globalOffset = 2;
		int lastWritten = 0;
		int timestamp = (int) (System.currentTimeMillis() / 1000L);
		int chunksWritten = 0;
		int chunkXOffset = MCAUtil.regionToChunk(regionX);
		int chunkZOffset = MCAUtil.regionToChunk(regionZ);

		if (chunks == null) {
			return 0;
		}

		for (int cx = 0; cx < 32; cx++) {
			for (int cz = 0; cz < 32; cz++) {
				int index = getChunkIndex(cx, cz);
				Chunk chunk = chunks[index];
				if (chunk == null) {
					continue;
				}
				raf.seek(SECTOR_SIZE * globalOffset);
				lastWritten = chunk.serialize(raf, chunkXOffset + cx, chunkZOffset + cz);

				if (lastWritten == 0) {
					continue;
				}

				int sectors = (lastWritten - 1) / SECTOR_SIZE + 1;
				if (sectors > MAX_CHUNK_SECTORS) {
					throw new IOException("Chunk (" + (chunkXOffset + cx) + ", " + (chunkZOffset + cz)
							+ ") requires " + sectors + " sectors; MCA supports at most " + MAX_CHUNK_SECTORS);
				}
				chunksWritten++;

				raf.seek(index * 4);
				raf.writeByte(globalOffset >>> 16);
				raf.writeByte(globalOffset >> 8 & 0xFF);
				raf.writeByte(globalOffset & 0xFF);
				raf.writeByte(sectors);

				// write timestamp
				raf.seek(index * 4 + SECTOR_SIZE);
				raf.writeInt(changeLastUpdate ? timestamp : chunk.getLastMCAUpdate());

				globalOffset += sectors;
			}
		}

		// padding
		if (lastWritten % SECTOR_SIZE != 0) {
			raf.seek(globalOffset * SECTOR_SIZE - 1);
			raf.write(0);
		}
		return chunksWritten;
	}

	/**
	 * Set a specific Chunk at a specific index. The index must be in range of 0 - 1023.
	 * @param index The index of the Chunk.
	 * @param chunk the chunk to assign, or {@code null} to remove the record
	 * @throws IndexOutOfBoundsException If index is not in the range.
	 */
	public void setChunk(int index, Chunk chunk) {
		checkIndex(index);
		if (chunks == null) {
			chunks = new Chunk[1024];
		}
		chunks[index] = chunk;
	}

	/**
	 * Assigns the slot selected by {@link #getChunkIndex(int, int)}.
	 * Callers using world chunk coordinates must select the correct region first.
	 * @param chunkX The x-coordinate of the Chunk.
	 * @param chunkZ The z-coordinate of the Chunk.
	 * @param chunk the chunk to assign, or {@code null} to remove the record
	 */
	public void setChunk(int chunkX, int chunkZ, Chunk chunk) {
		setChunk(getChunkIndex(chunkX, chunkZ), chunk);
	}

	/**
	 * Returns the chunk data of a chunk at a specific index in this file.
	 * @param index The index of the chunk in this file.
	 * @return the chunk, or {@code null} if the slot is empty
	 * @throws IndexOutOfBoundsException if the index is outside 0 through 1023
	 * */
	public Chunk getChunk(int index) {
		checkIndex(index);
		if (chunks == null) {
			return null;
		}
		return chunks[index];
	}

	/**
	 * Returns the chunk at the slot selected by {@link #getChunkIndex(int, int)}.
	 * Callers using world chunk coordinates must select the correct region first.
	 * @param chunkX The x-coordinate of the chunk.
	 * @param chunkZ The z-coordinate of the chunk.
	 * @return the chunk, or {@code null} if the slot is empty
	 * */
	public Chunk getChunk(int chunkX, int chunkZ) {
		return getChunk(getChunkIndex(chunkX, chunkZ));
	}

	/**
	 * Calculates the header slot from chunk X and Z reduced modulo 32.
	 * Each argument may be local or a world chunk coordinate in the intended region.
	 * @param chunkX The x-coordinate of the chunk.
	 * @param chunkZ The z-coordinate of the chunk.
	 * @return The index of this chunk.
	 * */
	public static int getChunkIndex(int chunkX, int chunkZ) {
		return (chunkX & 0x1F) + (chunkZ & 0x1F) * 32;
	}

	private int checkIndex(int index) {
		if (index < 0 || index > 1023) {
			throw new IndexOutOfBoundsException();
		}
		return index;
	}

	Chunk requireChunk(int blockX, int blockZ) {
		int chunkX = MCAUtil.blockToChunk(blockX), chunkZ = MCAUtil.blockToChunk(blockZ);
		Chunk chunk = getChunk(chunkX, chunkZ);
		if (chunk == null) {
			throw new IllegalStateException("Cannot edit missing chunk (" + chunkX + ", " + chunkZ + ")");
		}
		return chunk;
	}

	void checkBlockCoordinates(int blockX, int blockZ) {
		if (MCAUtil.blockToRegion(blockX) != regionX || MCAUtil.blockToRegion(blockZ) != regionZ) {
			throw new IllegalArgumentException("World block coordinates (" + blockX + ", " + blockZ
					+ ") are outside region (" + regionX + ", " + regionZ + ")");
		}
	}

	/**
	 * Sets a block state in an existing chunk at world coordinates. A new state is
	 * stored as a detached copy. A missing vertical section can be created with
	 * other positions initialized to air. Setting ordinary air in an absent section
	 * leaves the section absent.
	 * A changed state invalidates the four standard terrain heightmaps in its chunk
	 * so Minecraft can rebuild them on load. Stored lighting is preserved without
	 * recalculation. Block entities and scheduled ticks are preserved. Use fully loaded terrain and supply a state
	 * valid for the destination's block registry and dimension.
	 * @param blockX The x-coordinate of the block.
	 * @param blockY The y-coordinate of the block.
	 * @param blockZ The z-coordinate of the block.
	 * @param state a compound with a {@code Name} string and optional {@code Properties} compound of string values
	 * @param cleanup whether to request palette compaction in the edited section
	 * @throws IllegalArgumentException if world X or Z is outside this region or section Y cannot fit in a signed byte
	 * @throws IllegalStateException if the target chunk is missing
	 * @throws UnsupportedOperationException if the chunk was loaded in RAW mode
	 */
	public void setBlockStateAt(int blockX, int blockY, int blockZ, CompoundTag state, boolean cleanup) {
		checkBlockCoordinates(blockX, blockZ);
		requireChunk(blockX, blockZ).setBlockStateAt(blockX, blockY, blockZ, state, cleanup);
	}

	public void createMonsterSpawner(int x, int y, int z, MinecraftMob entityType, short spawnCount, short spawnRange, short minSpawnDelay, short maxSpawnDelay, short playerActivationRange, short maxNearbyEntities, short delay) {
		checkBlockCoordinates(x, z);
		requireChunk(x, z).createMonsterSpawner(x, y, z, entityType, spawnCount, spawnRange, minSpawnDelay, maxSpawnDelay, playerActivationRange, maxNearbyEntities, delay);
	}

	public void createChest(int x, int y, int z, String facing, ListTag<CompoundTag> items) {
		checkBlockCoordinates(x, z);
		requireChunk(x, z).createChest(x, y, z, facing, items);
	}

	public void createZombie(int xPos, int yPos, int zPos) {
		requireChunk(xPos, zPos).createZombie(xPos, yPos, zPos);
	}

	public void drawImage(String file, int desiredWidth, int desiredHeight, int startX, int startY, int startZ) {//todo add option to choose x/z axis, now only x axis
		try {
			// Load the original image
			File inputFile = new File(file);
			BufferedImage inputImage = ImageIO.read(inputFile);

			// Create a new BufferedImage with desired dimensions
			BufferedImage outputImage = new BufferedImage(desiredWidth, desiredHeight, inputImage.getType());

			// Draw the original image onto the new BufferedImage
			Graphics2D g2d = outputImage.createGraphics();
			g2d.drawImage(inputImage, 0, 0, desiredWidth, desiredHeight, null);
			g2d.dispose();

			// Iterate over each pixel of the resized image
			for (int y = 0; y < desiredHeight; y++) {
				for (int x = 0; x < desiredWidth; x++) {
					int rgb = outputImage.getRGB(x, y);

					int red = (rgb >> 16) & 0xFF;
					int green = (rgb >> 8) & 0xFF;
					int blue = (rgb) & 0xFF;

					CompoundTag concrete = new CompoundTag();
                    concrete.putString("Name", MinecraftConcreteColor.closestTo(red, green, blue).getBlockId());
					int invertedY = startY + (desiredHeight - 1 - y); // Invert Y-coordinate here
					setBlockStateAt(startX + x, invertedY, startZ, concrete, false);
				}
			}
			File outputFile = new File("output/image.jpg");
			ImageIO.write(outputImage, "jpg", outputFile);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Reads a block state at world coordinates in this region. An existing
	 * section must have block state data.
	 * @param blockX the world block X
	 * @param blockY the world block Y
	 * @param blockZ the world block Z
	 * @return a detached copy of the block state, or {@code null} if the chunk or section is missing
	 * @throws IllegalArgumentException if the world X or Z coordinate is outside this region
	 */
	public CompoundTag getBlockStateAt(int blockX, int blockY, int blockZ) {
		checkBlockCoordinates(blockX, blockZ);
		int chunkX = MCAUtil.blockToChunk(blockX), chunkZ = MCAUtil.blockToChunk(blockZ);
		Chunk chunk = getChunk(chunkX, chunkZ);
		if (chunk == null) {
			return null;
		}
		return chunk.getBlockStateAt(blockX, blockY, blockZ);
	}

	/**
	 * Compacts palettes and remaps packed indices throughout the loaded region.
	 * Use {@link #setBlockStateAt(int, int, int, CompoundTag, boolean)} for ordinary
	 * edits. Saving after an edit does not require whole-region cleanup.
	 */
	public void cleanupPalettesAndBlockStates() {
		for (Chunk chunk : chunks) {
			if (chunk != null) {
				chunk.cleanupPalettesAndBlockStates();
			}
		}
	}

	@Override
	public Iterator<Chunk> iterator() {
		if (chunks == null) {
			return Collections.<Chunk>nCopies(1024, null).iterator();
		}
		return Arrays.stream(chunks).iterator();
	}
}
