package net.querz.mca;

public enum MinecraftBiome {
    THE_VOID("minecraft:the_void"),
    PLAINS("minecraft:plains"),
    SUNFLOWER_PLAINS("minecraft:sunflower_plains"),
    SNOWY_PLAINS("minecraft:snowy_plains"),
    ICE_SPIKES("minecraft:ice_spikes"),
    DESERT("minecraft:desert"),
    SWAMP("minecraft:swamp"),
    MANGROVE_SWAMP("minecraft:mangrove_swamp"),
    FOREST("minecraft:forest"),
    FLOWER_FOREST("minecraft:flower_forest"),
    BIRCH_FOREST("minecraft:birch_forest"),
    DARK_FOREST("minecraft:dark_forest"),
    OLD_GROWTH_BIRCH_FOREST("minecraft:old_growth_birch_forest"),
    OLD_GROWTH_PINE_TAIGA("minecraft:old_growth_pine_taiga"),
    OLD_GROWTH_SPRUCE_TAIGA("minecraft:old_growth_spruce_taiga"),
    TAIGA("minecraft:taiga"),
    SNOWY_TAIGA("minecraft:snowy_taiga"),
    SAVANNA("minecraft:savanna"),
    SAVANNA_PLATEAU("minecraft:savanna_plateau"),
    WINDSWEPT_HILLS("minecraft:windswept_hills"),
    WINDSWEPT_GRAVELLY_HILLS("minecraft:windswept_gravelly_hills"),
    WINDSWEPT_FOREST("minecraft:windswept_forest"),
    WINDSWEPT_SAVANNA("minecraft:windswept_savanna"),
    JUNGLE("minecraft:jungle"),
    SPARSE_JUNGLE("minecraft:sparse_jungle"),
    BAMBOO_JUNGLE("minecraft:bamboo_jungle"),
    BADLANDS("minecraft:badlands"),
    ERODED_BADLANDS("minecraft:eroded_badlands"),
    WOODED_BADLANDS("minecraft:wooded_badlands"),
    MEADOW("minecraft:meadow"),
    CHERRY_GROVE("minecraft:cherry_grove"),
    GROVE("minecraft:grove"),
    SNOWY_SLOPES("minecraft:snowy_slopes"),
    FROZEN_PEAKS("minecraft:frozen_peaks"),
    JAGGED_PEAKS("minecraft:jagged_peaks"),
    STONY_PEAKS("minecraft:stony_peaks"),
    RIVER("minecraft:river"),
    FROZEN_RIVER("minecraft:frozen_river"),
    BEACH("minecraft:beach"),
    SNOWY_BEACH("minecraft:snowy_beach"),
    STONY_SHORE("minecraft:stony_shore"),
    WARM_OCEAN("minecraft:warm_ocean"),
    LUKEWARM_OCEAN("minecraft:lukewarm_ocean"),
    DEEP_LUKEWARM_OCEAN("minecraft:deep_lukewarm_ocean"),
    OCEAN("minecraft:ocean"),
    DEEP_OCEAN("minecraft:deep_ocean"),
    COLD_OCEAN("minecraft:cold_ocean"),
    DEEP_COLD_OCEAN("minecraft:deep_cold_ocean"),
    FROZEN_OCEAN("minecraft:frozen_ocean"),
    DEEP_FROZEN_OCEAN("minecraft:deep_frozen_ocean"),
    MUSHROOM_FIELDS("minecraft:mushroom_fields"),
    DRIPSTONE_CAVES("minecraft:dripstone_caves"),
    LUSH_CAVES("minecraft:lush_caves"),
    DEEP_DARK("minecraft:deep_dark"),
    NETHER_WASTES("minecraft:nether_wastes"),
    WARPED_FOREST("minecraft:warped_forest"),
    CRIMSON_FOREST("minecraft:crimson_forest"),
    SOUL_SAND_VALLEY("minecraft:soul_sand_valley"),
    BASALT_DELTAS("minecraft:basalt_deltas"),
    THE_END("minecraft:the_end"),
    END_HIGHLANDS("minecraft:end_highlands"),
    END_MIDLANDS("minecraft:end_midlands"),
    SMALL_END_ISLANDS("minecraft:small_end_islands"),
    END_BARRENS("minecraft:end_barrens");

    private final String biomeId;

    MinecraftBiome(String biomeId) {
        this.biomeId = biomeId;
    }

    public String getBiomeId() {
        return biomeId;
    }
}
