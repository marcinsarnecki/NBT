# Run immediately after the operator executes /tick freeze in chat.
execute in minecraft:the_nether if loaded 112 80 16 if block 117 84 27 minecraft:glowstone if block 117 85 21 minecraft:air in minecraft:the_end if loaded 5 84 10 if block 5 84 10 minecraft:end_portal in minecraft:overworld if loaded 192 -55 288 if block 192 -55 288 minecraft:oak_leaves[persistent=true] if block 192 -56 288 minecraft:air run return run function nbt_fixture:internal/finalize
tellraw @s {text:"Finalization skipped. Check that all three setup phases completed and this phase has not already run."}
return fail
