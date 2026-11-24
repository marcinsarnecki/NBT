# Manual Minecraft validation

`test` uses tracked fixtures and temporary directories. Run `vanillaTest`
explicitly to generate disposable regions for Minecraft Java Edition 26.3.

## Inputs and outputs

Prepare a 26.3 Superflat Overworld using the [fixture-generation guide](../fixture-generation/README.md).
The exporters read its full region at `manual/input/replacements/overworld/r.0.0.mca`.
They expect all 1,024 chunks at DataVersion 5023. If the region is incomplete,
visit its four quadrants in the disposable world and let the terrain generate
before freezing ticks and copying the region. The default suite uses the
small tracked excerpt.

Each invocation writes to a new `manual/output/<scenario>/run-*/` directory.
Both manual data directories are ignored by Git. Existing `input/`,
`output/`, and prior run directories can remain as local archives.

```powershell
.\gradlew.bat vanillaTest
.\gradlew.bat vanillaTest --tests '*VanillaRegionExportsTest.exportsPlainPaletteRegion'
```

On Linux or macOS use `./gradlew`. To run a different scenario, replace the
method name in the filter.

| Method | Required source data and export |
| --- | --- |
| `exportsPlainPaletteRegion` | Chunk (0, 0) needs a plain-string palette and air at (3–5, −60, 3). `first/` contains the first log. Use `reopened/` to check all three log orientations. |
| `exportsMixedPaletteRegion` | Chunk (11, 13) needs the mixed palette created by the datapack and air at the three edit positions. The export places a jungle log at (181, −60, 211), a wall torch at (180, −60, 211), and a diamond block at (183, −60, 211). |
| `exportsMissingSectionRegion` | Chunk (0, 0) needs an air-only section at Y=6, with plains biomes and no lighting arrays. The test removes that section from a scratch copy, then writes the diamond block at (8, 100, 8) into the region under `output/`. |
| `preparesHeightmapComparisonRegions` | Chunks (6–8, 6–8) need their generated heightmaps and air at Y=−40. Compare the checkerboard on fresh in-game maps in `A-stale/` and `B-rebuild/`. |

Install an export only in the matching dimension's `region/` directory of a
disposable copy of the source world while Minecraft is closed. Keep the
`r.0.0.mca` basename. After testing, save and close Minecraft before inspecting
the resulting region.
