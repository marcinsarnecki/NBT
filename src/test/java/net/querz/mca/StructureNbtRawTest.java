package net.querz.mca;

import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.EndTag;
import net.querz.nbt.tag.ListTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StructureNbtRawTest {
	private static final int TAG_END = 0;
	private static final int TAG_INT = 3;
	private static final int TAG_STRING = 8;
	private static final int TAG_LIST = 9;
	private static final int TAG_COMPOUND = 10;

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void rejectsDuplicateBlocksThatHideAPlacement() throws IOException {
		Path path = writeStructure(false, out -> {
			writeBlock(out);
			writeListHeader(out, "blocks", TAG_COMPOUND, 0);
		});

		assertEquals(0, readWithDefaultDecoder(path).getListTag("blocks").size());
		assertStructureRejected(path, "Duplicate compound tag name", "blocks");
	}

	@Test
	public void rejectsDuplicateEntitiesThatHideUnsupportedData() throws IOException {
		Path path = writeStructure(false, out -> {
			writeBlock(out);
			writeListHeader(out, "entities", TAG_COMPOUND, 1);
			writeString(out, "id", "minecraft:zombie");
			out.writeByte(TAG_END);
			writeListHeader(out, "entities", TAG_END, 0);
		});

		assertEquals(0, readWithDefaultDecoder(path).getListTag("entities").size());
		assertStructureRejected(path, "Duplicate compound tag name", "entities");
	}

	@Test
	public void rejectsDuplicateNamesInsideNestedCompounds() throws IOException {
		Path path = writeStructure(true, StructureNbtRawTest::writeBlock);
		CompoundTag state = readWithDefaultDecoder(path).getListTag("palette").asCompoundTagList().get(0);
		assertEquals("y", state.getCompoundTag("Properties").getString("axis"));
		assertStructureRejected(path, "Duplicate compound tag name", "axis");
	}

	@Test
	public void rejectsUnknownTypesInEmptyLists() throws IOException {
		for (int type : new int[]{99, 255}) {
			Path path = writeStructure(false, out -> {
				writeBlock(out);
				writeListHeader(out, "entities", type, 0);
			});

			ListTag<?> entities = readWithDefaultDecoder(path).getListTag("entities");
			assertEquals(0, entities.size());
			assertEquals(EndTag.class, entities.getTypeClass());
			assertStructureRejected(path, "Unknown list element type ID", Integer.toString(type));
		}
	}

	@Test
	public void acceptsEndTypedAndCompoundTypedEmptyLists() throws IOException {
		for (int type : new int[]{TAG_END, TAG_COMPOUND}) {
			Path path = writeStructure(false, out -> {
				writeListHeader(out, "blocks", type, 0);
				writeListHeader(out, "entities", type, 0);
			});

			assertTrue(StructureNbt.read(path).getBlocks().isEmpty());
		}
	}

	@Test
	public void readsAValidRawStructure() throws IOException {
		Path path = writeStructure(false, StructureNbtRawTest::writeBlock);
		StructureNbt structure = StructureNbt.read(path);
		assertEquals(5023, structure.getDataVersion());
		assertEquals(2, structure.getSizeX());
		assertEquals(1, structure.getSizeY());
		assertEquals(1, structure.getSizeZ());
		CompoundTag state = structure.getPalette().get(0);
		assertEquals("minecraft:jungle_log", state.getString("Name"));
		assertEquals("y", state.getCompoundTag("Properties").getString("axis"));
		assertEquals(1, structure.getBlocks().size());
		StructureNbt.Block block = structure.getBlocks().get(0);
		assertEquals(1, block.getX());
		assertEquals(0, block.getY());
		assertEquals(0, block.getZ());
		assertEquals(0, block.getStateIndex());
	}

	private static void assertStructureRejected(Path path, String... details) throws IOException {
		try {
			StructureNbt.read(path);
			fail("Expected malformed structure NBT to be rejected");
		} catch (IOException expected) {
			for (String detail : details) {
				assertTrue(expected.getMessage(), expected.getMessage().contains(detail));
			}
		}
	}

	private static CompoundTag readWithDefaultDecoder(Path path) throws IOException {
		try (InputStream input = Files.newInputStream(path); GZIPInputStream gzip = new GZIPInputStream(input)) {
			return (CompoundTag) new NBTDeserializer(false).fromStream(gzip).getTag();
		}
	}

	// Write binary NBT directly so duplicate names and unknown IDs reach the decoder.
	private Path writeStructure(boolean duplicateAxis, RootTags tags) throws IOException {
		Path path = temp.newFile().toPath();
		try (OutputStream file = Files.newOutputStream(path);
				DataOutputStream out = new DataOutputStream(new GZIPOutputStream(file))) {
			writeHeader(out, TAG_COMPOUND, "");
			writeHeader(out, TAG_INT, "DataVersion");
			out.writeInt(5023);
			writeCoordinates(out, "size", 2, 1, 1);
			writeListHeader(out, "palette", TAG_COMPOUND, 1);
			writeString(out, "Name", "minecraft:jungle_log");
			writeHeader(out, TAG_COMPOUND, "Properties");
			if (duplicateAxis) {
				writeString(out, "axis", "x");
			}
			writeString(out, "axis", "y");
			out.writeByte(TAG_END);
			out.writeByte(TAG_END);
			tags.write(out);
			out.writeByte(TAG_END);
		}
		return path;
	}

	private static void writeBlock(DataOutputStream out) throws IOException {
		writeListHeader(out, "blocks", TAG_COMPOUND, 1);
		writeCoordinates(out, "pos", 1, 0, 0);
		writeHeader(out, TAG_INT, "state");
		out.writeInt(0);
		out.writeByte(TAG_END);
	}

	private static void writeCoordinates(DataOutputStream out, String name, int x, int y, int z) throws IOException {
		writeListHeader(out, name, TAG_INT, 3);
		out.writeInt(x);
		out.writeInt(y);
		out.writeInt(z);
	}

	private static void writeString(DataOutputStream out, String name, String value) throws IOException {
		writeHeader(out, TAG_STRING, name);
		out.writeUTF(value);
	}

	private static void writeListHeader(DataOutputStream out, String name, int type, int length) throws IOException {
		writeHeader(out, TAG_LIST, name);
		out.writeByte(type);
		out.writeInt(length);
	}

	private static void writeHeader(DataOutputStream out, int type, String name) throws IOException {
		out.writeByte(type);
		out.writeUTF(name);
	}

	private interface RootTags {
		void write(DataOutputStream out) throws IOException;
	}
}
