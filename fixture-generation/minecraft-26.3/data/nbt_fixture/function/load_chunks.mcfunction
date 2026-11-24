# Run first with ticks enabled. Forced chunk generation completes asynchronously.
execute in minecraft:overworld run gamerule minecraft:random_tick_speed 0
execute in minecraft:overworld run gamerule minecraft:spawn_mobs false
execute in minecraft:overworld run gamerule minecraft:fire_spread_radius_around_player 0
execute in minecraft:overworld run forceload add 0 0
execute in minecraft:overworld run forceload add 176 208
execute in minecraft:overworld run forceload add 192 208
execute in minecraft:overworld run forceload add 176 256
execute in minecraft:overworld run forceload add 208 256
execute in minecraft:overworld run forceload add 192 288
execute in minecraft:the_nether run forceload add 112 16
execute in minecraft:the_nether run forceload add 128 144
execute in minecraft:the_end run forceload add 0 0
execute in minecraft:the_end run forceload add 0 64
tellraw @s {text:"Fixture chunks requested. Leave the world running before starting the setup phases."}
