# World Depth Optimisation

A Fabric mod for Minecraft 26.3 that makes worlds made tall by other mods or datapacks cheaper. Bedrock rides up under high ground and
everything below it is removed, so a mountain at y=1000 stands on a few hundred blocks of rock instead of more than a thousand.

It changes no world settings, including height, and does nothing in a world of vanilla height. It only supports mods and datapacks that
make worlds taller, and post-processes vanilla's noise generator so it works with them rather than replacing world generation.

## What it does

- **Floating floor:** in a world taller than 384 blocks, bedrock follows a smoothed version of the surface, a set depth below it, once
  the surface is high enough to lift it.
- **Structures:** buried structures are raised to sit just above the lowest bedrock under them, and bedrock wins where it passes
  through one. Strongholds clear the highest bedrock under them instead, to keep the portal room whole.

The floor is approximate: because it is smoothed, rock under a sharp peak can run deeper than the setting.

## Files

Each release has two jars. Install one, not both.

| File | Contents |
|---|---|
| `worlddepthoptimisation-<version>.jar` | The mod |
| `worlddepthoptimisation-<version>-benchmark.jar` | For testing only. The mod, plus the benchmark command, measurement hooks and test world types. It makes the overworld 2,048 blocks tall (y=-64 to 1983) in every world, because the test worlds need it |

Both need [Fabric API](https://modrinth.com/mod/fabric-api) and Java 25.

## Configuration

`config/worlddepthoptimisation.properties` is created on first launch:

```properties
world_depth=352
```

It sets how many blocks below the surface the floor sits, and must be a multiple of 16 from 16 to 4064. A value that breaks those rules
is corrected, with a warning in the log.

**Low depths may create structure generation and other unforeseen errors. High depths may create performance issues.**

Restart after changing it. Only newly generated chunks use the new value, so changing it in an existing world can leave a step in the
bedrock where new chunks meet old ones.

`/worlddepth` shows the current state, and `/worlddepth on` or `/worlddepth off` switches the floor for new chunks until the game closes.

## Tall terrain

The mod does not make anything taller. A tall world needs another mod or datapack to raise both the dimension height and the terrain
generator's limits. Note that a tall dimension costs memory by itself, even on terrain that never uses the height (see below).

## Benchmarks

The benchmark jar adds `/fbench run [label]`, which settles, sits still, flies through new chunks, saves, flies back over the saved
chunks, and then runs scripted block edits. Results go to `benchmarks/` in the game folder. `bench.ps1` runs this unattended on fresh
worlds, on Windows, and needs the JDK path at the top of the script to match your machine.

The figures below are from single runs on one PC (Ryzen 9 9900X, RX 6750 XT, 31 GB, 6 GB Java heap, render distance 16), on flat test
worlds, so treat small differences as noise.

| | 384-tall dimension, surface y=300 | 2,048-tall, surface y=300 | 2,048-tall, surface y=1000 on a raised floor | 2,048-tall, water from y=-64 to y=1936 |
|---|---|---|---|---|
| Peak process memory | 3,363 MB | 5,691 MB | 4,645 MB | 7,291 MB |
| Time per chunk while flying | 32.7 ms | 38.5 ms | 39.3 ms | 74.3 ms |
| Worst server tick while flying | 10.8 ms | 27.8 ms | 27.1 ms | 137.1 ms |
| Worst tick when teleporting back | 30.7 ms | 167.3 ms | 174.4 ms | 144.8 ms |
| Compressed chunk packet | 0.24 KB | 0.24 KB | 0.26 KB | 0.67 KB |

A raised floor costs about the same as low terrain. The cost comes from the dimension being tall, and from how many blocks a chunk
actually holds. These tests do not cover real network connections, and they have not compared the floor on and off under tall terrain.

## Building

```
./gradlew build
```

Jars land in `build/libs`.

## Licence

All rights reserved. The source is published to read and learn from, and is not licensed for reuse or redistribution.
