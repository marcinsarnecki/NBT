package net.querz.nbt.io;

import net.querz.io.Serializer;
import net.querz.nbt.tag.Tag;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/** Serializes named binary tags as uncompressed NBT or a complete gzip member. */
public class NBTSerializer implements Serializer<NamedTag> {

	private boolean compressed, littleEndian;

	public NBTSerializer() {
		this(true);
	}

	public NBTSerializer(boolean compressed) {
		this.compressed = compressed;
	}

	public NBTSerializer(boolean compressed, boolean littleEndian) {
		this.compressed = compressed;
		this.littleEndian = littleEndian;
	}

	/**
	 * Writes and flushes one NBT document. In compressed mode the gzip member
	 * is complete on return. The caller owns the destination stream.
	 * @param object the named tag to serialize
	 * @param out the destination, which remains open on success or failure
	 * @throws IOException if writing, finishing gzip, or flushing fails
	 */
	@Override
	public void toStream(NamedTag object, OutputStream out) throws IOException {
		if (compressed) {
			writeCompressedTag(object, out);
		} else {
			writeTag(object, out);
			out.flush();
		}
	}

	private void writeCompressedTag(NamedTag object, OutputStream out) throws IOException {
		IOException writeFailure = null;
		try (GZIPOutputStream gzip = new GZIPOutputStream(new NonClosingOutputStream(out))) {
			try {
				writeTag(object, gzip);
			} catch (IOException e) {
				// Close before rethrowing in case the destination repeats this same exception.
				writeFailure = e;
			}
		} catch (IOException | RuntimeException | Error cleanupFailure) {
			if (writeFailure == null) {
				throw cleanupFailure;
			}
			if (cleanupFailure != writeFailure) {
				writeFailure.addSuppressed(cleanupFailure);
			}
		}
		if (writeFailure != null) {
			throw writeFailure;
		}
	}

	private void writeTag(NamedTag object, OutputStream out) throws IOException {
		NBTOutput nbtOut = littleEndian ? new LittleEndianNBTOutputStream(out) : new NBTOutputStream(out);
		nbtOut.writeTag(object, Tag.DEFAULT_MAX_DEPTH);
	}

	private static final class NonClosingOutputStream extends BufferedOutputStream {
		private NonClosingOutputStream(OutputStream out) {
			// Buffer the gzip header until the compressor is inside the resource scope.
			super(out);
		}

		@Override
		public void close() throws IOException {
			flush();
		}
	}
}
