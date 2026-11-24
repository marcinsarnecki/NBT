# Chunk (0,0) stays as generated. The wall torch's support is in chunk (12,13).
setblock 189 -61 222 minecraft:dirt_path
setblock 192 -57 221 minecraft:stone
setblock 191 -57 221 minecraft:wall_torch[facing=west]

# Chunk (11,16). Keep this loot chest unopened.
setblock 178 -57 264 minecraft:stone
setblock 178 -56 264 minecraft:chest[facing=south,type=single,waterlogged=false]
data merge block 178 -56 264 {LootTable:"minecraft:chests/simple_dungeon",LootTableSeed:123L}

# Twenty additional property-free states give the chest section a five-bit palette.
setblock 176 -59 256 minecraft:white_concrete
setblock 177 -59 256 minecraft:orange_concrete
setblock 178 -59 256 minecraft:magenta_concrete
setblock 179 -59 256 minecraft:light_blue_concrete
setblock 180 -59 256 minecraft:yellow_concrete
setblock 181 -59 256 minecraft:lime_concrete
setblock 182 -59 256 minecraft:pink_concrete
setblock 183 -59 256 minecraft:gray_concrete
setblock 184 -59 256 minecraft:light_gray_concrete
setblock 185 -59 256 minecraft:cyan_concrete
setblock 186 -59 256 minecraft:purple_concrete
setblock 187 -59 256 minecraft:blue_concrete
setblock 188 -59 256 minecraft:brown_concrete
setblock 189 -59 256 minecraft:green_concrete
setblock 190 -59 256 minecraft:red_concrete
setblock 191 -59 256 minecraft:black_concrete
setblock 176 -59 257 minecraft:white_wool
setblock 177 -59 257 minecraft:orange_wool
setblock 178 -59 257 minecraft:magenta_wool
setblock 179 -59 257 minecraft:light_blue_wool

# Chunk (12,18). Twenty-nine property-free additions, leaves, and stone for six-bit indices.
clone 176 -59 256 191 -59 257 192 -59 288
setblock 196 -59 289 minecraft:yellow_wool
setblock 197 -59 289 minecraft:lime_wool
setblock 198 -59 289 minecraft:pink_wool
setblock 199 -59 289 minecraft:gray_wool
setblock 200 -59 289 minecraft:light_gray_wool
setblock 201 -59 289 minecraft:cyan_wool
setblock 202 -59 289 minecraft:purple_wool
setblock 203 -59 289 minecraft:blue_wool
setblock 204 -59 289 minecraft:brown_wool
fill 192 -55 288 201 -55 291 minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]

# Chunk (13,16). A separate chest with a neutral inventory.
setblock 210 -57 264 minecraft:stone
setblock 210 -56 264 minecraft:chest[facing=south,type=single,waterlogged=false]
item replace block 210 -56 264 container.0 with minecraft:stone 3
item replace block 210 -56 264 container.9 with minecraft:stick 2
tellraw @s {text:"Overworld setup complete. Keep ticks running so light and the initial leaf updates can settle."}
