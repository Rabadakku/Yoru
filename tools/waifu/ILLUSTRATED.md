# Illustrated companions

Every portrait here is an original AI-generated illustration made for Yoru.
Each character is an adult, the art is non-explicit fashion illustration, and
no image contains game assets or an existing character.

- `nightfall.png`: the theme's default portrait.
- `amberglow.png`: a burgundy evening dress.
- `rainbird.png`: teal fashion in a rain-lit greenhouse.
- `solstice.png`: an ivory evening dress in an observatory.
- `vermilion.png`: a black dress and crimson coat in a music lounge.

They ship with the dedicated Waifu theme only. Moonlight keeps its violet
palette without portraits, and the other themes preserve the selected id but
do not display companion art. Pixel portraits are no longer bundled or offered
in the picker. Their saved IDs resolve to Nightfall for older vaults, without
rewriting vault data.

Each portrait is stored at 768 × 1152. That is larger than any card draws it,
and keeps all five inside the 8 MB portrait budget `DistributionTest` holds
the jar to; the generated originals were 1024 × 1536 and about 2.5 MB each.

Settings shows the choices as thumbnails: the theme default, each portrait,
and Rotate. On Today the chosen portrait fills the companion card. On the
theme's other pages a strip pairs it with a second portrait picked by the page,
so each page has a composition of its own and keeps it from visit to visit.

The image is decoded once and cached. The UI scales and crops it to the card,
keeping its face near the top, and draws a readable gradient behind the caption.
Rotation changes once a minute, also responds to click or Space, and stops when
the panel leaves the window. Single portraits do not start a rotation timer.
