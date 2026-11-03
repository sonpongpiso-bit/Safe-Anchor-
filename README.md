# Safe Anchor

Client-side Fabric mod for **Minecraft Java Edition 26.1.2** (Java 25).

Press one key while looking at a block and Safe Anchor runs this sequence:

1. Places a **Respawn Anchor** at the target (or reuses the anchor you are looking at).
2. **Charges** it with Glowstone.
3. Places a **Glowstone on the ground behind the anchor** (the side away from you).
   If that spot is blocked/invalid (not replaceable, no solid ground, entity in the way,
   out of reach, interactive block underneath, no Glowstone left) the step is **skipped
   automatically** and the sequence goes straight to step 4.
4. **Detonates** the anchor using the configured hotbar slot, then restores your previously selected slot.

> Minecraft calls the block "Respawn Anchor"; there is no "Ender Anchor".
> Anchors only explode outside the Nether (Overworld / End).

All actions use the regular client interaction path (`MultiPlayerGameMode#useItemOn`,
which sends the standard use-item-on and slot-change packets). The player never
sneaks and no custom/fabricated packets are sent.

## Controls & settings

| Setting | Default | Notes |
|---|---|---|
| Activation key | `R` | Click the button, press any key. `Delete` unbinds. |
| Menu key | `INSERT` | Opens the settings screen. `ESC` closes it (or cancels key capture). |
| Detonation hotbar slot | 1 | 1-9. Must NOT hold Glowstone (it would recharge instead of detonate). |
| Tick speed | 2 | 1-5 ticks between steps. |

Settings are saved to `config/safeanchor.json`.

Hotbar requirements: Respawn Anchor(s) (unless aiming at an existing one), Glowstone, and
a non-Glowstone item (or empty slot) in the detonation slot.

## Building

Requirements: **JDK 25** and **Gradle 9.x**.

```bash
gradle build     # or: gradle wrapper --gradle-version 9.4.0  then  ./gradlew build
```

The jar is written to `build/libs/safe-anchor-<version>.jar` (ignore the `-sources` jar).
Put it in `mods/` together with **Fabric API** for 26.1.2 and Fabric Loader >= 0.18.
Dev client: `gradle runClient`.

### GitHub Actions

`.github/workflows/build.yml` builds on every push/PR with JDK 25 + Gradle 9.4.0 and uploads
the jar as the `safe-anchor-jar` artifact (Actions tab -> run -> Artifacts).

If a version in `gradle.properties` is rejected, use current values from <https://fabricmc.net/develop/>.

## Multiplayer note

The mod only performs ordinary player actions, but many servers prohibit automation/macros.
Check the rules of any server before using it.
