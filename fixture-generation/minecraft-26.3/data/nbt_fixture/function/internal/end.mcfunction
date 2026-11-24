# Seven portal block entities and an end_stone edit target in section Y=5 of chunk (0,0).
fill 0 80 0 15 95 15 minecraft:air
fill 5 83 5 11 83 12 minecraft:end_stone
setblock 6 83 7 minecraft:glowstone
fill 5 84 10 11 84 10 minecraft:end_portal

# Give chunk (0,4) open and obstructed columns at the bottom of the build range.
fill 0 0 64 7 255 71 minecraft:air
fill 8 0 64 15 0 71 minecraft:end_stone
tellraw @s {text:"End setup complete. Allow lighting to settle and check the below-build skylight section after saving."}
