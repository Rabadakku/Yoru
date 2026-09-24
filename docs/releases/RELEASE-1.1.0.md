# Yoru 1.1.0

## Use Yoru with Claude (#47)

- Claude Desktop, Claude Code and other apps that support the Model Context
  Protocol can now use Yoru as a tool. Ask "What's due this week, and how much
  did I study?", "Plan tomorrow around my classes", or "Turn these lecture notes
  into a page and tasks".
- **No API key.** The assistant runs on your own account with its app. Yoru
  calls no AI service and holds no key.
- **Off until you switch it on**, in **Settings → Integrations · AI
  assistants**. Reading comes first. **Let assistants make changes** is a second
  switch: with it on, an assistant can add and edit tasks, pages, plans, time
  and habit check-ins. No assistant can delete anything. Yoru backs up the vault
  before an assistant's first change, redraws the page you are on, and lists
  what changed in Settings.
- **Setup takes one click.** **Add Yoru to Claude Desktop** adds Yoru to Claude
  Desktop's settings and keeps everything else in them. Claude Code gets a
  command to copy.
- **What gets sent, and where.** The assistant reaches the Yoru you have open
  through a socket in a folder only your account can open. Nothing listens on
  the network. Whatever the assistant reads becomes part of your conversation
  with it, and its app sends that to its provider. [SECURITY.md](../../SECURITY.md)
  covers this in full.

## Tasks as a database (#68)

- Every task has a **priority**, from none to urgent.
- You can add **statuses of your own** inside To do, Doing and Done, such as
  "Waiting".
- You can add **properties of your own**: text, number, select, multi-select,
  date, checkbox, link, and created and edited times. Manage them from
  **Properties** on the Tasks page. A property can belong to one list or to
  every task.
- Notion imports map their columns to properties.

## Quick add and the command palette (#74)

- **One line makes a whole task.** "Essay draft tomorrow 5pm #school !high
  every monday /Classes" sets:
  - the due date and time;
  - the tag and the priority;
  - the repeat and the list.

  What Yoru recognises is highlighted as you type. Click a part to keep it as
  plain text instead.
- Tasks can be **due at a time of day**.
- **Cmd/Ctrl-K** opens a command palette. From it you can go to any part of
  Yoru, start a new page, add a task from a line, log time or plan a block.
- A setting turns the reading off, and then the whole line is the title.

## The week

- **Change one week of a repeat** (#59). Skip one week of a weekly block, or
  move it to another time or day, then restore it later. The rule stays as it
  is. Drag it on the grid, double-click it, or use the list under the grid from
  the keyboard.
- **Today's schedule now includes the day's weekly repeats**, not only one-off
  blocks.
- The week's header reads "Sep 20 – 26, 2026". Planned blocks show their
  activity's name.

## Everything can be corrected (#59)

- **Several tasks at once:** change their status, list, dates or tags, or
  delete them. Each change is checked in full before one save.
- **Several sessions at once:** move them to another activity, shift their
  times, or delete them.
- **Habit history:** correct any past day, or add a missed restart to a
  time-since tracker. You can page back through history or jump to a date.
- **Order:** move habits, activities and tags up and down.
- **A habit's own time zone.** Changing it keeps the days already recorded.
- Every kind of record can be created, viewed, edited and deleted from the
  interface. A test now checks each control, record by record.

## Habits

- Time-since trackers count in calendar units, "1y 2mo 3d 4h 5m", and screen
  readers hear them as words.
- An **automatic Anki streak**: any day with reviews counts, and it keeps
  counting while Anki is closed (#100).

## Finer details

- Copy, paste, undo and Command-Delete work in Pages as everywhere else. The
  Inbox explains what it holds.
- Totals read "2h" and "5h 30m" instead of "2h 0m" and "05:30:00".
- A button that is switched off looks switched off, not highlighted.
- Section headers keep names capitalised: "Integrations · Anki".
- Yoru wakes four times a second instead of fourteen, because nothing on
  screen needs more.

## Vault

- This version moves the vault from **schema 20 to 23**, and the export from
  **format 9 to 12**. That is one step each for:
  - weeks changed on their own;
  - the task database;
  - due times.
- A vault from 1.0.21 or earlier opens with nothing lost. The first save keeps
  a copy of the older file beside it.
- Older exports still import.
- The AI switches are kept on this computer, like the text size.

Checks:

- The full isolated suite: 87 test programs, which now run four at a time and
  are found automatically.
- New tests for the assistant tools, the protocol, the socket and its token,
  quick add (86 phrases) and the task database.
- A vault written by each older schema still opens.
- `Yoru --mcp` was checked end to end with the official MCP SDK as the client.
