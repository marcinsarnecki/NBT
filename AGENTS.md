# AGENTS.md — Minecraft MCA Library

## Project goals

Modernize an existing Java NBT/MCA library to correctly read, modify, and write Minecraft 26.3 region files.

Primary operations:
- Read and write `.mca` files
- Read individual block states
- Modify individual block states
- Preserve unrelated world data

Keep the public API simple and focused.

## Engineering rules

- Inspect existing code before proposing modifications.
- Do not rewrite the library from scratch.
- Preserve existing functionality unless there is evidence it is incorrect.
- Prefer minimal, incremental changes.
- Do not introduce unnecessary abstractions, frameworks, or dependencies.
- Do not modify unrelated files.
- Explain format assumptions and compatibility decisions.
- Distinguish confirmed behavior from hypotheses.
- Do not assume the current implementation is correct merely because it compiles or passes round-trip tests.

## Testing

- Write regression tests before fixing confirmed bugs when practical.
- Use real Minecraft-generated MCA fixtures.
- Test block states with properties, especially `minecraft:jungle_log[axis=y]`.
- Verify all unaffected blocks remain unchanged after modifications.
- Test palette growth, cleanup, and bitpacking boundaries.
- Validate serialization against Minecraft-generated data independently of the library's own decoder.
- Never modify original test worlds or fixture files.

## Workflow

1. Audit the repository.
2. Document findings and risks.
3. Establish baseline tests.
4. Implement Minecraft 26.3 compatibility.
5. Refactor only after behavior is understood and covered by tests.

For the initial audit, do not modify any files without explicit permission.