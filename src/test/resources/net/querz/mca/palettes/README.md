# Region fixtures

The three modern files are excerpts from one fresh vanilla Minecraft Java
26.3 world with seed `1`, Superflat Classic Flat Overworld, and structure
generation disabled. The 26.3 fixture datapack prepared the chunks. Both
Overworld chests were opened, the Ender Dragon was killed by command, and
the scheduled ticks were queued while frozen before saving. The full source
paths are relative to the repository root.

| Fixture | Provenance | World chunk coordinates | DataVersion | Bytes |
| --- | --- | --- | --- | --- |
| `overworld/r.0.0.mca` | `manual/input/replacements/overworld/r.0.0.mca` | (0, 0), (11, 13), (11, 16), (13, 16), (12, 18) | 5023 | 28,672 |
| `nether/r.0.0.mca` | `manual/input/replacements/nether/r.0.0.mca` | (7, 1), (8, 9) | 5023 | 16,384 |
| `end/r.0.0.mca` | `manual/input/replacements/end/r.0.0.mca` | (0, 0), (0, 4) | 5023 | 16,384 |
| `legacy/r.0.1.mca` | Synthetic NBT and MCA bytes | (0, 32) | 4790 | 12,288 |

| Fixture | SHA-256 of full source | SHA-256 of fixture |
| --- | --- | --- |
| Overworld | `98b9e71ad6be3b42a37381e169a49ea3459fc551d7adab198eb98c9aa70bfc45` | `ce68bef7f495cf8d5137cc5ca5215c6198c385d0a200ad6a87dfff0e30d099bc` |
| Nether | `7aab8ec2b52fdaabca9c7eb2c32e3f5eb0cf93fa29e32efbbaa6af1e4133b111` | `7dde60cc5f226bdfefa4ee391d71fad5ada6a9c8444e8a98d764e550f1a0c9d7` |
| End | `e2aab2e7663a3c25cbdc6c346c7489a30f3c76feb88679a2d946c8bf55942118` | `82bb8be318a2b1bf57d1eb3d83b90bc9ef5f35e877a1c55eadd7a5786507d9f8` |
| Legacy | — | `8ef7a35514cc6984177c7972ed82f350dc60a74ce31ee6a158faf0b53426cd5b` |

The extractor copies the declared ZLIB records and original timestamps into
their existing header slots. It rebuilds sector offsets and writes zero
padding. The [generation guide](../../../../../../../fixture-generation/README.md)
includes world creation, commands, extraction, and legacy regeneration.

The Overworld fixture covers plain and mixed palettes, both chests, and
40 oak-leaf ticks. Its 4-, 5-, and 6-bit sections retain their original
indices. The Nether covers five flowing-lava ticks and packed biome data.
The End covers 14 portal block entities and a genuine lighting-only section
at `Y=-1`. The synthetic legacy fixture exercises the `Name`/`Properties`
encoding and growth from 16 to 17 states.

The default tests read these files and write to JUnit temporary directories.
They create two-record Overworld views where exact record counts matter.
Temporary NBT variants supply the loot-table tags, negative tick delays,
nonempty structure references, and block-light arrays used by the metadata
preservation regression. The default tests use the regular Gradle dependencies
and can run offline once those dependencies and the Gradle distribution are cached.
