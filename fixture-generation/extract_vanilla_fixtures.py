"""Copy the vanilla chunk records used by the regression suite."""

import argparse
import hashlib
from pathlib import Path
import struct
import zlib


REPOSITORY = Path(__file__).resolve().parents[1]
SOURCES = REPOSITORY / "manual/input/replacements"
FIXTURES = REPOSITORY / "src/test/resources/net/querz/mca/palettes"
SECTOR_SIZE = 4096
CHUNKS = {
    "overworld": [(0, 0), (11, 13), (11, 16), (13, 16), (12, 18)],
    "nether": [(7, 1), (8, 9)],
    "end": [(0, 0), (0, 4)],
}


def extract(source, chunks):
    raw = source.read_bytes()
    header = bytearray(2 * SECTOR_SIZE)
    records = bytearray()
    if len(raw) < len(header) or len(raw) % SECTOR_SIZE:
        raise ValueError(f"{source}: invalid region size")

    for x, z in chunks:
        slot_offset = 4 * (x + 32 * z)
        location, = struct.unpack_from(">I", raw, slot_offset)
        offset = (location >> 8) * SECTOR_SIZE
        allocated = (location & 255) * SECTOR_SIZE
        if offset < len(header) or not allocated or offset + allocated > len(raw):
            raise ValueError(f"{source}: missing or invalid chunk allocation at {(x, z)}")

        length, codec = struct.unpack_from(">IB", raw, offset)
        if not 2 <= length <= allocated - 4 or codec != 2:
            raise ValueError(f"{source}: invalid inline ZLIB record at {(x, z)}")
        record = raw[offset:offset + 4 + length]
        inflater = zlib.decompressobj()
        inflater.decompress(record[5:])
        if not inflater.eof or inflater.unused_data:
            raise ValueError(f"{source}: invalid ZLIB framing at {(x, z)}")

        sector = (len(header) + len(records)) // SECTOR_SIZE
        sectors = (len(record) + SECTOR_SIZE - 1) // SECTOR_SIZE
        struct.pack_into(">I", header, slot_offset, sector << 8 | sectors)
        timestamp = SECTOR_SIZE + slot_offset
        header[timestamp:timestamp + 4] = raw[timestamp:timestamp + 4]
        records.extend(record.ljust(sectors * SECTOR_SIZE, b"\0"))

    return bytes(header + records), hashlib.sha256(raw).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check the fixtures without writing them")
    args = parser.parse_args()

    extracted = {}
    try:
        for dimension, chunks in CHUNKS.items():
            path = Path(dimension) / "r.0.0.mca"
            extracted[path] = extract(SOURCES / path, chunks)
    except (OSError, ValueError, zlib.error) as error:
        parser.exit(1, f"{error}\n")

    for path, (data, source_hash) in extracted.items():
        fixture = FIXTURES / path
        if args.check:
            if not fixture.is_file() or fixture.read_bytes() != data:
                parser.exit(1, f"{fixture}: differs from the vanilla excerpt\n")
        else:
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_bytes(data)
        print(f"{path.as_posix()} source  {source_hash}")
        print(f"{path.as_posix()} fixture {hashlib.sha256(data).hexdigest()} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
