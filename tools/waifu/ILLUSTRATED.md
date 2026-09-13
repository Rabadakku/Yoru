# Illustrated companion

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
