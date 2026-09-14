# Illustrated companion

## Unfinished artwork checkpoint

Three additional original adult illustrations were generated with the built-in
image tool and saved under the bundled artwork directory: `rainbird.png`
(teal fashion in a neon greenhouse), `solstice.png` (ivory evening dress in an
observatory), and `vermilion.png` (black dress and crimson coat in a music lounge).
They contain no game assets. The prompts specified clearly adult characters,
non-explicit fashion illustration, cinematic settings, and no existing characters.
These files are not yet registered in the portrait catalog or picker.

Work stopped at the owner's request. The planned Daybreak illustration was
not generated because image generation reached its usage limit. Remaining work:
catalog registration, visual picker, varied page compositions, measured artwork
package-budget review (the existing 8 MB guard predates these additional files),
full tests and visual verification, then release. This is not a release candidate.

`nightfall.png` is an original AI-generated illustration made for Yoru. The
character is an adult, and the image contains no game assets. It ships with
the dedicated Waifu theme only. Amberglow is a second original adult companion in a burgundy
evening dress, generated for Yoru. Other themes preserve the selected id but
do not display companion art. Pixel portraits are no longer bundled or offered
in the picker. Their saved IDs resolve to Nightfall for older vaults, without
rewriting vault data.

The image is decoded once and cached. The UI scales and crops it to the card,
keeping its face near the top, and draws a readable gradient behind the caption.
Rotation changes once a minute, also responds to click or Space, and stops when
the panel leaves the window. Single portraits do not start a rotation timer.
