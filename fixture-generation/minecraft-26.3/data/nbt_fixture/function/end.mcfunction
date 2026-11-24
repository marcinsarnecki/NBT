execute in minecraft:the_end if loaded 0 80 0 if loaded 0 0 64 run return run function nbt_fixture:internal/end
tellraw @s {text:"End setup skipped. Run nbt_fixture:load_chunks and wait for both End fixture chunks to load."}
return fail
