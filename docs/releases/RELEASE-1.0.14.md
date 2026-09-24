# Yoru 1.0.14 — Anki in the tracker

Today now shows your Anki review activity: today's review count, seven dates of
review history and the active Anki profile. Refresh manually or leave Today
open for automatic updates every minute.

## Connect Anki

1. In Anki, choose **Tools → Add-ons → Get Add-ons**, enter **2055492159**,
   then restart Anki.
2. Keep Anki open on the profile you want to track.
3. In Yoru, scroll down Today to **Anki reviews**, enter an API key only if you
   configured one in AnkiConnect, and select **Connect Anki**.

The integration uses AnkiConnect's default local port, 8765. It reports offline,
authentication and slow-response problems, and preserves the previous snapshot
with its timestamp if a refresh fails. Switching profiles during a refresh is
rejected to prevent mixed totals. Disconnect cancels pending work and clears
the snapshot and key. Closing the vault clears them too; reconnect after reopening.

Review totals include repeated reviews of the same card. Today's count follows
Anki's configured day boundary; the history shows the dates Anki returns, which
can differ from the computer's calendar date around midnight.

This is read-only. Yoru does not change Anki, copy card content into the vault,
or convert review counts into study time or game rewards. No vault schema
changes; existing study history and game saves are preserved. Custom ports,
remote connections and persistent offline Anki history are outside this release.

Validation includes the full isolated test suite, theme renders, long-text
layout checks and regression tests for authentication, malformed/oversized
responses, timeouts, profile switches and disconnect races. The Java client also
passed against the official AnkiConnect HTTP server with Anki 25.09.4 and a new
synthetic collection. The release workflow checks the bundled Java 22 runtime
before packaging. Full native gameplay has not been revalidated for this release.
