import net.querz.mca.AirMode;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.mca.StructureImporter;
import net.querz.mca.StructureNbt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Imports a MapartCraft structure into one disposable region export. */
public final class MapartCraftImport {
    private static final String USAGE = "Usage: MapartCraftImport <input.mca> <structure.nbt> <output.mca>"
            + " <x> <y> <z> <minY> <height> <PLACE_EXPLICIT|SKIP_EXPLICIT>";

    private MapartCraftImport() {}

    public static void main(String[] args) {
        if (args.length != 9) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        try {
            importStructure(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Import rejected: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
        } catch (IOException | IllegalStateException e) {
            System.err.println("Import failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void importStructure(String[] args) throws IOException {
        Path input = Paths.get(args[0]);
        Path structurePath = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        int x = parseInteger(args[3], "x");
        int y = parseInteger(args[4], "y");
        int z = parseInteger(args[5], "z");
        int minY = parseInteger(args[6], "minY");
        int height = parseInteger(args[7], "height");
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        AirMode airMode;
        try {
            airMode = AirMode.valueOf(args[8]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Air mode must be PLACE_EXPLICIT or SKIP_EXPLICIT: " + args[8]);
        }

        if (!Files.isRegularFile(input)) {
            throw new IOException("Input region must be an existing file: " + input);
        }
        if (!Files.isRegularFile(structurePath)) {
            throw new IOException("Structure must be an existing file: " + structurePath);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(output)) {
                throw new IOException("Output path is not a regular file: " + output);
            }
            if (Files.isSameFile(input, output) || Files.isSameFile(structurePath, output)) {
                throw new IllegalArgumentException("Output must use a different file from both inputs: " + output);
            }
        }
        Path outputDirectory = output.toAbsolutePath().getParent();
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) {
            throw new IOException("Output must be in an existing directory: " + output);
        }

        StructureNbt structure = StructureNbt.read(structurePath);
        MCAFile region = MCAUtil.read(input.toFile());
        StructureImporter.place(structure, region, x, y, z, minY, height, airMode);
        int chunks = MCAUtil.write(region, output.toFile());
        System.out.printf("Wrote %d chunks to %s from a %d x %d x %d structure with %d listed blocks.%n",
                chunks, output.toAbsolutePath(), structure.getSizeX(), structure.getSizeY(),
                structure.getSizeZ(), structure.getBlocks().size());
    }

    private static int parseInteger(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a 32-bit integer: " + value);
        }
    }
}
