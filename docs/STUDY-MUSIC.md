# Optional study music

## Direction

The owner requested free jazz, lo-fi and similar study music, then chose a larger
optional download instead of a small playlist bundled in the installer.

Keep the installer small. Offer the music from Settings, download only after a
user chooses to, and play the installed tracks offline. Retain personal music
imports and separate study-music playback from Pomodoro completion sounds.
Pomodoro sound choice, volume, preview and mute are tracked in #61.

## Verified source and licensing

Incompetech offers a free Creative Commons option with attribution:
https://incompetech.com/music/royalty-free/licenses/

Its public catalog supplies track metadata and CC BY 4.0 attribution notices:
https://incompetech.com/music/royalty-free/music.html
https://incompetech.com/music/royalty-free/pieces.json

CC BY 4.0 allows redistribution and adaptation, including commercial use, with
appropriate credit, a license link and an indication of changes. Preserve those
notices in the download and expose credits in Yoru. The app's Unlicense does not
replace the music's license.
https://creativecommons.org/licenses/by/4.0/

Candidate tracks checked against the publisher's catalog:

| Track | ISRC | Duration | Direction |
|---|---|---|---|
| Study And Relax | USUAN1900030 | 3:43 | Lo-fi jazz |
| Local Forecast - Slower | USUAN1300011 | 3:19 | Relaxed jazz |
| Night in Venice | USUAN1900056 | 3:38 | Vintage-style jazz |
| Backed Vibes (clean) | USUAN1100479 | 3:48 | Background jazz |
| Dream Catcher | USUAN1800019 | 6:17 | Lo-fi electronic |

These are candidates, not a shipped playlist. Final selection needs a listening
review and complete credits. FreePD.com is closed; do not depend on it as the
source of a new pack.

## Implementation remaining

- Prepare a downloadable pack with publisher attribution and license notices.
- Convert to a format the existing JDK player supports (WAV, AIFF or AU),
  documenting the conversion. MP3 is not currently supported by the app.
- Measure the download and installed size and display them before downloading.
- Provide a Settings entry, offline playback and an accessible credits view.
- Verify the archive, supported audio formats and import behavior before release.

No music is bundled or downloaded by the app yet. Audio files must stay out of
the source repository; publish an explicitly licensed optional release asset.
