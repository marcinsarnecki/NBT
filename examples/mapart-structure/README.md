# Import a MapartCraft structure

[`MapartCraftImport.java`](MapartCraftImport.java) is a Java 8-compatible
command-line example. It reads a gzip-compressed MapartCraft structure using
`StructureNbt.read`, places it with `StructureImporter.place`, and writes the
edited region with `MCAUtil.write`. The example is compiled separately from
the library JAR.

## Build and run

Run these commands from the repository root. Use JDK 8 or 11 for the Gradle
wrapper and supply your own source paths. Use the same region filename for the
output in a separate directory so it can be opened as a Minecraft region.

### Windows PowerShell

```powershell
.\gradlew.bat jar
New-Item -ItemType Directory -Force -Path 'build/mapart-example', 'build/mapart-output' | Out-Null
javac -cp build/libs/nbt-6.1.jar -d build/mapart-example examples/mapart-structure/MapartCraftImport.java
java -cp "build/mapart-example;build/libs/nbt-6.1.jar" MapartCraftImport `
  "path/to/r.0.0.mca" "path/to/mapart.nbt" "build/mapart-output/r.0.0.mca" `
  64 -40 63 -64 384 SKIP_EXPLICIT
```

### Linux or macOS

```sh
./gradlew jar
mkdir -p build/mapart-example build/mapart-output
javac -cp build/libs/nbt-6.1.jar -d build/mapart-example examples/mapart-structure/MapartCraftImport.java
java -cp "build/mapart-example:build/libs/nbt-6.1.jar" MapartCraftImport \
  "path/to/r.0.0.mca" "path/to/mapart.nbt" "build/mapart-output/r.0.0.mca" \
  64 -40 63 -64 384 SKIP_EXPLICIT
```

Use a Java 8 JDK, or add `--release 8` to the `javac` command on a newer
JDK, if you want to run the compiled example on Java 8.

The arguments are the input region, structure, output region, world origin
`x y z`, destination `minY height`, and air mode. The sample uses Overworld
bounds. For the standard Nether or End, use `0 256` and choose a suitable
world origin. `PLACE_EXPLICIT` writes listed air states. `SKIP_EXPLICIT`
skips listed air, cave air, and void air. Positions omitted from the
structure are left alone in both modes.

The output directory must already exist. A separate existing output file
can be overwritten. The example rejects paths that refer to either input
file, including filesystem aliases. It prints errors to standard error and
returns a nonzero exit code on failure.

## Placement limits

The entire structure must fit in one region and the supplied build bounds.
Listed writes require existing, fully generated terrain chunks. The
importer can create missing vertical sections and rejects writes over
existing block entities. The structure must have one palette and contain
block states only. The caller is responsible for destination block registry
compatibility.

The example uses the importer’s normal heightmap handling and preserves
stored lighting. Edits that affect light emission or opacity can leave
stale lighting. The [regression tests](../../src/test/java/net/querz/mca/StructureImporterTest.java)
exercise placement and inspect the serialized result independently of the
block getter.
