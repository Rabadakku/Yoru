# Feature status

What Yoru does today, and what is planned. One line each. Issue numbers link to
[the tracker](https://github.com/Rabadakku/Yoru/issues); the order of the
planned work is in [#65](https://github.com/Rabadakku/Yoru/issues/65).

Legend: ✅ working · 🟡 partly working · ⬜ planned

---

## Time

| Feature | Status | Notes |
|---|---|---|
| Open-ended timer per activity | ✅ | Keeps running when Yoru is closed; recovered when the vault opens |
| Pomodoro mode | ✅ | Focus and break lengths of your own, with sounds drawn from tones (#61) |
| Record time you did not time | ✅ | **Log time** on Today and Data |
| Correct any session | ✅ | Edit one, or move, shift or delete several at once (#59) |
| Minimum session length | ✅ | Configurable; shorter sessions do not count |
| Activities with daily targets | ✅ | Create, rename, retarget, reorder and delete, choosing what happens to their time |
| Daily goal and streak | ✅ | Today shows progress against the goal and the current streak |
| 52-week heat map, 14-day chart, focus mix | ✅ | On Data, per activity or all together, scaled to your goal |
| Anki study time | ✅ | Finished Anki sittings become tracked time through AnkiConnect, never counted twice |
| Export sessions as CSV | ✅ | |
| A new timer centrepiece on Today | ⬜ | Waits on the owner's choice of concept (#62) |

## The week

| Feature | Status | Notes |
|---|---|---|
| Week grid: planned beside recorded | ✅ | Hour rules, a now-line and how much of each plan was matched |
| Drag to plan, move and resize | ✅ | Snaps to 15 minutes; recorded time stays history |
| Weekly repeats | ✅ | A template of blocks that repeat every week, drawn dashed |
| Change one week of a repeat | ✅ | Skip it, move it or restore it without touching the rule (#59) |
| Week starts on your chosen day | ✅ | Used by both calendars |
| Today's schedule | ✅ | One-off blocks and weekly repeats together |
| Drag tasks onto the week | ⬜ | #83 |
| Google, iCloud and Outlook calendars | ⬜ | #84 |

## Tasks

| Feature | Status | Notes |
|---|---|---|
| Lists of your own, and an Inbox | ✅ | A rail beside the table; drag a task onto a list (#56) |
| Several tags per task | ✅ | Made as you type (#66) |
| Due date and time, planned day | ✅ | A new task is due the day it is written (#67) |
| Repeating tasks | ✅ | One task with a history; every weekday, the last Friday, three days after done… (#57) |
| Priority, your own statuses, custom properties | ✅ | Text, number, select, multi-select, date, checkbox, link, created and edited (#68) |
| Quick add in plain language | ✅ | "Essay draft tomorrow 5pm #school !high every monday" (#74) |
| Command palette | ✅ | Cmd/Ctrl-K: go anywhere, add a task, log time, plan a block |
| Views, sorting and manual order | ✅ | All, due today, next five days, open, completed |
| Month calendar | ✅ | Drag to reschedule; double-click a day for a new task |
| Act on several tasks at once | ✅ | Status, list, dates, tags and delete (#59) |
| Import from Notion, or from an AI chat's reply | ✅ | Reviewed before one all-or-nothing import |
| Sub-tasks, checklists and dependencies | ⬜ | #70 |
| Saved views with filters and grouping | ⬜ | #69 |
| A task side panel with rich notes | ⬜ | #71 |
| Projects, templates | ⬜ | #72, #73 |
| Reminders and notifications | ⬜ | #76 |

## Habits

| Feature | Status | Notes |
|---|---|---|
| Daily check-offs | ✅ | Streak, best run, consistency since the start and the last week (#53, #55) |
| Time-since trackers | ✅ | "1y 2mo 3d 4h 5m", restarts kept as history (#50, #52) |
| Correct history | ✅ | Any past day, missed restarts, paging and jump to a date (#59) |
| Order and time zone per habit | ✅ | A habit's days follow its own zone (#59) |
| Tick off from Today and Tasks | ✅ | From the keyboard too (#54) |
| Automatic Anki streak | ✅ | Any day with reviews counts (#100) |
| Counts, amounts, ratings, X times a week | ⬜ | #82 |

## Pages

| Feature | Status | Notes |
|---|---|---|
| Markdown editor and reading view | ✅ | Styled as you type; callouts, tables, code (#46) |
| Folders, tabs, trash and restore | ✅ | |
| Links, backlinks and outline | ✅ | `[[Page]]` links follow renames |
| Search and quick switcher | ✅ | |
| Tasks linked to pages | ✅ | Each shows the other |
| Import and export a Markdown folder | ✅ | Obsidian-compatible names |
| Icons, emoji and covers | ⬜ | #78 |

## AI

| Feature | Status | Notes |
|---|---|---|
| Use Yoru from Claude Desktop, Claude Code or any MCP app | ✅ | No API key: your own account with that app; off until switched on (#47) |
| Assistants read tasks, pages, schedule, time and habits | ✅ | 9 reading tools |
| Assistants add and edit, behind a second switch | ✅ | 12 changing tools; none deletes; backed up first |
| One-click setup for Claude Desktop | ✅ | Keeps everything else in its settings |
| AI inside the page editor (rewrite a selection) | ⬜ | Needs a model Yoru can call itself; see #47 |

## Look and feel

| Feature | Status | Notes |
|---|---|---|
| Sidebar navigation, native Mac menus, unified title bar | ✅ | Windows and Linux use an in-window menu bar (#63) |
| Five themes, or follow macOS appearance | ✅ | Midnight, Ember, Sakura, Linen, Moonlight |
| Text from 100% to 200% | ✅ | Every label is checked to fit at every size |
| Keyboard-first, with copy, paste and undo everywhere | ✅ | A native-keyboard check on a real Mac is still open (#49) |
| Study music from your own files | 🟡 | WAV, AIFF and AU; the JDK ships no MP3 decoder |
| A new logo and app icon | ⬜ | #64 |
| Owner sign-off on the redesign | ⬜ | #63 |

## Data and safety

| Feature | Status | Notes |
|---|---|---|
| One encrypted vault, no account or server | ✅ | AES-256-GCM; optional password |
| Several vaults | ✅ | Create, switch, rename, remove a password, delete |
| Backups before anything destructive | ✅ | Pruned automatically |
| Every record can be created, viewed, edited and deleted | ✅ | Held row by row by `CrudCoverageTest` (#59) |
| Export and import the whole vault as JSON | ✅ | Import is one transaction |
| Reset everything, or chosen parts | ✅ | Backs up first |
| Old vaults keep opening | ✅ | A vault from every released schema is tested |
| In-app updates | ✅ | Checked by hand; installers verified by SHA-256 |

## Later, on the same rules

A home dashboard (#75), weekly and monthly reviews (#80), goals (#81),
collections of your own (#79), meal planning (#60) and LeetCode practice (#41).
