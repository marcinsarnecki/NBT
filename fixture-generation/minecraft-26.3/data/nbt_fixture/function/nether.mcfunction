execute in minecraft:the_nether if loaded 112 80 16 if loaded 128 64 144 run return run function nbt_fixture:internal/nether
tellraw @s {text:"Nether setup skipped. Run nbt_fixture:load_chunks and wait for both Nether fixture chunks to load."}
return fail
