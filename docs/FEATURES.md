# Feature status

Every feature requested for Yoru, and whether it actually works. One line each,
no hedging. Issue numbers link to
[the tracker](https://github.com/Rabadakku/yoru/issues).

Legend: ✅ working · 🟡 partly working · ⬜ not started

---

## Tracking

| Feature | Status | Notes |
|---|---|---|
| Open-ended clock in/out, no forced pomodoro | ✅ | Running timer survives closing the app |
| Correct a forgotten clock-out | ✅ | |
| Add time you never tracked | ✅ | |
| Edit an existing session | ✅ | |
| Time entry that cannot crash on bad input | ✅ | Reverts invalid text instead of throwing |
| Sessions under 5 minutes don't count | ✅ | Still recorded; the floor is configurable |
| Multiple activities, easy to add | ✅ | |
| Streak heat map, per activity and combined | ✅ | |
| Heat colours matched to a 4–5h daily goal | ✅ | Tiers scale to your goal, not a fixed ladder |
| Rainbow tier for beating the goal | ✅ | Hue cycles by date |
| Heated-titanium ramp | ✅ | Kept as the MIDNIGHT palette |

## Schedule

| Feature | Status | Notes |
|---|---|---|
| Weekly calendar with time gridlines | ✅ | Hour rules, half-hour ticks, now-line |
| Week starts on your chosen day | ✅ | Settings → Tracking; used by both calendars — [#26](https://github.com/Rabadakku/yoru/issues/26) |
| Studied time shown as blocks | ✅ | Solid; planned blocks are outlines behind |
| Click-drag to create | ✅ | Snaps to 15 minutes |
| Drag to move, drag edges to resize | ✅ | Planned blocks only — recorded time is history |
| Schedule efficiency / matched % | ✅ | |
| One weekly schedule that repeats | ✅ | **Repeats weekly…** on the Schedule page; drawn dashed on the grid, kept alongside one-off blocks — [#4](https://github.com/Rabadakku/yoru/issues/4) |
| Double-click to add | ⬜ | Part of [#4](https://github.com/Rabadakku/yoru/issues/4) |

## Tasks

| Feature | Status | Notes |
|---|---|---|
| Task inbox with due dates and notes | ✅ | Deadline and planned day are separate fields — [#25](https://github.com/Rabadakku/yoru/issues/25) |
| Status field (todo / doing / done) | ✅ | One click on the row chip cycles TODO → DOING → DONE |
| Class/section tags with colours | ✅ | Create, rename, recolour, delete under **Tags…**; colour shown on each row — [#3](https://github.com/Rabadakku/yoru/issues/3) |
| Sorting | ✅ | My order, due date, title, status — [#2](https://github.com/Rabadakku/yoru/issues/2) |
| Views: due today / open / completed | ✅ | Due today includes overdue — [#2](https://github.com/Rabadakku/yoru/issues/2) |
| Smaller, denser task cards | ✅ | One row per task: status / title / due / tag — [#2](https://github.com/Rabadakku/yoru/issues/2) |
| Manual task ordering | ✅ | Drag a row, or use ↑/↓; unfiltered view under My order — [#23](https://github.com/Rabadakku/yoru/issues/23) |
| Drag-and-drop task calendar | ✅ | Month view on the Tasks page; drag onto a day to reschedule, onto the strip to unschedule — [#5](https://github.com/Rabadakku/yoru/issues/5) |

## Habits

| Feature | Status | Notes |
|---|---|---|
| Daily check-offs (chores, meds) with streaks | ✅ | |
| Editable 28-day history | ✅ | |
| "Time since" tracker for something quit | ✅ | |
| Backdate the quit date | ✅ | |
| Restart without losing prior periods | ✅ | |

## Collection

| Feature | Status | Notes |
|---|---|---|
| Choose 1 of 3 starters | ✅ | Treecko, Torchic, Mudkip |
| Full National Dex roster | ✅ | All 386 through Gen III |
| Encounter per 30 minutes studied | ✅ | Short sessions accumulate |
| Buddy shown beside the timer | ✅ | |
| Time spent with buddy | ✅ | |
| Evolution via time together | ✅ | Two recorded hours per stage |
| Shiny encounters at boosted odds | ✅ | 10 in 8192, ten times the original |
| Companion animates while studying | ✅ | Breathing, hop, shiny sparkle |
| Party of six | ✅ | Collection card lists the party; **Manage party** reorders, adds and removes — [#12](https://github.com/Rabadakku/yoru/issues/12) |
| Nicknames | ✅ | Per-card **Nickname** control; the species stays visible beside it — [#13](https://github.com/Rabadakku/yoru/issues/13) |
| Encounter animation before the reveal | ✅ | 1.2-second grass reveal; reduced motion skips it; no rerolls — [#9](https://github.com/Rabadakku/yoru/issues/9) |
| Pokémon in a battle-style box | ⬜ | [#8](https://github.com/Rabadakku/yoru/issues/8) — currently a field box |
| Gym battles | ➖ | Removed 2026-09-11: the real game's gyms are played in the Game tab — [#19](https://github.com/Rabadakku/yoru/issues/19) |
| Battle system like the original | ✅ | The original game itself, running in the Game tab — [#29](https://github.com/Rabadakku/yoru/issues/29) |

## Look and feel

| Feature | Status | Notes |
|---|---|---|
| Terminal UI with Japanese/anime touches | ✅ | |
| Linux-ricing direction | ✅ | Flat, self-painted widgets, monospace |
| Four themes | ✅ | MIDNIGHT, EMBER, SAKURA (pink), LINEN (beige) |
| Readable text everywhere | ✅ | Two contrast bugs found and fixed |
| No Java mascot in dialogs | ✅ | |
| Window resizes properly | ✅ | |
| Less button padding | ✅ | |
| Trainer walking animation | ✅ | Real Emerald sheets, walk and run |
| Trainer separate from buddy, near the timer | ✅ | |
| Nature on the route | ✅ | Local grass/shrub/rock sprites, distant pixel silhouettes, wider trail and fixed camera |
| Day/night route lighting | ✅ | Follows local clock with gradual dawn/dusk and night stars |
| Faster animations | ✅ | 70ms tick, 3px per frame |
| People, bikes, legendary flybys | 🟡 | Rare legendary sprite flybys implemented; people/bikes and dedicated flight frames pending — [#10](https://github.com/Rabadakku/yoru/issues/10) |
| Team Rocket cameos | ⬜ | [#11](https://github.com/Rabadakku/yoru/issues/11) |
| Logo and app icon | ✅ | One drawn crescent: crisp pixels below 24px, antialiased above, on a night and a moonlight app icon in Apple's icon grid; exported to `.icns`, `.ico` and PNG — [#57](https://github.com/Rabadakku/yoru/issues/57) |
| Study music from your own files | 🟡 | Plays while the timer runs; **WAV/AIFF/AU only** — the JDK ships no MP3 decoder — [#14](https://github.com/Rabadakku/yoru/issues/14) |

## Data, setup and safety

| Feature | Status | Notes |
|---|---|---|
| Local-only, no server to host | ✅ | |
| Encrypted vault | ✅ | AES-256-GCM, PBKDF2 600k |
| Optional password | ✅ | Chosen when the vault is made; the note beside the tick box says what going without one costs |
| Remove a vault's password | ✅ | Welcome screen → *Rename or delete…*, or Data → Vaults. Re-encrypts under a key kept beside the vault; refuses and rolls back rather than leaving a vault nothing can open |
| Vault reopens after creation | ✅ | |
| Settings menu | ✅ | Appearance, tracking, artwork, integrations, reset |
| Plugins | ⬜ | Not exposed in 1.0: the Integrations card says they are planned. Removed from the UI in #47, back with the adapters |
| Reset all data, or specific parts | ✅ | Backs up first |
| Drop in your own ROM art (folder or zip) | ✅ | Drag onto the window, or Settings → Artwork |
| Data plan so updates don't break history | ✅ | [docs/DATA-MODEL.md](DATA-MODEL.md) |
| JSON export/import | ✅ | Whole vault out and back in from the Data page; import applies as one transaction — [#1](https://github.com/Rabadakku/yoru/issues/1) |
| Distribution build for classmates | ⬜ | [#16](https://github.com/Rabadakku/yoru/issues/16) |

## Integrations

| Feature | Status | Notes |
|---|---|---|
| AI reads class files and adds tasks | ⬜ | Removed from 1.0 (#47): the code is kept, but no build can reach it |
| Use your existing ChatGPT plan | ❌ | **Not possible** — see below |
| Paste-in workflow (free alternative) | ⬜ | [#15](https://github.com/Rabadakku/yoru/issues/15) |
| Anki import | ⬜ | Planned, not connected |
| LeetCode import | ⬜ | Planned, not connected |
| Apple Health / workouts | ⬜ | Planned, not connected |

## Testing

| Level | Status | Notes |
|---|---|---|
| Unit | ✅ | Domain, analytics, encounters, heat tiers, artwork naming |
| Integration | ✅ | Tracker + vault, atomicity, reset scoping, schema round trip |
| System | 🟡 | Every page renders headlessly; no full user-flow drive-through |
| Acceptance | 🟡 | You. Findings become issues. |
| Coverage gap | ⬜ | [#20](https://github.com/Rabadakku/yoru/issues/20) — evolution, shiny, buddy time, edit paths |

**151 automated checks pass.**

---

## Two things that will not work as originally asked

**Using your existing ChatGPT plan for AI.** A ChatGPT Plus or Pro subscription
and the OpenAI API are separate products with separate billing. The subscription
grants zero API quota and there is no key that represents it. Workable instead:
a pay-as-you-go key (well under a cent per syllabus, but a separate bill), the
paste-in flow in [#15](https://github.com/Rabadakku/yoru/issues/15), or a local
model.

**A Java recreation of Emerald's battles.** Built as a prototype across
[#17](https://github.com/Rabadakku/yoru/issues/17),
[#18](https://github.com/Rabadakku/yoru/issues/18) and
[#19](https://github.com/Rabadakku/yoru/issues/19), then removed on 2026-09-11
at the owner's request: the original game now runs inside Yoru, so its battles
are the real ones. Only the base-stat table survived, because placing a
Pokémon in the party means writing the stats the game would calculate.
