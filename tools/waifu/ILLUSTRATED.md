# Illustrated companion

`nightfall.png` is an original AI-generated illustration made for Yoru. The
character is an adult, and the image contains no game assets. It ships with
Moonlight and can also be selected in other themes. Existing pixel portrait IDs
remain supported for older vaults.

The image is decoded once and cached. The UI scales and crops it to the card,
keeping its face near the top, and draws a readable gradient behind the caption.
Rotation changes once a minute, also responds to click or Space, and stops when
the panel leaves the window. Single portraits do not start a rotation timer.
