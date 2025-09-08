***Note, you need to host [ModPackUpdater](https://github.com/Shiroiame-Kusu/ModPackUpdater) yourself first if you want to use this mod***
# ModPackUpdater (Client Mod)

A multi-loader Minecraft client mod that syncs a local instance with a ModPackUpdater server. It discovers a pack, fetches a manifest, computes a local diff, and applies changes (add/update/delete) for mods, configs, resource packs, and other files.

For protocol and client-side algorithm details, see CLIENT.md.

## Status
- Version: 0.1.0-Alpha.1
- Minecraft: 1.21
- Loaders: Fabric, Forge, NeoForge
- Java: 21
- License: GPL-3.0

## Features
- Talks to a ModPackUpdater server (health, pack summary, manifest, file fetch)
- Client-side diff: compares server manifest vs. local files by SHA-256
- Atomic downloads, verification, and safe deletes (see CLIENT.md)
- Multi-loader builds from one codebase (common + loader subprojects)

## Project layout
- common/ — shared code and resources
- fabric/ — Fabric loader entrypoints and mappings
- forge/ — Forge setup (FG 6)
- neoforge/ — NeoForge setup (ModDevGradle)

## Build
Requirements: JDK 21, Git, internet access.

- Build all loaders and collect final jars into output/:
  - Linux/macOS: `./gradlew build`
  - Windows: `gradlew build`
- Build specific loaders:
  - `./gradlew :fabric:build`
  - `./gradlew :forge:build`
  - `./gradlew :neoforge:build`

Artifacts:
- The root build collects non-sources/non-javadoc jars from each loader into output/ automatically (see root build.gradle).

## Run in dev
Each loader exposes run configs via Gradle. Typical tasks:
- Fabric: `:fabric:runClient`, `:fabric:runServer`
- Forge: `:forge:runClient`, `:forge:runServer`
- NeoForge: `:neoforge:runClient`, `:neoforge:runServer`

Dev run directories live under runs/ (client, server). Use your IDE Gradle panel to execute or debug.

## Configure (client behavior)
The mod synchronizes files for a chosen pack from a server. Key concepts:
- baseUrl: server root, e.g., http://localhost:5000
- packId: target pack ID
- installRoot: local game directory to sync

Protocol, schema, and detailed guidance are documented in CLIENT.md.

## Troubleshooting
- Network proxy: The repository’s gradle.properties contains example proxy settings (localhost:7897). Remove or override them if not applicable to your environment:
  - `./gradlew -Dhttp.proxyHost= -Dhttps.proxyHost=`
- Clean builds: `./gradlew clean build`

## Contributing
Issues and PRs are welcome. Please keep changes loader-agnostic in common/ where possible.

## License
GPL-3.0. See LICENSE.txt.

## Credits
Author: Shiroame_Kusu

