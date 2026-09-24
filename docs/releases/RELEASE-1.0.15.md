# Yoru 1.0.15 — Anki study time counts

Time you spend studying in Anki is now tracked time. While Anki is connected on
**Today**, every finished Anki sitting is added as a session, so it reaches
your totals, the heat map, the daily goal, your streak and the study
encounters, just like time on Yoru's own clock.

## How it counts

- **Anki's own figure.** A sitting lasts as long as the answer times Anki
  logged, which is the same "studied in N minutes" Anki reports. Each answer
  is already capped by the deck's maximum answer time, so idle time on one card
  does not inflate the total.
- **Sittings.** Answers less than ten minutes apart are one sitting. A sitting
  is added once nothing has been answered for ten minutes. Until then the card
  shows it in Anki's figure for today but not yet in your tracked time.
- **Where it goes.** Sittings go under an activity called **Anki**, made the
  first time it is needed. Rename that activity, or move a sitting to another
  activity, and later sittings follow the latest one.
- **Catching up.** Connecting reads the last seven days, so Anki time from days
  Yoru was closed (or reviews synced from your phone) is added too. After that,
  each minute's refresh reads the last day.
- **Never twice.** Refreshing again, reconnecting and restarting all leave a
  recorded sitting alone, even one you have edited. A sitting that overlaps time
  you clocked in Yoru is left out whole, because that time already counts, and
  so is a sitting shorter than your minimum session.

The Anki card shows both figures side by side: minutes studied in Anki today,
and how much of that is in your tracked time. It also shows what the last
refresh added.

## What reaches the vault

Only sessions: a start, an end and the activity. No card content, answers,
deck names or API key are stored. Anki is still only read, never changed. The
vault format is unchanged (still schema 13). A sitting is an ordinary session
whose id marks where it came from, so older versions of Yoru open the vault
normally. Switching to another vault disconnects Anki, and you connect again to
add Anki time to that vault.

Because Anki time is now ordinary tracked time, it earns study encounters at
the usual rate of one per thirty minutes.

## Notes

- Deleting an Anki sitting from the last seven days does not stick while Anki
  is connected: the next refresh reads the same answers and adds it again. Edit
  it instead; an edited sitting is left as you made it.
- Answers that sync late from another device and run into a sitting that is
  already recorded are not added. Yoru prefers counting a few seconds short to
  counting any time twice.

## Validation

`./test.sh` passes in full. New coverage:

- `AnkiTimeTest`: 47 checks covering sittings and Anki's own lengths,
  finished-only recording, no duplicates across refreshes, reaches, edits,
  moves and late answers, clocked and running sessions, the minimum session,
  failed writes, the encrypted vault and export.
- `AnkiConnectTest`: review logs read in batches, the day reach, reschedules
  left out, and malformed logs refused.
- `AnkiCardTest`: the card adds sittings to the tracker, catches up a week
  first and then reads a day, and never adds anything twice.

Each rule was also checked by breaking it and confirming a test fails. Yoru's
client and tracker then ran against the upstream AnkiConnect add-on's own
request handler, backed by Anki 25.09's backend and a throwaway collection of
invented cards answered through the real scheduler. Result: 130 answers read,
the reschedule left out, the finished 16-minute sitting added exactly once, the
unfinished one held back, and a wrong API key refused. No personal Anki profile
was opened.
