package net.querz.mca;

public enum MinecraftMob {
    ALLAY("minecraft:allay"),
    AXOLOTL("minecraft:axolotl"),
    BAT("minecraft:bat"),
    BEE("minecraft:bee"),
    BLAZE("minecraft:blaze"),
    CAT("minecraft:cat"),
    CAVE_SPIDER("minecraft:cave_spider"),
    CHICKEN("minecraft:chicken"),
    COD("minecraft:cod"),
    COW("minecraft:cow"),
    CREEPER("minecraft:creeper"),
    DOLPHIN("minecraft:dolphin"),
    DONKEY("minecraft:donkey"),
    DROWNED("minecraft:drowned"),
    ELDER_GUARDIAN("minecraft:elder_guardian"),
    ENDER_DRAGON("minecraft:ender_dragon"),
    ENDERMAN("minecraft:enderman"),
    ENDERMITE("minecraft:endermite"),
    EVOKER("minecraft:evoker"),
    FOX("minecraft:fox"),
    FROG("minecraft:frog"),
    GHAST("minecraft:ghast"),
    GIANT("minecraft:giant"),
    GLOW_SQUID("minecraft:glow_squid"),
    GOAT("minecraft:goat"),
    GUARDIAN("minecraft:guardian"),
    HOGLIN("minecraft:hoglin"),
    HORSE("minecraft:horse"),
    HUSK("minecraft:husk"),
    ILLUSIONER("minecraft:illusioner"),
    IRON_GOLEM("minecraft:iron_golem"),
    LLAMA("minecraft:llama"),
    MAGMA_CUBE("minecraft:magma_cube"),
    MOOSHROOM("minecraft:mooshroom"),
    MULE("minecraft:mule"),
    OCELOT("minecraft:ocelot"),
    PANDA("minecraft:panda"),
    PARROT("minecraft:parrot"),
    PHANTOM("minecraft:phantom"),
    PIG("minecraft:pig"),
    PIGLIN("minecraft:piglin"),
    PIGLIN_BRUTE("minecraft:piglin_brute"),
    PILLAGER("minecraft:pillager"),
    POLAR_BEAR("minecraft:polar_bear"),
    PUFFERFISH("minecraft:pufferfish"),
    RABBIT("minecraft:rabbit"),
    RAVAGER("minecraft:ravager"),
    SALMON("minecraft:salmon"),
    SHEEP("minecraft:sheep"),
    SHULKER("minecraft:shulker"),
    SILVERFISH("minecraft:silverfish"),
    SKELETON("minecraft:skeleton"),
    SKELETON_HORSE("minecraft:skeleton_horse"),
    SLIME("minecraft:slime"),
    SNOW_GOLEM("minecraft:snow_golem"),
    SPIDER("minecraft:spider"),
    STRIDER("minecraft:strider"),
    SQUID("minecraft:squid"),
    STRAY("minecraft:stray"),
    TADPOLE("minecraft:tadpole"),
    TRADER_LLAMA("minecraft:trader_llama"),
    TROPICAL_FISH("minecraft:tropical_fish"),
    TURTLE("minecraft:turtle"),
    VEX("minecraft:vex"),
    VILLAGER("minecraft:villager"),
    VINDICATOR("minecraft:vindicator"),
    WANDERING_TRADER("minecraft:wandering_trader"),
    WARDEN("minecraft:warden"),
    WITCH("minecraft:witch"),
    WITHER("minecraft:wither"),
    WITHER_SKELETON("minecraft:wither_skeleton"),
    WOLF("minecraft:wolf"),
    ZOGLIN("minecraft:zoglin"),
    ZOMBIE("minecraft:zombie"),
    ZOMBIE_HORSE("minecraft:zombie_horse"),
    ZOMBIE_VILLAGER("minecraft:zombie_villager"),
    ZOMBIFIED_PIGLIN("minecraft:zombified_piglin");

    private final String entityId;

    MinecraftMob(String entityId) {
        this.entityId = entityId;
    }

    public String getEntityId() {
        return entityId;
    }
}
