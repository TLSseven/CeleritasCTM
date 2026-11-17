# CeleritasCTM

Connected Textures (CTM) and natural-texture compatibility mod for **Minecraft 1.12.2 + Cleanroom Loader + Celeritas/Actinium**, implementing part of OptiFine's texture features.

> **This mod is AI-written. Maintenance is not guaranteed.**
>
> **Compatibility was tested against the Conquest Reforged 1.12.2 connected-texture resource pack. Compatibility with other resource packs is not guaranteed.**

---

## Features

### 1. OptiFine CTM Compatibility (connected / random textures)

Loads OptiFine-format `.properties` rules from resource packs and applies them per-quad under the Celeritas render pipeline.

### 2. Better Grass

Grass block sides extend the grass top texture along connected diagonal-below neighbors. Disable to restore the vanilla double-layer sides. Configurable.

### 3. Better Snow

Blocks under a snow layer show the snow texture on their sides (including the grass snowed side). Configurable.

### 4. Connected Glass

Vanilla glass and stained glass connect seamlessly when adjacent. Configurable.

### 5. BetterFoliage Compatibility

Lets the Better Foliage mod work under the Celeritas render pipeline (extra leaves, short grass, etc.). Whether BetterFoliage takes over grass rendering is configurable.

### 6. Built-in Config Menu

A built-in config menu integrated into the Celeritas **Video Settings** screen.

---

## Requirements

| Dependency | Version |
|------------|---------|
| Minecraft | 1.12.2 |
| Cleanroom Loader | 0.6.13-alpha |
| Actinium | alpha-0.0.13 |

---

## Confirmed Compatible Mods

- ConquestReforged 3.0.2
- BetterFoliage 2.3.3 (requires Forgelin-Continuous)
- Decocraft 2.6.3.7
- Aperture 1.8.2
- Blockbuster 2.7.1
- Chameleon 1.2.2
- Emoticons 1.1.2
- Metamorph 1.4
- Minema 3.7.1

---

## Compatibility Notes

- **Compatibility was tested against the Conquest Reforged 1.12.2 connected-texture resource pack. Compatibility with other resource packs is not guaranteed.**
- CTM processing only applies to vanilla/original-model blocks; custom mod models (e.g. Chisel & Bits) are skipped to avoid texture corruption.
- Resource packs should use standard OptiFine `.properties` rules. If a rule does not apply, check the rule file path and syntax.
