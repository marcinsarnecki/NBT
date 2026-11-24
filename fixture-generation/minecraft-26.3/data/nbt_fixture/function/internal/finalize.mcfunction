# The five disjoint placements queue flowing_lava ticks. The fill queues the leaf ticks.
execute in minecraft:the_nether run setblock 117 85 21 minecraft:lava[level=1]
execute in minecraft:the_nether run setblock 119 85 21 minecraft:lava[level=2]
execute in minecraft:the_nether run setblock 121 85 21 minecraft:lava[level=3]
execute in minecraft:the_nether run setblock 123 85 21 minecraft:lava[level=4]
execute in minecraft:the_nether run setblock 125 85 21 minecraft:lava[level=5]
execute in minecraft:overworld run fill 192 -56 288 201 -56 291 minecraft:stone
tellraw @s {text:"Frozen edits complete. Save and Quit to Title now, then check the saved tick lists."}
