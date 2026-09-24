# Yoru 1.0.20

## A page of habits on one screen (#52, #53)

- Each kind of habit is one list: daily habits on the left, time-since trackers
  on the right, a line each. Six daily habits and eight trackers now fit side by
  side at 1280×900, where two daily habits used to fill the column.
- A daily habit shows its name over the last seven days, which you can still
  click to correct, with its streak and its consistency over the last 30 days
  at the end of the line. The best run and the week so far are in the tooltip.
  Click the name for the full history; rename and delete are in ⋯.
- A time-since tracker shows when it began and how long it has run, to the
  minute ("12d 4h 33m", "4h 33m"), with Start again beside it. Editing the
  start, the history, rename and delete are in ⋯.
- At the larger text sizes a row's figures move onto the line below instead of
  squeezing the week into slivers.

## Ticking habits off from Tasks and Today (#54)

- The Up and Down keys move through today's habits, and Space ticks the one with
  focus.
- Each habit's day turns over at midnight in the habit's own time zone.

## Tasks (#67)

- Double-click an empty day in the task calendar to make a task due that day.
- Clicking a task in the calendar without dragging it no longer quietly plans it
  for the day it was already due.

## Today fits the window (#86)

- At 1280×900 the whole day is on screen without scrolling. Anki's one line
  now stands beside the page title rather than at the foot of the page, where
  it had been pushed below the window.

## Anki (#85)

- **Test connection** says which of its failures happened, in words that say
  what to do: open Anki and install its add-on, fix the key, open a profile,
  wait for Anki, or update the add-on.
- A test confirms the API key is kept only in the encrypted vault. It is not in
  this computer's preferences, in anything Yoru prints, or readable in the vault
  file.

## Text size (#31)

- The Data page's heat map, the task calendar and the Schedule's week grid now
  follow the text size setting. At 200% they no longer write month names over
  their own edge, cut off weekday names or print titles outside their blocks.
  Changing the size while the app is open re-measures them.
- The vault launcher uses the app's type and spacing scale.

No vault migration. Checks: the full isolated suite, including the new
`HabitChecklistTest` and `AnkiSettingsTest`, `TextFitTest` at every text size,
and the page previews in all five themes at both window sizes.
