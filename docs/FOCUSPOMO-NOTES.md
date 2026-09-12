# What to steal from FocusPomo — #6

**Provenance.** FocusPomo 5.2.1 (build 11739), walked live on a real install
on 2026-09-10, with a little existing data in it. Earlier revisions of this file were written from the app's shipped
string catalogue only, because desktop control had been declined; that
limitation is gone and everything below was seen on screen unless it says
otherwise. Where a claim still rests on strings rather than observation it is
marked **(strings only)**.

It is a Mac Catalyst port of an iPad app. That explains most of what follows:
disclosure rows instead of inline fields, wheel pickers, long-press gestures,
and a menu bar that carries nothing but stock Cocoa commands.

---

## 1. Structure — three screens, but not three pages

The View menu lists **Home (⌘1) / Timeline (⌘2) / Summary (⌘3)**, which is
misleading. **Home and Summary are one continuous scroll.** The timer is the top
of the page; swiping up pulls the Summary panel over it while the clock scrolls
away and dims. A "Go Up" chevron appears at the top-left of the summary section
to get back. Timeline is a genuinely separate screen.

I got this wrong at first by reading the menu and assuming three pages. Worth
recording because it is the single biggest structural idea in the app: **the
stats are not somewhere you navigate to, they are underneath the thing you were
already looking at.**

`File` has only Close / Close All. `Edit` is the stock Cocoa block. **There are
no app commands in the menu bar at all** — every action lives in the window.

## 2. The timer screen is almost empty

At rest it shows four things: the clock (`00:00`, very large), the tag name
below it with a `›` chevron, a `Start Focus` pill, and today's completed
pomodoros as tomato emoji in the bottom-left corner.

No navigation bar. No toolbar. No settings affordance. **All chrome is
hover-revealed** — the "New Tag" and "…" buttons do not exist in a screenshot of
the idle window and appear in the accessibility tree only once the pointer
enters. Yoru's pages are denser than this and probably should stay so, but the
principle that the timer screen carries nothing but the timer is a good one.

## 3. Per-tag focus duration — the idea worth taking

Tapping the tag name opens a picker that **blurs the whole timer behind it** and
shows the tags as pills: a colour dot, the name, and **that tag's own configured
duration** underneath.

For example: `Work 25:00`, `School 00:00`.

So **the timer mode is a property of the tag, not a global setting.** "Work" is
a 25-minute pomodoro; "School" is an open-ended stopwatch. Choosing what you are
about to do also chooses how it will be timed.

Yoru is stopwatch-only by design (`README`: "open-ended clock in/out") and the
collection economy assumes it. But if a countdown mode is ever added, this is the
shape to copy: attach it to the activity rather than to a global preference.

The overflow menu on that picker is **Edit Tag / Sort By ▸ / View Archived
Tags** — archiving is real and this is where it lives.

## 4. Timeline — closest thing to Yoru's ScheduleGrid

- **Day / Week toggle.** Day is one full-width column; Week is seven narrow
  ones. Yoru's grid is week-only.
- Header reads `‹ W37, Sep 6 - Sep 12 ›` — **the ISO week number** with
  prev/next chevrons, above a `Sun 6 … Sat 12` day strip. Yoru shows neither a
  week number nor a day strip.
- **Week starts Sunday**, confirmed live, matching
  the `Start Week On` setting (its control reads "Sunday").
- **A live now-line** — a coloured rule across the full grid width with the time
  in a pill in the left gutter. It read `06:42` while `date` said `06:42`.
  *(Yoru already has this: `ScheduleGrid` line ~338.)*
- Sessions are rounded blocks in the tag's colour. In Day view: name left,
  duration right. In Week view: name over duration, stacked.

### 4a. Adding a session

`+` top-right opens **Add New Session**: a centred card over a dimmed backdrop,
with four **disclosure rows** — `Tag`, `Start from`, `End at`, `Duration` — and
an `Add` pill.

**Duration is co-equal with start and end**, not derived and hidden. Yoru's
manual entry has start and end only. Editing any two of the three should settle
the third; that is the affordance worth copying, because "I studied for 90
minutes, some time this morning" is a more natural thing to know than two
precise clock times.

Defaults were Tag = currently selected, `06:00` → `06:25`, 25m — the top of the
current hour plus a fixed 25, not "the last 25 minutes".

Each row opens a **three-column wheel picker** (iOS `UIDatePicker` style):
`2026/9/10 | 6 | 00`. Note the **left column is the date**, so a session can be
moved to a different day from the same control that sets its time.

### 4b. Editing and deleting — an anti-pattern, not a pattern

**Long-press on a block is the only way in.** I tried, in order: single click,
double click, right-click, and every menu in the menu bar. All did nothing. A
long press opens **Edit Session** — the same four rows, plus a full-width dark
red `Delete` row and a `Done` pill. Delete then raises a proper confirm
("Are you sure you want to delete this session?", Cancel / Delete).

There is **no affordance whatsoever** for this. No hover state, no cursor
change, no context menu, no menu command, no hint text.

This is directly relevant to Yoru: **#32 was a report that a scheduled block could
not be deleted.** The app being copied has exactly the same failure.
Copy the *dialog* — four fields, destructive action separated and coloured,
confirm before deleting — and do **not** copy the way you reach it. Yoru's
`ScheduleGrid.editableAt()` (click, plus a visible cue) is the better answer.

## 5. Summary — the charts

Three columns: **Focus Trend**, **Pomodoro Details**, **All Data / More**. The
whole screen carries a **`BETA` badge** in a shipping paid app.

**Focus Trend**
- `Today's Focus 0h ▼100%` and `This Week 50m ▼16%` — **period-over-period
  deltas with a direction arrow**. Yoru had no comparison of any kind. *(Now
  partly taken: `Analytics.change`, on the Data page.)*
- A seven-slot strip labelled `S M T W T F S`. Days with time draw a **vertical
  bar stacked by tag colour**; empty days draw a small grey dot; today's dot is
  larger and its letter is bold.
- `Daily Avg 10m` printed at the left with a **dotted horizontal reference
  line** across the chart at that height.

**Pomodoro Details**
- `Today's Pomos 🍅 0 ▼2` and `Abandoned 🟡 0` **side by side, equally
  weighted.** Abandoned sessions are a first-class metric, not a footnote.
- Empty state is the words `No Focus Data yet`, not a chart of zeroes.

**All Data** — `Total Pomodoros 4`, `Total Focus 1h 50m`. Lifetime, no period.

**More** — disclosure rows: `Focus Distribution (Old Version)`, `Feedback`,
`Summary Roadmap`, `What's New in 5.0`. Their own stated roadmap is *"sub-tags,
pie charts, habit tracking"* — Yoru already has habit tracking.

### 5a. Focus Distribution

Behind the "Old Version" row, and the best single idea on the screen.

A **D / W / M / Y / All** period selector, then **ranked horizontal bars**:

```
[  78%  ] School  1h25m
[ 22% ]   Work      25m
```

One bar per tag, in the tag's own colour, sorted longest first, percentage
inside the bar, name and absolute time beside it. Plus `Total Days 2` and the
completed pomodoros drawn as literal tomatoes.

**One flaw to fix while copying it:** the bars are not proportional. A short bar
is widened to fit the percentage label printed inside it, so the 22% bar renders
at about a third of the 78% bar rather than a quarter. Yoru's `FocusBars` puts
the label outside the track so the bars can stay honest; `FocusMixTest` measures
the painted pixels to keep it that way.

## 6. Short sessions — where Yoru deliberately diverges

FocusPomo has a whole vocabulary for unfinished work: `Abandoned`,
`Show Abandoned Tomatoes`, `Discard Session`, `Give Up`, `End Early`, and the
same five-minute threshold Yoru uses (`Focus time is less than 5 minutes`)
**(strings only for the vocabulary; the `Abandoned` counter was seen live)**.

It **keeps** abandoned sessions as a visible, toggleable category.

**Yoru does the opposite on purpose.** The owner's instruction was: *"Anything
under 5 minutes (or users selected minimum time) does not need to be recorded at
all."* Since #34, sub-minimum sessions are not stored. This is a settled
decision, not an oversight — **do not "fix" it by reintroducing them.** The Data
page still offers to purge pre-#34 short sessions, and says so out loud rather
than dropping them silently.

## 7. What we already match

Recorded so nobody rebuilds it: manual sort / sort by time / sort by title
(#2's board), calendar view and an undated bucket (#5), add-record-manually and
session editing, a month heat map, tag colour pickers, week-start as a setting
(#26), and the now-line on the schedule grid.

## 8. What not to take

**The tone.** `Bravo!!!!`, `Great! You win your first tomato!`, `Come On! You
are almost there`, `Congrats on completing a Pomodoro set! ✨✨`, `Keep Going
💪🏻` **(strings only)**. Yoru's README opens with "A quiet place to show up".
The companion and the encounter economy already carry the reward weight.

**App blocking** (`App Blocker`, `App Blacklist`) — needs entitlements an
unsigned local build has no route to.

**Anything sync- or subscription-shaped.** A large share of that string
catalogue is iCloud sync, membership tiers, trials and refunds. Yoru is local
and account-free.

**Long-press as the only route to an action.** See §4b.

---

## Suggested order for what remains

1. ~~Focus distribution by activity~~ — **done**, `FocusBars` + `Analytics.distribution`.
2. ~~Period-over-period change~~ — **done**, `Analytics.change`, shown on Data.
3. Duration as a third editable field on Yoru's manual session entry (§4a).
4. Day/Week toggle on the schedule, and the ISO week number in its header (§4).
5. Tag-stacked bars and a daily-average line on the existing 14-day chart (§5).
6. Archive tags, then merge tags (§3) — a finished semester wants archiving,
   not deletion, and Yoru currently only offers delete.
7. Countdown mode and breaks, with duration attached to the activity (§3). Large,
   and a genuine product decision rather than a gap to be filled by default.

## Reproducing this

FocusPomo is at `/Applications/FocusPomo.app`, bundle id `com.MINE.PomodoroTimer`.
If the install holds real data, **add and delete only what you clean up after**,
and ask before changing anything. Background `app_*` control works for
navigation and reading; the long-press in §4b needs foreground control, because
`app_click` cannot hold a press. Sheets animate in, so a screenshot taken in the
same batch as the click that opens them will show the *previous* state — take a
second screenshot rather than concluding the click failed.
