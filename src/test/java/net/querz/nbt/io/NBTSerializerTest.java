package net.querz.nbt.io;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.EndTag;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class NBTSerializerTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void toBytesWritesCompleteGzipInBothByteOrders() throws IOException {
		assertCompleteMember(new NBTSerializer().toBytes(tag()), expectedNbt("root", ByteOrder.BIG_ENDIAN));
		assertCompleteMember(new NBTSerializer(true, true).toBytes(tag()),
				expectedNbt("root", ByteOrder.LITTLE_ENDIAN));
	}

	@Test
	public void toFileWritesCompleteGzipInBothByteOrders() throws IOException {
		File file = temp.newFile();
		new NBTSerializer(true).toFile(tag(), file);
		assertCompleteMember(Files.readAllBytes(file.toPath()), expectedNbt("root", ByteOrder.BIG_ENDIAN));
		new NBTSerializer(true, true).toFile(tag(), file);
		assertCompleteMember(Files.readAllBytes(file.toPath()), expectedNbt("root", ByteOrder.LITTLE_ENDIAN));
	}

	@Test
	public void nbtUtilWritesCompleteGzipForNamedAndUnnamedTags() throws IOException {
		File file = temp.newFile();
		NamedTag named = tag();
		NBTUtil.write(named, file);
		assertCompleteMember(Files.readAllBytes(file.toPath()), expectedNbt("root", ByteOrder.BIG_ENDIAN));
		NBTUtil.write(named.getTag(), file.getPath(), true);
		assertCompleteMember(Files.readAllBytes(file.toPath()), expectedNbt("", ByteOrder.BIG_ENDIAN));
		NBTUtil.writeLE(named, file.getPath(), true);
		assertCompleteMember(Files.readAllBytes(file.toPath()), expectedNbt("root", ByteOrder.LITTLE_ENDIAN));
		NBTUtil.writeLE(named.getTag(), file);
		assertCompleteMember(Files.readAllBytes(file.toPath()), expectedNbt("", ByteOrder.LITTLE_ENDIAN));
	}

	@Test
	public void independentReaderRejectsTruncatedOrCorruptGzipTrailers() throws IOException {
		byte[] gzip = new NBTSerializer().toBytes(tag());
		assertCompleteMember(gzip, expectedNbt("root", ByteOrder.BIG_ENDIAN));
		assertInvalidMember(Arrays.copyOf(gzip, gzip.length - 1));

		byte[] badCrc = gzip.clone();
		badCrc[badCrc.length - 8] ^= 1;
		assertInvalidMember(badCrc);

		byte[] badSize = gzip.clone();
		badSize[badSize.length - 4] ^= 1;
		assertInvalidMember(badSize);
	}

	@Test
	public void toStreamFlushesCompleteGzipAndLeavesBufferedDestinationOpen() throws IOException {
		TrackingOutputStream sink = new TrackingOutputStream();
		BufferedOutputStream buffered = new BufferedOutputStream(sink);
		new NBTSerializer().toStream(tag(), buffered);
		byte[] gzip = sink.bytes.toByteArray();
		assertCompleteMember(gzip, expectedNbt("root", ByteOrder.BIG_ENDIAN));
		assertFalse(sink.closed);

		buffered.write(0x5a);
		buffered.flush();
		assertEquals(gzip.length + 1, sink.bytes.size());
		assertEquals(0x5a, sink.bytes.toByteArray()[gzip.length]);
	}

	@Test
	public void trailerWriteFailurePropagatesWithoutClosingDestination() throws IOException {
		NBTSerializer serializer = new NBTSerializer();
		byte[] gzip = serializer.toBytes(tag());
		assertCompleteMember(gzip, expectedNbt("root", ByteOrder.BIG_ENDIAN));
		int trailerStart = gzip.length - 8;
		IOException failure = new IOException("cannot write gzip trailer");
		TrackingOutputStream sink = new TrackingOutputStream() {
			@Override
			public void write(byte[] bytes, int offset, int length) throws IOException {
				int writable = Math.min(length, trailerStart - this.bytes.size());
				super.write(bytes, offset, writable);
				if (writable < length) {
					throw failure;
				}
			}
		};
		assertWriteFailure(serializer, sink, failure);
		assertEquals(trailerStart, sink.bytes.size());
	}

	@Test
	public void finalFlushFailurePropagatesWithoutClosingDestination() throws IOException {
		IOException failure = new IOException("cannot flush destination");
		TrackingOutputStream sink = new TrackingOutputStream() {
			@Override
			public void flush() throws IOException {
				throw failure;
			}
		};
		assertWriteFailure(new NBTSerializer(), sink, failure);
		assertCompleteMember(sink.bytes.toByteArray(), expectedNbt("root", ByteOrder.BIG_ENDIAN));
	}

	@Test
	public void repeatedWriteFailureDuringGzipCleanupPreservesOriginalException() {
		IOException failure = new IOException("destination failed");
		assertBodyAndCleanupWriteFailures(failure, failure);
		assertEquals(0, failure.getSuppressed().length);
	}

	@Test
	public void distinctGzipCleanupFailureIsSuppressedOnWriteFailure() {
		IOException bodyFailure = new IOException("cannot write compressed body");
		IOException cleanupFailure = new IOException("cannot finish gzip");
		assertBodyAndCleanupWriteFailures(bodyFailure, cleanupFailure);
		assertArrayEquals(new Throwable[]{cleanupFailure}, bodyFailure.getSuppressed());
	}

	@Test
	public void nbtWriteFailureLeavesDestinationOpen() throws IOException {
		CompoundTag invalid = new CompoundTag();
		invalid.put("invalid", EndTag.INSTANCE);
		TrackingOutputStream sink = new TrackingOutputStream();
		try {
			new NBTSerializer().toStream(new NamedTag("root", invalid), sink);
			fail("Expected an invalid NBT document to be rejected");
		} catch (IOException expected) {
			assertEquals("end tag not allowed", expected.getMessage());
		}
		assertFalse(sink.closed);
		int before = sink.bytes.size();
		sink.write(0x5a);
		assertEquals(before + 1, sink.bytes.size());
	}

	@Test
	public void uncompressedOutputPreservesBytesAndStreamOwnership() throws IOException {
		TrackingOutputStream sink = new TrackingOutputStream();
		new NBTSerializer(false).toStream(tag(), new BufferedOutputStream(sink));
		assertArrayEquals(expectedNbt("root", ByteOrder.BIG_ENDIAN), sink.bytes.toByteArray());
		assertFalse(sink.closed);

		assertArrayEquals(expectedNbt("root", ByteOrder.LITTLE_ENDIAN),
				new NBTSerializer(false, true).toBytes(tag()));
		File file = temp.newFile();
		NBTUtil.write(tag().getTag(), file, false);
		assertArrayEquals(expectedNbt("", ByteOrder.BIG_ENDIAN), Files.readAllBytes(file.toPath()));
		NBTUtil.writeLE(tag().getTag(), file, false);
		assertArrayEquals(expectedNbt("", ByteOrder.LITTLE_ENDIAN), Files.readAllBytes(file.toPath()));
	}

	private static NamedTag tag() {
		CompoundTag root = new CompoundTag();
		root.putInt("value", 0x01020304);
		return new NamedTag("root", root);
	}

	private static byte[] expectedNbt(String name, ByteOrder order) {
		byte[] rootName = name.getBytes(StandardCharsets.US_ASCII);
		byte[] key = "value".getBytes(StandardCharsets.US_ASCII);
		return ByteBuffer.allocate(11 + rootName.length + key.length).order(order)
				.put((byte) 10).putShort((short) rootName.length).put(rootName)
				.put((byte) 3).putShort((short) key.length).put(key).putInt(0x01020304)
				.put((byte) 0).array();
	}

	private static void assertCompleteMember(byte[] gzip, byte[] expected) throws IOException {
		assertArrayEquals(expected, inflateThroughEof(gzip));
		ByteBuffer trailer = ByteBuffer.wrap(gzip, gzip.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN);
		CRC32 crc = new CRC32();
		crc.update(expected);
		assertEquals(crc.getValue(), Integer.toUnsignedLong(trailer.getInt()));
		assertEquals(expected.length, Integer.toUnsignedLong(trailer.getInt()));
	}

	private static byte[] inflateThroughEof(byte[] gzip) throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
			byte[] buffer = new byte[32];
			int count;
			while ((count = in.read(buffer)) != -1) {
				raw.write(buffer, 0, count);
			}
		}
		return raw.toByteArray();
	}

	private static void assertInvalidMember(byte[] gzip) {
		try {
			inflateThroughEof(gzip);
			fail("Expected gzip trailer validation to fail");
		} catch (IOException expected) {
			// Reading to EOF forces the standard reader to validate the trailer.
		}
	}

	private static void assertWriteFailure(NBTSerializer serializer, TrackingOutputStream sink, IOException failure) {
		try {
			serializer.toStream(tag(), sink);
			fail("Expected the output failure to propagate");
		} catch (IOException actual) {
			assertSame(failure, actual);
		}
		assertFalse(sink.closed);
	}

	private static void assertBodyAndCleanupWriteFailures(IOException bodyFailure, IOException cleanupFailure) {
		// Force output during writeTag, before close tries to finish the member.
		byte[] payload = new byte[128 * 1024];
		new Random(123).nextBytes(payload);
		CompoundTag root = new CompoundTag();
		root.putByteArray("payload", payload);
		class FailingOutputStream extends TrackingOutputStream {
			int attempts;

			@Override
			public void write(byte[] data, int offset, int length) throws IOException {
				throw attempts++ == 0 ? bodyFailure : cleanupFailure;
			}
		}
		FailingOutputStream sink = new FailingOutputStream();
		try {
			new NBTSerializer().toStream(new NamedTag("root", root), sink);
			fail("Expected the original write failure");
		} catch (IOException actual) {
			assertSame(bodyFailure, actual);
		}
		assertEquals("Expected both the body and cleanup writes", 2, sink.attempts);
		assertFalse(sink.closed);
	}

	private static class TrackingOutputStream extends OutputStream {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		boolean closed;

		@Override
		public void write(int value) throws IOException {
			bytes.write(value);
		}

		@Override
		public void write(byte[] data, int offset, int length) throws IOException {
			bytes.write(data, offset, length);
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
