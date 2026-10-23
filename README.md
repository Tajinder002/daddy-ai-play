# AutoPlay AI (Practice) — Minecraft 1.21.11 / Fabric

A small adaptive combat-practice prototype. This is a fresh, minimal project intended to avoid the broken intermediary-name source and mismatched build environment in earlier attempts.

## Build

- Minecraft: 1.21.11
- Fabric Loader: 0.18.4
- Fabric API: 0.141.1+1.21.11
- Yarn: 1.21.11+build.4
- Java: 21
- Loom: 1.14-SNAPSHOT

On GitHub, push the repository and open **Actions**. The `Build AutoPlay AI` workflow compiles the project and uploads `AutoPlay-AI-JAR` as an artifact if the build succeeds.

## Commands

- `/autoplay on` — enable the prototype
- `/autoplay off` — disable it
- `/autoplay status` — show current state and in-memory learning count
- `/autoplay allowlist` — print the allowlist file location

The prototype aims at and attacks the nearest other player within range. Its learning is deliberately simple: it tracks whether a target's health decreased after an attack and slightly adjusts engagement range based on its observed success rate. This is not a trained neural network or a complete competitive PvP agent.

## Multiplayer safety

Automation is disabled on multiplayer servers unless the exact server address is listed in `.minecraft/config/autoplay-allowed-servers.txt`. Add only private servers where you own the server or have explicit permission to use automation. The allowlist is empty by default. Server rules and anti-cheat policies still apply.

## Upload to a new GitHub repository

1. Download and extract this ZIP.
2. Open the extracted `autoplay-ai-fixed` folder.
3. Upload its *contents* to the root of your new repository (so `build.gradle`, `gradle.properties`, `src/`, and `.github/` are at the root).
4. Commit the files, then open **Actions** and check the newest build.

If the workflow fails, open the failed run and inspect the `Build mod` step. This package has not been compiled in this environment because a Gradle installation/dependency download was not available here; do not treat the ZIP as a verified successful build until GitHub Actions passes.
