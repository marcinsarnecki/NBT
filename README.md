# NBT and MCA

A Java library for Named Binary Tag (NBT) data and offline editing of Minecraft
Java Edition terrain regions. This project is based on Querz's original NBT
library. Copyright (c) 2016–2020 Querz. [Upstream project](https://github.com/Querz/NBT) ·
[MIT License](LICENSE)

The inherited library provides the NBT tag model, binary NBT and SNBT I/O, and
region-file APIs. This fork adds Minecraft 26.3 block palette support, region
and chunk record checks, block editing with missing-section creation and
heightmap invalidation, and block-only structure placement.

## Minecraft compatibility

The supported terrain workflow is reading and writing **ZLIB-compressed**
`region/*.mca` files with existing, fully generated chunks. Vanilla Minecraft
Java Edition **26.3 (`DataVersion=5023`)** was used to verify property-bearing
and plain block edits, creation of an Overworld section, edits to existing
Nether and End sections, and a MapartCraft staircase import. Automated tests
inspect serialized NBT and packed indices independently of the block getter.
Those checks cover these workflows on specific fixtures. World validation
and block registry compatibility remain the caller's responsibility.
[Fixture provenance](src/test/resources/net/querz/mca/palettes/README.md) ·
[Manual validation](manual/README.md)

| On-disk palette | Behavior |
| --- | --- |
| Plain strings | Compact string entries such as `minecraft:stone`. A property-free palette stays compact until an edit introduces a state with properties in 26.3. |
| Mixed | The 26.3 representation can combine string values wrapped under an empty key with block state compounds using `id` and `properties`. |
| Legacy compounds | State compounds use `Name` and optional `Properties`. The regression suite covers this encoding with a synthetic `DataVersion=4790` region. |

The Java block API uses a `CompoundTag` with `Name` and optional
`Properties` containing string values, regardless of the on-disk palette.
Each section stores 4,096 palette indices. The packing width comes from the
palette size, with a minimum of four bits. Indices stay within a single
64-bit word. A single-value section can omit the packed array. Edits
automatically repack when the width changes. Explicit palette cleanup can
remove unused entries and is optional for ordinary edits and structure imports.
Use `cleanupPalettesAndBlockStates()` on a region or chunk, or
`cleanupPaletteAndBlockStates()` on a section, when you want to compact it.
[Minecraft 26.3 block state format changes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

## Read, edit, and export a region

Save this as `EditRegion.java` and pass an existing Overworld `r.0.0.mca`
containing chunk `(0,0)` as the first argument. The example prints the previous
block name when available and exports the modified region to a new temporary
directory.

```java
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.tag.CompoundTag;

public class EditRegion {
    public static void main(String[] args) throws IOException {
        File input = new File(args[0]);
        MCAFile region = MCAUtil.read(input);
        CompoundTag previous = region.getBlockStateAt(3, -60, 3);
        if (previous != null) {
            System.out.println(previous.getString("Name"));
        }

        CompoundTag diamond = new CompoundTag();
        diamond.putString("Name", "minecraft:diamond_block");
        region.setBlockStateAt(3, -60, 3, diamond, false);

        CompoundTag log = new CompoundTag();
        log.putString("Name", "minecraft:jungle_log");
        CompoundTag properties = new CompoundTag();
        properties.putString("axis", "x");
        log.put("Properties", properties);
        region.setBlockStateAt(4, -60, 3, log, false);

        Path output = Files.createTempDirectory("nbt-edit-").resolve(input.getName());
        MCAUtil.write(region, output.toFile());
        System.out.println(output);
    }
}
```

- `MCAUtil.read` derives the region coordinates from the filename. It checks
  each modern terrain chunk's root-level integer `xPos` and `zPos` against
  its header slot. Block access through `MCAFile` checks world X/Z, including
  negative coordinates. The lower-level chunk slot APIs retain their
  region-local indexing. Use a full load for editing and saving terrain.
- The getter returns a detached state or `null` for an absent chunk or
  section. The setter stores its own copy of a new state. Writes need an
  existing chunk and can create a missing vertical section filled with air
  around the edit. Setting ordinary air in a missing section leaves it absent.
  Sections that contain only lighting are outside the block-editing workflow.
- A changed block removes `WORLD_SURFACE`, `OCEAN_FLOOR`, `MOTION_BLOCKING`,
  and `MOTION_BLOCKING_NO_LEAVES` from its chunk so Minecraft can rebuild
  them on load. Other heightmaps are preserved.
- Stored `isLightOn`, `SkyLight`, and `BlockLight` are preserved. Lighting
  recalculation is left to Minecraft or a relighting tool. A reload alone
  may leave lighting stale after an edit changes light emission or opacity.
- Block edits leave block entities and scheduled block and fluid ticks in
  place. Vanilla may discard an incompatible block entity after a block is
  replaced, losing data such as a chest inventory. Pending ticks can still
  refer to a replaced block or fluid. Keep the original region as a backup.
- Export to the same region filename in another directory. A recognized
  output filename must match the region coordinates. The writer stages the
  replacement and rejects inline records exceeding 255 sectors. An empty
  region writes an 8,192-byte header. External `.mcc` records, LZ4, and legacy
  `Level`-wrapped chunk editing are outside the supported terrain workflow.

## Import a block-only structure

Save this as `ImportStructure.java`. Pass the input Overworld region, a
gzip-compressed structure NBT file, and the world origin as `x y z`.

```java
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.querz.mca.AirMode;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.mca.StructureImporter;
import net.querz.mca.StructureNbt;

public class ImportStructure {
    public static void main(String[] args) throws IOException {
        File input = new File(args[0]);
        StructureNbt structure = StructureNbt.read(Paths.get(args[1]));
        MCAFile region = MCAUtil.read(input);
        int x = Integer.parseInt(args[2]);
        int y = Integer.parseInt(args[3]);
        int z = Integer.parseInt(args[4]);

        StructureImporter.place(structure, region, x, y, z, -64, 384, AirMode.SKIP_EXPLICIT);

        Path output = Files.createTempDirectory("nbt-structure-").resolve(input.getName());
        MCAUtil.write(region, output.toFile());
        System.out.println(output);
    }
}
```

Pass the destination's actual minimum Y and height to the importer. Direct
block edits check whether the section Y fits in the MCA format. The caller
enforces dimension-specific bounds for those edits.

| Standard vanilla dimension | Block Y (inclusive) | Importer `minY`, `height` |
| --- | --- | --- |
| Overworld | −64 through 319 | `-64, 384` |
| Nether | 0 through 255 | `0, 256` |
| End | 0 through 255 | `0, 256` |

`StructureNbt.read` accepts a single palette and validates the structure's
types, dimensions, positions, and palette indices. It rejects alternate
palettes, per-block NBT, and nonempty entity lists. `StructureImporter.place`
checks the full footprint against one region and the supplied build range.
Before applying any write, it checks for existing, fully loaded terrain
chunks, editable sections or gaps, and block entity overlaps. Unexpected
errors during placement have no rollback. Saving is a separate step.

`PLACE_EXPLICIT` writes listed air states. `SKIP_EXPLICIT` leaves listed air,
cave air, and void air untouched. Both modes leave omitted positions alone.
Multi-region placement, missing chunk generation, ordinary entity editing,
and DataVersion conversion are outside this workflow.
[Standalone MapartCraft example](examples/mapart-structure/README.md)

## Inherited NBT utilities

The tag model includes numeric and string tags, typed lists, compounds,
primitive arrays, and `EndTag` for compound termination or empty-list
encoding. `NBTUtil` reads gzip or uncompressed NBT, writes gzip by default,
and provides little-endian variants. Use `NamedTag` to supply a root name.
`SNBTUtil` reads and writes the string form of NBT. The default nesting limit is `Tag.DEFAULT_MAX_DEPTH`
(512). Exceeding it raises `MaxDepthReachedException`.

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.SNBTUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.FloatTag;
import net.querz.nbt.tag.ListTag;

public class NbtExample {
    public static void main(String[] args) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putString("name", "sample");
        ListTag<FloatTag> values = new ListTag<>(FloatTag.class);
        values.addFloat(1.25f);
        root.put("values", values);

        Path file = Files.createTempFile("nbt-example-", ".dat");
        NBTUtil.write(root, file.toFile());
        System.out.println(SNBTUtil.toSNBT(NBTUtil.read(file.toFile()).getTag()));
    }
}
```

## Build and test

The library targets Java 8 source and bytecode. Use JDK 11 to run the
repository's Gradle 6.5 wrapper. From the repository root.

```sh
./gradlew build
./gradlew test
```

On Windows use `.\gradlew.bat`. The library JAR is in `build/libs/`.
Compile the Java examples against that JAR. On JDK 9 or later, pass
`--release 8` to `javac` to target Java 8.

The default tests use checked-in fixtures and temporary outputs. With the
Gradle distribution and dependencies cached, run them with `--offline`.
Fixture generation is a separate workflow from testing. The guide covers
the three vanilla 26.3 dimension excerpts and the synthetic legacy fixture.
[Fixture generation](fixture-generation/README.md)
