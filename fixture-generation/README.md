# Reproduce the MCA fixtures

Use one fresh Minecraft Java 26.3 singleplayer save for the modern Overworld,
Nether, and End source regions. The [`minecraft-26.3`](minecraft-26.3) datapack
uses data pack format 121.0. The vanilla chunks have `DataVersion=5023`.

The legacy regression fixture is synthetic. Its
[standalone Python generator](generate_legacy_fixture.py) and the
[vanilla excerpt script](extract_vanilla_fixtures.py) use the Python 3.8+
standard library. The default Gradle tests use the resulting fixture files.

## Create the 26.3 world and install the datapack

1. Launch Minecraft Java 26.3. Create a new singleplayer world
   named `NBT Fixture 26.3`. Use seed `1`, Creative mode,
   Peaceful difficulty, and **Allow Commands**.
2. In the world settings choose **Superflat**, then **Customize → Presets →
   Classic Flat**. Turn **Generate Structures** off. Leave the vanilla dimension
   settings in place. Classic Flat has bedrock at Y=-64, dirt at Y=-63 and -62,
   and grass at Y=-61.
3. Enter the world, then **Save and Quit to Title**. In **Singleplayer** select
   the new world, choose **Edit → Open World Folder**, and copy the
   `minecraft-26.3` folder directly into that save's `datapacks` folder. The
   pack metadata must be at
   `NBT Fixture 26.3/datapacks/minecraft-26.3/pack.mcmeta`, with `data` alongside it.
4. Reopen the save and run `/datapack list enabled`. Check for
   `file/minecraft-26.3`. If absent, run `/datapack enable "file/minecraft-26.3"`
   and check again. Invoke the functions from chat.

## Prepare the 26.3 world

Run this first with the game unpaused.

```mcfunction
/function nbt_fixture:load_chunks
```

Run the setup functions from the Overworld. `load_chunks` uses
`execute in … run forceload add` to request the target chunks in all three
dimensions remotely. Wait for generation with the world running. Then run
these commands individually.

```mcfunction
/function nbt_fixture:overworld
/function nbt_fixture:nether
/function nbt_fixture:end
```

The setup functions switch command context and perform edits remotely while
your player stays in place. Each phase checks that its target chunks are loaded.
If a phase was skipped, leave the game unpaused and retry it. A completed phase
prints a confirmation. If a command reports an error during a phase, check the
log before collecting the files.

### Open the chests and activate the End exit fountain

The source save used for the tracked fixtures included these manual actions
after setup and before the final frozen save.

1. Open and close both Overworld chests without taking or adding items. Go to
   `(178,-56,264)` for the loot chest and then `(210,-56,264)` for the neutral
   chest. Opening the first chest materializes its loot, so its saved NBT has
   `Items` in place of the loot-table tags. The second chest retains the three
   stone and two sticks placed by the datapack. The regression suite creates a
   temporary loot-table variant of the first chest.
2. In the End, kill the dragon with a command. The setup functions edit the End
   remotely, and this separate visit lets the dragon fight and death sequence
   complete with a player present. From the Overworld, run the following.

   ```mcfunction
   /gamemode spectator
   /execute in minecraft:the_end run tp @s 8 98 8
   /kill @e[type=minecraft:ender_dragon,limit=1]
   ```

   If the dragon has not spawned yet, give the dimension time to tick before
   running the kill command. Wait until the central exit fountain is active.
   Verify its portal at `(1,60,1)` and return to the Overworld.

   ```mcfunction
   /execute if block 1 60 1 minecraft:end_portal
   /execute in minecraft:overworld run tp @s 199 -50 290
   /gamemode creative
   ```

The seven fountain portal block entities are in the same chunk as the seven
prepared ones. After these steps, leave the world running for at least 10
seconds so that initial leaf updates drain and lighting settles. The following
checks can be run from the Overworld.

```mcfunction
/execute in minecraft:overworld run data get block 178 -56 264 Items
/execute in minecraft:overworld run data get block 210 -56 264 Items
/execute in minecraft:overworld if block 191 -57 221 minecraft:wall_torch[facing=west]
/execute in minecraft:the_end run data get block 5 84 10
```

### Freeze, queue scheduled ticks, and save

Enter these two commands in order, immediately before saving.

```mcfunction
/tick freeze
/function nbt_fixture:finalize
```

`/tick freeze` must be entered by the player with commands enabled. Vanilla
requires administrator permissions for this command, above the datapack
function permission level. The final function assumes the freeze succeeded.
It checks that setup markers are present, queues the five flowing-lava ticks in
the Nether, and places stone beneath the 40 leaves in the Overworld to queue
their block ticks.

After the final function confirms completion, select **Save and Quit to
Title** while frozen. Leave the world closed until the three region files have
been copied. Unfreezing, stepping ticks, or reopening the save before copying
can consume the pending ticks.

## Copy the vanilla regions

With the world closed, use **Edit → Open World Folder** to locate the files
below. Create the ignored staging directories and copy the full regions there.
Use the Overworld file for all modern Overworld cases.

| World | Path under the save | Path under the repository | Chunk coordinates to retain when migrating |
| --- | --- | --- | --- |
| `NBT Fixture 26.3` | `dimensions/minecraft/overworld/region/r.0.0.mca` | `manual/input/replacements/overworld/r.0.0.mca` | `(0,0)`, `(11,13)`, `(11,16)`, `(12,18)`, `(13,16)` |
| `NBT Fixture 26.3` | `dimensions/minecraft/the_nether/region/r.0.0.mca` | `manual/input/replacements/nether/r.0.0.mca` | `(7,1)`, `(8,9)` |
| `NBT Fixture 26.3` | `dimensions/minecraft/the_end/region/r.0.0.mca` | `manual/input/replacements/end/r.0.0.mca` | `(0,0)`, `(0,4)` |

The commands reproduce the input conditions. Saved timestamps and pending tick
timing can differ between runs. Record the hashes of the staged copies.

```powershell
Get-ChildItem -LiteralPath 'manual/input/replacements' -Filter '*.mca' -Recurse |
  Get-FileHash -Algorithm SHA256
```

## Verify the saved NBT before selecting fixtures

Open the copied regions in NBTExplorer or a separate region reader. Check
`Status=minecraft:full`, `DataVersion=5023`, and `xPos` and
`zPos` at each retained slot. Check the following details before replacing
the tracked fixtures.

| Region and chunk | What to check |
| --- | --- |
| Overworld `(0,0)` | Section `Y=-4` has a plain-string palette with bedrock, dirt, grass block, and air. Section `Y=-3` has a singleton air palette. Section `Y=6` is all air and plains, with `Y`, `block_states`, and `biomes`. |
| Overworld `(11,13)` | Section `Y=-4` has six mixed palette entries, including the empty-key string wrappers and `wall_torch[facing=west]` at `(191,-57,221)`. Check the four heightmaps, `isLightOn`, `SkyLight`, and `BlockLight`. Section `Y=-3` remains all air. |
| Overworld `(11,16)` | Section `Y=-4` has 26 mixed states and 5-bit indices. There is one chest at `(178,-56,264)` with materialized `Items`. The ordinary edit targets `(179,-60,259)` and `(177,-56,264)` remain air. |
| Overworld `(12,18)` | Section `Y=-4` has 35 mixed states and 6-bit indices. There are 40 `minecraft:oak_leaves` entries in `block_ticks`, with empty `block_entities` and `fluid_ticks` lists. The edit target `(195,-60,291)` remains air. |
| Overworld `(13,16)` | The chest at `(210,-56,264)` has three stone in slot 0 and two sticks in slot 9. |
| Nether `(7,1)` | Section `Y=5` has a 4-bit mixed palette with netherrack at `(120,83,24)`. There are five `minecraft:flowing_lava` entries in `fluid_ticks`. |
| Nether `(8,9)` | Section `Y=4` has `nether_wastes` and `crimson_forest` in the biome palette and a packed `biomes.data` array. |
| End `(0,0)` | Section `Y=5` has a 4-bit plain-string palette and end stone at `(8,83,8)`. The chunk has **14** End portal block entities, seven along X=5..11 at Y=84 and Z=10 and seven at the exit fountain. |
| End `(0,4)` | The lighting-only section contains signed byte `Y=-1` and a 2048-byte `SkyLight` array. The tracked fixture uses this section from the vanilla save. |

## Extract the modern fixtures

From the repository root, run the extractor after verifying the saved NBT.
`--check` compares the staged inputs with the fixtures currently on disk.

```powershell
python fixture-generation/extract_vanilla_fixtures.py
python fixture-generation/extract_vanilla_fixtures.py --check
```

The extractor reads the staged files and writes the three modern regions under
`src/test/resources/net/querz/mca/palettes/`. It copies each selected compressed
record and its timestamp unchanged, keeps its original header slot, recalculates
sector offsets, and zeroes unused headers and sector padding. The test suite
makes its own temporary variants and sparse views.

See the [fixture provenance and SHA-256 hashes](../src/test/resources/net/querz/mca/palettes/README.md)
for the exact source files and generated excerpts. A fresh save can match the
regression conditions while producing different hashes, for example because of
its timestamps.

## Reproduce the synthetic legacy fixture

The checked-in `src/test/resources/net/querz/mca/palettes/legacy/r.0.1.mca`
contains one synthetic chunk at `(0,32)`, in local slot `(0,0)`, with
`DataVersion=4790`. Its section at `Y=-4` uses 16 old-style compound entries
with `Name` and optional string-valued `Properties`, four-bit packed block
indices, and `minecraft:deepslate[axis=y]` at `(3,-63,512)`. Adding the test's
jungle log grows the palette to 17 entries and requires five-bit indices.

The generator writes the NBT tags, packed indices, ZLIB record, and MCA header
directly. It uses fixed timestamps, zero padding, and a fixed DEFLATE encoding
so the output is byte-for-byte reproducible. Run these commands from the
repository root to regenerate or check the fixture. The default Gradle tests
read the checked-in file directly.

```powershell
python fixture-generation/generate_legacy_fixture.py
python fixture-generation/generate_legacy_fixture.py --check
```
