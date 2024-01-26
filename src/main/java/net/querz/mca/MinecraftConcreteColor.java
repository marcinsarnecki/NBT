package net.querz.mca;

public enum MinecraftConcreteColor {
    WHITE("minecraft:white_concrete", 207, 213, 214),
    ORANGE("minecraft:orange_concrete", 224, 97, 1),
    MAGENTA("minecraft:magenta_concrete", 169, 48, 159),
    LIGHT_BLUE("minecraft:light_blue_concrete", 36, 137, 199),
    YELLOW("minecraft:yellow_concrete", 241, 175, 21),
    LIME("minecraft:lime_concrete", 94, 168, 24),
    PINK("minecraft:pink_concrete", 214, 101, 143),
    GRAY("minecraft:gray_concrete", 55, 58, 62),
    LIGHT_GRAY("minecraft:light_gray_concrete", 125, 125, 115),
    CYAN("minecraft:cyan_concrete", 21, 119, 136),
    PURPLE("minecraft:purple_concrete", 100, 31, 156),
    BLUE("minecraft:blue_concrete", 45, 47, 143),
    BROWN("minecraft:brown_concrete", 96, 60, 32),
    GREEN("minecraft:green_concrete", 73, 91, 36),
    RED("minecraft:red_concrete", 142, 33, 33),
    BLACK("minecraft:black_concrete", 8, 10, 15);

    private final String blockId;
    private final int r;
    private final int g;
    private final int b;

    MinecraftConcreteColor(String blockId, int r, int g, int b) {
        this.blockId = blockId;
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public String getBlockId() {
        return blockId;
    }

    public int getR() {
        return r;
    }

    public int getG() {
        return g;
    }

    public int getB() {
        return b;
    }

    public static MinecraftConcreteColor closestTo(int r, int g, int b) {
        MinecraftConcreteColor closestColor = null;
        double closestDistance = Double.MAX_VALUE;

        for (MinecraftConcreteColor color : values()) {
            double distance = Math.abs(color.r - r) + Math.abs(color.g - g) + Math.abs(color.b - b);
//            double distance = Math.sqrt(Math.pow(color.r - r, 2) + Math.pow(color.g - g, 2) + Math.pow(color.b - b, 2));
            if (distance < closestDistance) {
                closestDistance = distance;
                closestColor = color;
            }
        }

        return closestColor;
    }
}

