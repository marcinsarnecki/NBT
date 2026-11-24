execute in minecraft:overworld if loaded 0 -64 0 if loaded 176 -64 208 if loaded 192 -64 208 if loaded 176 -64 256 if loaded 208 -64 256 if loaded 192 -64 288 run return run function nbt_fixture:internal/overworld
tellraw @s {text:"Overworld setup skipped. Run nbt_fixture:load_chunks and wait for all Overworld fixture chunks to load."}
return fail
