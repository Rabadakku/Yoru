# Yoru 1.0.21

## Lists (#56)

- The Tasks page has a rail of places: All tasks, the Inbox, Repeating, Daily
  habits, and your own lists (Chores, Personal, School…), each with its colour
  and its count of open tasks. It stands beside the board when there is room and
  wraps above it when there is not.
- Every task is in one list, or in the Inbox. A new task goes into the list on
  screen. Move a task with **Move to** in its ⋯ menu, or drag its row onto a
  list.
- Each list keeps its own view, sort, month and search. Reordering inside a list
  leaves every other list where it was.
- Right-click a list to rename, recolour, move or delete it. Deleting asks
  whether its tasks go to the Inbox or with it, and keeps a backup first. A tag
  can be turned into a list from the Tags editor.

## Several tags a task (#66)

- Type in the Tags field to find a tag and press Enter, or type a new name and
  choose **Create**. The new tag is saved with the task. Backspace removes the
  last one.
- The board shows every tag it has room for, then "+2". Click a row's tags to
  change them without opening the task.

## Repeating tasks (#57)

- Repeat every day, every weekday, every week or two, every month or year, or
  set your own rule: every N days, weeks, months or years, on chosen weekdays,
  on a day of the month or the nth (or last) weekday. The next date can come
  from the schedule or from the day you finish, and a rule can end on a date or
  after a number of times.
- Finishing a repeating task records it and moves it to its next date, so it
  stays one task with a history. **Skip this one** and **Stop repeating** are in
  its menu. Repeating tasks are marked ↻, and the calendar shows the dates they
  will come back on, dashed. Double-click a task in the calendar to open it.

## Pomodoro (#61)

- Today's focus card switches between the open-ended timer and a pomodoro: 25
  minutes of work, 5 of break, and 15 after every fourth, all set in Settings.
  Start, pause, skip and +5 minutes, with an optional auto-start.
- Work is recorded as time under the activity you choose; breaks are not. A
  count of today's pomodoros is on Today.
- A gentle sound marks the end of work and a different one the end of a break,
  played once, in one of three voices, at its own volume, with a preview and a
  mute in Settings. With the sound off, Today still says so and the Dock asks
  for your attention. The study music stops during breaks.
- An interval that ends while your computer sleeps is recorded to its proper
  end, and the next one waits for you rather than running through the night.

## Vault

This version moves the vault to schema 20 and the export to format 9, one step
for each of the above: a task's tags, its list, and how it repeats. A vault
from 1.0.20 or earlier opens with each task's tag intact, every task in the
Inbox and nothing repeating; the first save keeps a copy of the older file
beside it. Older exports still import. Pomodoro settings are kept on this
computer, like the study music's volume.

Checks: the full isolated suite, with new tests for lists, repeat dates (month
ends, leap years, week starts, a completion inside a clock change), the
pomodoro's timing through sleep and restart, its sounds, and both new areas in
every theme at 100% and 200% text.
