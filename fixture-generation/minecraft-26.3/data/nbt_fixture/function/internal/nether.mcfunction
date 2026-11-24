# Build a sealed chamber in chunk (7,1) and leave space for the frozen lava placements.
fill 112 80 16 127 95 31 minecraft:netherrack
fill 116 81 20 125 86 28 minecraft:air
setblock 120 83 24 minecraft:netherrack
setblock 117 84 27 minecraft:glowstone

# Two biomes at quart-grid boundaries in section Y=4 of chunk (8,9).
fillbiome 128 64 144 143 79 159 minecraft:nether_wastes
fillbiome 136 64 144 143 79 159 minecraft:crimson_forest
tellraw @s {text:"Nether setup complete. Flowing lava is added during the frozen phase."}
