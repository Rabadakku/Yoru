# Yoru 1.0.17

## Anki stays available while you work (#51)

- Open Yoru while Anki is closed and see the saved review counts immediately.
- Keep working on Tasks, Pages or another tab: automatic retries continue while
  the vault is open. Reopening Anki updates the summary without pressing Connect.
- Older summaries show their calendar date as well as their time.
- A failed vault save preserves the last saved summary and reports that the
  latest counts could not be saved.
- Switching off Anki or changing vaults prevents stale results from being applied.

The default retry interval is one minute; a custom interval in Settings is
respected. Setup stays in Settings and the 30-day chart stays on Data. Stored
Anki summaries contain only the profile name, counts and fetch time, never card
content or answers.

Validation includes an encrypted close/reopen fixture, an offline fake source,
automatic timer-driven recovery after leaving Today, disabled-integration checks,
cache-write failure handling, and the full isolated project suite.
