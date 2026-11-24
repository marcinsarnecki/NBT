package net.querz.mca;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File-based MCA region I/O and conversions between block, chunk, and region coordinates. */
public final class MCAUtil {

	private MCAUtil() {}

	/**
	 * Reads all data from a region file. See {@link #read(File)} for the file naming and validation rules.
	 * @param file The file to read the data from.
	 * @return An in-memory representation of the MCA file with decompressed chunk data.
	 * @throws IOException if the file cannot be read or decoded
	 * @throws IllegalArgumentException if the filename does not identify a region
	 * */
	public static MCAFile read(String file) throws IOException {
		return read(new File(file), LoadFlags.ALL_DATA);
	}

	/**
	 * Reads all data from an MCA region. The filename supplies the region coordinates.
	 * Use this overload for editing and saving generated terrain.
	 * @param file The file to read the data from.
	 * @return An in-memory representation of the MCA file with decompressed chunk data.
	 * @throws IOException if the file cannot be read or decoded, or modern terrain coordinates do not match their slots
	 * @throws IllegalArgumentException if the filename does not identify a region
	 * */
	public static MCAFile read(File file) throws IOException {
		return read(file, LoadFlags.ALL_DATA);
	}

	/**
	 * Reads an MCA region with the requested loading mode. See {@link #read(File, long)}.
	 * @param file The file to read the data from.
	 * @return An in-memory representation of the MCA file with decompressed chunk data.
	 * @param loadFlags the requested {@link LoadFlags}
	 * @throws IOException if the file cannot be read or decoded, or modern terrain coordinates do not match their slots
	 * @throws IllegalArgumentException if the filename does not identify a region
	 * */
	public static MCAFile read(String file, long loadFlags) throws IOException {
		return read(new File(file), loadFlags);
	}

	/**
	 * Reads an MCA region using its filename to determine its coordinates. Chunk payloads
	 * are bounded by their declared record lengths. Modern terrain chunk coordinates
	 * are checked against their slots in every loading mode, including {@link LoadFlags#RAW}.
	 * @param file The file to read the data from.
	 * @return An in-memory representation of the MCA file with decompressed chunk data
	 * @param loadFlags the requested {@link LoadFlags}. Use {@link LoadFlags#ALL_DATA} for terrain edits
	 * @throws IOException if the file cannot be read or decoded, or modern terrain coordinates do not match their slots
	 * @throws IllegalArgumentException if the filename does not identify a region
	 * */
	public static MCAFile read(File file, long loadFlags) throws IOException {
		MCAFile mcaFile = newMCAFile(file);
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			mcaFile.deserialize(raf, loadFlags);
			return mcaFile;
		}
	}

	/**
	 * Calls {@link MCAUtil#write(MCAFile, File, boolean)} without changing the timestamps.
	 * @see MCAUtil#write(MCAFile, File, boolean)
	 * @param file The file to write to.
	 * @param mcaFile The data of the MCA file to write.
	 * @return The amount of chunks written to the file.
	 * @throws IOException If something goes wrong during serialization.
	 * */
	public static int write(MCAFile mcaFile, String file) throws IOException {
		return write(mcaFile, new File(file), false);
	}

	/**
	 * Calls {@link MCAUtil#write(MCAFile, File, boolean)} without changing the timestamps.
	 * @see MCAUtil#write(MCAFile, File, boolean)
	 * @param file The file to write to.
	 * @param mcaFile The data of the MCA file to write.
	 * @return The amount of chunks written to the file.
	 * @throws IOException If something goes wrong during serialization.
	 * */
	public static int write(MCAFile mcaFile, File file) throws IOException {
		return write(mcaFile, file, false);
	}

	/**
	 * @see MCAUtil#write(MCAFile, File, boolean)
	 * @param file The file to write to.
	 * @param mcaFile The data of the MCA file to write.
	 * @param changeLastUpdate whether to update the chunk timestamps in the region header
	 * @return The amount of chunks written to the file.
	 * @throws IOException If something goes wrong during serialization.
	 * */
	public static int write(MCAFile mcaFile, String file, boolean changeLastUpdate) throws IOException {
		return write(mcaFile, new File(file), changeLastUpdate);
	}

	/**
	 * Stages a region with ZLIB-compressed chunk records, then replaces the destination.
	 * Oversized inline records that require more than 255 sectors are rejected before
	 * replacement. An empty region writes just the 8,192-byte header.
	 * A recognized destination region filename must match the coordinates of the region.
	 * Other export names are accepted.
	 * @param file The file to write to.
	 * @param mcaFile The data of the MCA file to write.
	 * @param changeLastUpdate whether to update the chunk timestamps in the region header
	 * @return the number of chunk records written
	 * @throws IOException if the region cannot be serialized or written
	 * @throws IllegalArgumentException if a recognized destination region name has different coordinates
	 * @throws UnsupportedOperationException if the region contains a partially loaded chunk
	 * */
	public static int write(MCAFile mcaFile, File file, boolean changeLastUpdate) throws IOException {
		MCAFile namedRegion = parseRegionFileName(file.getName());
		if (namedRegion != null) {
			mcaFile.checkWriteRegion(namedRegion);
		}
		Path destination = file.toPath().toAbsolutePath();
		Path staged = Files.createTempFile(destination.getParent(), ".mca-region-", ".tmp");
		try {
			int chunks;
			try (RandomAccessFile raf = new RandomAccessFile(staged.toFile(), "rw")) {
				chunks = mcaFile.serializeToStagingFile(raf, changeLastUpdate);
			}
			Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
			return chunks;
		} finally {
			Files.deleteIfExists(staged);
		}
	}

	/**
	 * Turns the chunks coordinates into region coordinates and calls
	 * {@link MCAUtil#createNameFromRegionLocation(int, int)}
	 * @param chunkX The x-value of the location of the chunk.
	 * @param chunkZ The z-value of the location of the chunk.
	 * @return A mca filename in the format "r.{regionX}.{regionZ}.mca"
	 * */
	public static String createNameFromChunkLocation(int chunkX, int chunkZ) {
		return createNameFromRegionLocation( chunkToRegion(chunkX), chunkToRegion(chunkZ));
	}

	/**
	 * Turns the block coordinates into region coordinates and calls
	 * {@link MCAUtil#createNameFromRegionLocation(int, int)}
	 * @param blockX The x-value of the location of the block.
	 * @param blockZ The z-value of the location of the block.
	 * @return A mca filename in the format "r.{regionX}.{regionZ}.mca"
	 * */
	public static String createNameFromBlockLocation(int blockX, int blockZ) {
		return createNameFromRegionLocation(blockToRegion(blockX), blockToRegion(blockZ));
	}

	/**
	 * Creates a filename string from provided chunk coordinates.
	 * @param regionX The x-value of the location of the region.
	 * @param regionZ The z-value of the location of the region.
	 * @return A mca filename in the format "r.{regionX}.{regionZ}.mca"
	 * */
	public static String createNameFromRegionLocation(int regionX, int regionZ) {
		return "r." + regionX + "." + regionZ + ".mca";
	}

	/**
	 * Turns a block coordinate value into a chunk coordinate value.
	 * @param block The block coordinate value.
	 * @return The chunk coordinate value.
	 * */
	public static int blockToChunk(int block) {
		return block >> 4;
	}

	/**
	 * Turns a block coordinate value into a region coordinate value.
	 * @param block The block coordinate value.
	 * @return The region coordinate value.
	 * */
	public static int blockToRegion(int block) {
		return block >> 9;
	}

	/**
	 * Turns a chunk coordinate value into a region coordinate value.
	 * @param chunk The chunk coordinate value.
	 * @return The region coordinate value.
	 * */
	public static int chunkToRegion(int chunk) {
		return chunk >> 5;
	}

	/**
	 * Turns a region coordinate value into a chunk coordinate value.
	 * @param region The region coordinate value.
	 * @return The chunk coordinate value.
	 * */
	public static int regionToChunk(int region) {
		return region << 5;
	}

	/**
	 * Turns a region coordinate value into a block coordinate value.
	 * @param region The region coordinate value.
	 * @return The block coordinate value.
	 * */
	public static int regionToBlock(int region) {
		return region << 9;
	}

	/**
	 * Turns a chunk coordinate value into a block coordinate value.
	 * @param chunk The chunk coordinate value.
	 * @return The block coordinate value.
	 * */
	public static int chunkToBlock(int chunk) {
		return chunk << 4;
	}

	private static final Pattern mcaFilePattern = Pattern.compile("^.*r\\.(?<regionX>-?\\d+)\\.(?<regionZ>-?\\d+)\\.mca$");

	/**
	 * Creates an empty region using the coordinates in the given filename.
	 * @param file a path whose basename ends in {@code r.<regionX>.<regionZ>.mca}
	 * @return the empty region
	 * @throws IllegalArgumentException if the basename does not identify a region
	 */
	public static MCAFile newMCAFile(File file) {
		MCAFile region = parseRegionFileName(file.getName());
		if (region == null) {
			throw new IllegalArgumentException("invalid mca file name: " + file.getName());
		}
		return region;
	}

	private static MCAFile parseRegionFileName(String name) {
		Matcher m = mcaFilePattern.matcher(name);
		if (m.find()) {
			return new MCAFile(Integer.parseInt(m.group("regionX")), Integer.parseInt(m.group("regionZ")));
		}
		return null;
	}
}
