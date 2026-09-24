# Shared Yoru workspace

## Product direction — September 10, 2026

Read [docs/PRODUCT-GOALS.md](docs/PRODUCT-GOALS.md) before implementation.
The owner wants the entire supported game playable **inside Yoru**, entered through
a **Game** tab, with battles and progression matching the game. Study replaces
encounter/training grinding, and the earned Pokémon must be playable in that
campaign. Separate-emulator launches, timed play credits and isolated practice
gyms do not satisfy this goal. Preserve existing study data and collections.

This checkout is the local collaboration folder for the AIs working on Yoru.
The supplied Emerald Hoenn + National Dex files are under `game-files/` and are
ignored by Git. Do not commit the ROM, vaults, keys, class files or API keys.

Keep exact ROM hashes and machine-specific game paths in local-only notes.
The supported game variant is documented in `docs/PRODUCT-GOALS.md`.

Use a feature branch for changes, run `./test.sh`, and push to the GitHub
repository when network access is available.

## Artwork repair — September 9, 2026

The local `art/` folder contains 386 normal sprites, 386 shiny sprites and seven
overworld sheets restored from the earlier personal build. It is gitignored.
The jar discovers it beside itself or above `build/`; macOS bundles also search
beside the app. Imported user artwork takes precedence. Collection uses the
same persistent import as Settings, and Settings counts renderable artwork.

ROM import currently validates and stores the supplied ROM only. It does not
extract PNG artwork. Do not describe ROM validation as working sprite extraction.
GitHub release jars must not bundle the personal artwork or game files.

Verification: `./test.sh`, then `java -Djava.awt.headless=true -cp
build/yoru.jar:build/classes dev.yoru.ui.PackagedArtworkTest --check-local-pack`
(the latter requires the personal art folder and `build/artwork-preview/`).

## Route polish — September 9, 2026

TrainerScene uses stable world spacing, supplied grass/shrub/rock sheets, a wider
trail, a centered native gait without extra bobbing, and local-clock lighting.
Rare legendary cameos reuse local numbered species sprites; these are cosmetic
gliding images, not extracted overworld flight animations. Optional trainer, cyclist and Team Rocket
cameos are now implemented through `RouteCameos`; see the README for local
strip filenames and dimensions. They are cosmetic. The practice battle prototype
was removed on 2026-09-11; battles happen in the real game in the Game tab. EncounterScene provides a 1.2-second reveal
with a reduced-motion bypass; it does not change the deterministic encounter or
reward accounting. Its timer starts/stops with the component lifecycle.

Run `dev.yoru.ui.RoutePreview` with `build/yoru.jar:build/classes` after tests to
render day/night/flyby states, gait frames, an animated GIF and encounter stages
under ignored `build/route-preview/`. Never commit those personal-art previews.
