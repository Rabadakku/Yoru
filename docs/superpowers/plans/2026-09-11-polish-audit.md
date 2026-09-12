# Polish audit — issue #47 (a ricer aesthetic, an Apple-level finish)

Reconnaissance for the ticket described in `2026-09-11-v1.0-remainder.md` (#47).
Read-only: nothing here changes code. Every finding below names the class, the
element and one concrete fix, so the implementing agent can work straight down
the list.

Scope: `src/main/java/dev/yoru/ui/` — `Theme.java`, `YoruApp.java`, every page
class behind `Shell` (`GamePage`, `CollectionPage`, `TasksPanel`, `HabitsPanel`,
`TagEditor`, `WeeklyTemplate`, `TaskPastePanel`, `NotionImportPanel`,
`StorageScreen`), the shared dialogs (`Dialogs`, `DateTimeField`), and the drawn
scenes that carry type (`BuddyScene`, `TrainerScene`, `ScheduleGrid`, `Heatmap`,
`FocusBars`, `StorageScreen`, `RouteCameos`, `GameScreen`).

Line numbers are from `main` at the time of writing; re-check before editing.

---

## 0. Surfaces inventory

| Surface | Class | Notes |
|---|---|---|
| Window chrome, nav bar, page heading | `YoruApp.java:56-104`, `:159-184` | 8 nav tabs; the heading is built in `showPage`, not by the page |
| Today | `YoruApp.today()` `:203-336` | focus session, partner card, 3 stats, schedule preview, heat map, activity list |
| Tasks | `TasksPanel` | board, views, sort, calendar, 3 import actions |
| Habits | `HabitsPanel` | page + its own `JScrollPane` |
| Schedule | `YoruApp.schedule()` `:508-598` | nav row, `ScheduleGrid`, legend, planned list, note card |
| Collection | `CollectionPage` | encounters, on-their-way, storage, details |
| Game | `GamePage` | 5 phases: IDLE / STARTING / RUNNING / CLOSING / STUCK |
| Data | `YoruApp.data()` `:619-758` | actions row, 14-day chart, focus distribution, 2 tables |
| Settings | `YoruApp.settings()` `:936-1098` | 6 cards |
| Plugins | `YoruApp.plugins()` `:1112-1148` | reachable only from Settings; not a nav tab |
| Modal dialogs | `Dialogs` + `DateTimeField` | every dialog in the app |
| Overlays | `TagEditor`, `WeeklyTemplate`, `TaskPastePanel`, `NotionImportPanel` | panels hosted in `Dialogs.choose` |

Two local artefacts that are **not** tracked by git and must not be committed: a
duplicate `NotionImportPanel 2.java` (byte-identical to the real one) and a
`MusicUiTest 2.java` / `NotionImportPreview 2.java` / `NotionImportTest 2.java`
set under `src/test/`. They are copies left on the working tree; leave them
alone (do not delete, do not commit).

---

## 1. Spacing

The app has no spacing scale. Values are hand-typed per call site, and
near-equivalent elements differ.

### 1.1 Page header block

| Page | Title → gap → subtitle → gap | Source |
|---|---|---|
| Today | 25 → 8 → 11 → 24 | `YoruApp.java:205-208` |
| Tasks | 25 → 8 → 11 → 20 | `TasksPanel.java:47-48` |
| Habits | 28 → **no gap** → 13 → 0 | `HabitsPanel.java:15-16` |
| Schedule | 26 → 6 → 11 → 18 | `YoruApp.java:510-513` |
| Data | 28 → 8 → 13 → 18 | `YoruApp.java:621-624` |
| Settings | 26 → 6 → 11 → 20 | `YoruApp.java:939-942` |
| Game | 25 → 8 → 11 → 18 | `GamePage.java:45-46` |
| Collection | 25 → 8 → 11 → 20 | `CollectionPage.java:38-39` |
| Plugins | 28 → 8 → 13 → 24 | `YoruApp.java:1114-1117` |

- **HabitsPanel** is the only page whose subtitle is glued to the title
  (`HabitsPanel.java:15-16`, two `body.add(label(...))` with no `gap`). Fix: add
  `gap(body,8)` and use the same title/subtitle pair as every other page.
- **Fix (all):** one helper on `Theme` — `pageHeader(String title, String subtitle)`
  returning title 25/`TEXT`, `gap 8`, subtitle 11/`MUTED`, `gap 20` — and call it
  from all eight pages. See §2.1 for the sizes.

### 1.2 Card internals

`Theme.card()` is `EmptyBorder(20,22,20,22)` (`Theme.java:254`) but the gaps
inside cards are ad hoc:

| Card | Header → first row | Between rows | Last row → bottom |
|---|---|---|---|
| Appearance (Settings) | 6 | 14, 16 | 0 |
| Tracking (Settings) | 14 | 6, 14 | 0 |
| Study music (Settings) | 6 | 10, 12 | 0 |
| Artwork (Settings) | 6 | 10, 12 | 0 |
| Integrations (Settings) | 10 | 12 | 0 |
| Reset data (Settings) | 10 | 8, 14 | 0 |
| Focus session (Today) | 16 | 15, 8, 14, 14 | 0 |
| Partner (Today) | 0 | 10, 8, 12, 8 | 0 |
| Activity / 52 weeks (Today) | 0 | 0 | 0 |
| Study encounters (Collection) | 10 | 6, 12 | 0 |
| On their way (Collection) | 10 | 6, 6 | 0 |

Sources: `YoruApp.java:944-954`, `:972-1010`, `:1014-1050`, `:1054-1076`,
`:1079-1085`, `:1088-1096`, `:212-255`, `:264-271`, `:289-318`,
`CollectionPage.java:57-73`, `:77-105`.

- **Fix:** add `Theme.cardHeader(JPanel card, String text)` (header label 12/`CYAN`
  + `gap 10`) and `Theme.sectionGap(JPanel card)` (= `gap 12`), so every card uses
  the same three numbers: header gap 10, between-block gap 12, bottom 0.

### 1.3 Row-of-controls gaps

Six different `FlowLayout` configurations for "a horizontal row of things":

| Site | Layout | Element |
|---|---|---|
| `Theme.java:244` | `FlowLayout(LEFT,12,6)` | every `Theme.row()` |
| `TasksPanel.java:40` | `FlowLayout(LEFT,8,0)` | sort controls |
| `TasksPanel.java:213` | `FlowLayout(LEFT,10,0)` | task row right column |
| `TasksPanel.java:341` | `FlowLayout(LEFT,6,0)` | tag chip |
| `CollectionPage.java:129` | `FlowLayout(LEFT,8,0)` | storage controls |
| `TagEditor.java:47,62`, `WeeklyTemplate.java:72` | `FlowLayout(LEFT,8,0)` | dialog rows |
| `DateTimeField.java:26` | `FlowLayout(LEFT,6,0)` | date + time + Now |

- **Fix:** one `Theme.row()` (gap 12) plus `Theme.tightRow()` (gap 8) and
  `Theme.chip()` (gap 6); replace all six call-site layouts.

### 1.4 List-row padding

| Element | Padding | Source |
|---|---|---|
| Task row | `MatteBorder(0,0,1,0,LINE)` + `EmptyBorder(7,12,7,12)` | `TasksPanel.java:247` |
| Today's schedule preview line | `EmptyBorder(5,12,5,12)` | `YoruApp.java:361` |
| Schedule page planned-block line | `EmptyBorder(4,0,4,0)` | `YoruApp.java:571` |
| Tag row | no border | `TagEditor.java:46` |
| Habit day cell | `setMargin(2,2,2,2)`, 22×26 | `HabitsPanel.java:31` |

- **Fix:** one `Theme.listRow()` border (`EmptyBorder(6,12,6,12)`, optional bottom
  rule) and use it in all four list contexts. Note `TasksPanel` row height is
  then driven by content; keep `setMaximumSize` as today (`TasksPanel.java:241`).

### 1.5 Grid gutters

`GridLayout(1,2,16,0)` (`CollectionPage.java:44`), `GridLayout(1,3,14,0)`
(`YoruApp.java:275`), `GridLayout(1,0,12,0)` (`YoruApp.java:949`),
`GridLayout(1,14,8,0)` (`YoruApp.java:636`), `GridLayout(2,14,5,5)`
(`HabitsPanel.java:27`), `GridLayout(1,0,3,0)` (`YoruApp.java:869`).

- **Fix:** two tokens — 16 for side-by-side cards, 8 for cells inside one card.

### 1.6 Outer chrome

- Nav bar `EmptyBorder(10,22,10,22)` (`YoruApp.java:62`) vs page content
  `EmptyBorder(24,28,24,28)` (`YoruApp.java:84`) — the bar's 22 and the content's
  28 do not line up, so the brand mark and page titles sit on different left
  edges. **Fix:** content `EmptyBorder(24,22,24,22)`.
- Page heading block `EmptyBorder(0,0,22,0)` (`YoruApp.java:161`) — a fourth
  number between the header and the first card. **Fix:** fold into `pageHeader`
  (§1.1) as `gap 20`.
- Card vs frame: `Theme.card()` is `(20,22,20,22)`; the two grid frames are
  `(10,10,10,10)` (`YoruApp.java:542`, `TasksPanel.java:184`); theme cards are
  `(14,14,14,14)` (`YoruApp.java:858`). **Fix:** `(20,22)` for cards, `(12,12)`
  for a frame around a drawn surface, `(16,16)` for a picker tile.
- Two minimum sizes for the same window: `setMinimumSize(new Dimension(720,520))`
  (`YoruApp.java:55`) and `frame.setMinimumSize(new Dimension(900,640))`
  (`YoruApp.java:1191`). **Fix:** keep one (the frame's `900,640`), drop the
  other, and put a comment saying which wins.

---

## 2. Typography

`Theme.mono(size)` is the only type API (`Theme.java:111`); every size is a
literal at the call site. `Theme.sans` is used only by `Dialogs` body text
(`Dialogs.java:28`), `Theme.install` defaults (`Theme.java:144,148`),
`themeCard`'s blurb (`YoruApp.java:865`) and the two import panels' prose
(`TaskPastePanel.java:54`, `NotionImportPanel.java:66`) — so prose is sometimes
sans and sometimes mono for the same role.

### 2.1 Page titles

25 (`Today`, `Tasks`, `Game`, `Collection`), 26 (`Schedule`, `Settings`), 28
(`Data`, `Habits`, `Plugins`). Sources in §1.1.

- **Fix:** one size, 26, for every page title.

### 2.2 Page subtitles

11 (`Today`, `Tasks`, `Schedule`, `Settings`, `Game`, `Collection`) vs 13
(`Data`, `Habits`, `Plugins`). Same list of sources.

- **Fix:** 11 for every subtitle, `MUTED`, uppercase, `·` as the separator (not
  `/` — see §6.6).

### 2.3 Card section headers

| Size | Sites |
|---|---|
| 11 | `YoruApp.java:213` "01 / FOCUS SESSION", `:262` "PARTNER / …", `TasksPanel.java:76` "OPTIONAL ONLINE ASSISTANCE", `GamePage.java:70` "YOUR GAME / …", `CollectionPage.java:58,81` |
| 12 | `YoruApp.java:291,322,348,565,633,667,749`, `:1141` "NOT CONNECTED YET" |
| 16 | `TagEditor.java:43` "TAGS / n", `WeeklyTemplate.java:47` "EVERY WEEK / n" |
| 17 | `YoruApp.java:1132` plugin card titles |
| 18 | `YoruApp.java:945,973,1015,1055,1080,1089` — all six Settings cards |

- **Fix:** three roles only — 12/`CYAN` for a card header, 11/`MUTED` for a
  field/view label, 16/`CYAN` for a dialog-panel header (`TagEditor`,
  `WeeklyTemplate` are already correct; the Settings and plugin cards are the
  outliers).

### 2.4 Emphasis / value type

- The timer is 60 (`YoruApp.java:226`) — the one 60 px string in the app; every
  other figure is 22 (Collection counts, Game hero) or 25 (`YoruApp.stat()`
  `:376`). **Fix:** one value scale: 22 for a card's headline figure, 25 for a
  stat tile, 56 for the timer.
- `HabitsPanel.java:37` elapsed time is 22, `:21` habit name is 20,
  `CollectionPage.java:110` empty title is 20, `GamePage.java:72-80` hero titles
  are 22, `CollectionPage.java:185` selected-mon name is 18. **Fix:** 20 for a
  card headline (habit name, empty-state title, hero title), 22 only for a
  numeric figure.

### 2.5 Dialog headings

14 (`YoruApp.java:494,726,798`, `TagEditor.java:96`), 15 (`YoruApp.java:1165`),
18 (`TasksPanel.java:461`, `TaskPastePanel.java:22`, `NotionImportPanel.java:60`,
`GamePage.java:181,192,250`, `VaultLauncher.java:17`), 20 (`VaultLauncher.java:64`,
`CollectionPage.java:110`).

- **Fix:** one dialog heading, 18/`TEXT` (`YoruApp.editOnGrid`'s 15 and the
  confirmation prompts' 14 are the outliers).

### 2.6 Body / helper copy

11 and 12 are used interchangeably inside the same card. Examples:
`YoruApp.java:702,717,720,721` (11) beside `:719` (12) in the Data page;
`GamePage.java:103` (11) beside `:104`'s prose (12); `CollectionPage.java:62`
(11) beside `:61` (12) in the same card.

- **Fix:** 12/`MUTED` for prose, 11/`MUTED` for a metadata line (a trailing
  figure, a key, a unit). Apply by role, not by look.

### 2.7 Prose face

`Dialogs.body()` sets `sans(14)` (`Dialogs.java:28`) while every on-page
paragraph is mono. `themeCard`'s blurb is `sans(11)` (`YoruApp.java:865`),
`TaskPastePanel.text()` is `sans(12)` (`:54`), `NotionImportPanel`'s
instructions are `sans(12)` (`:66`), and the status/message areas in both are
`sans(12)` (`TaskPastePanel.java:46`, `NotionImportPanel.java:93`).

- **Fix:** decide once — mono for anything the terminal aesthetic owns
  (labels, figures, buttons) and sans for running prose of two sentences or
  more. Then the blurb, the import instructions and the dialog body agree.
  Currently they do not even agree with each other.

---

## 3. Controls

### 3.1 Button padding

| Button | Padding | Source |
|---|---|---|
| `Theme.button` / `accentButton` | `EmptyBorder(7,13,7,13)` | `Theme.java:315,325` |
| LAF default (unstyled `JButton`, `JOptionPane` buttons) | `EmptyBorder(7,14,7,14)` | `Theme.java:175` |
| `TasksPanel.compact` | `EmptyBorder(4,9,4,9)`, mono 11 | `TasksPanel.java:84-88` |
| Nav tab | `EmptyBorder(8,6,8,6)` → rebuilt as `MatteBorder` + `EmptyBorder(8,6,6,6)` | `YoruApp.java:73,190` |
| Habit day cell | `setMargin(2,2,2,2)` + 22×26 | `HabitsPanel.java:31` |

- **Fix:** keep `Theme.button`'s sizes; make the UIManager default match
  (`7,13`), and make `compact` the only exception with one documented size
  (`4,10`). Dialog buttons then stop being 1 px wider than page buttons.

### 3.2 Control heights

| Control | Size | Source |
|---|---|---|
| Activity picker (Today) | 480×36 | `YoruApp.java:216` |
| Week-start combo (Settings) | 160×32 | `YoruApp.java:994` |
| Sort combo (Tasks) | 132×28 | `TasksPanel.java:65` |
| Goal / minimum spinners (Settings) | 90×32 | `YoruApp.java:976,984` |
| Date / time spinners (`DateTimeField`) | 132×34 / 112×34 | `DateTimeField.java:44` |
| Volume slider | 240×32 | `YoruApp.java:1034` |

- **Fix:** one control height token, 32, and one height for the in-dialog
  `DateTimeField` (keep 34 only if that `FlowLayout` row needs it — then make
  every control in the row 34).

### 3.3 Radii and chrome

`Theme.card()`, the two grid frames and `themeCard` are all square
`LineBorder(LINE)`. Everything drawn inside them is rounded:
`ScheduleGrid.paintSegment` uses `drawRoundRect(...,8,8)`
(`ScheduleGrid.java:381,389`), the provisional drag `8,8` (`:365`),
`StorageScreen` `12,12` (`StorageScreen.java:189,192`) and its party cells
`8,8` (`:228`), `Heatmap` cells `3,3` (`Heatmap.java:78`), `FocusBars` bars
square (`FocusBars.java:93`), `FlatButton` a hard `fillRect` (`Theme.java:285`).

- **Fix:** introduce `Theme.RADIUS` (8) and use it for cards, frames, buttons and
  drawn blocks; drop `Heatmap`'s 3 and `StorageScreen`'s 12 onto the same token,
  or accept square containers and square the four drawn surfaces instead. The
  current mix is the single biggest "not Apple" tell.

### 3.4 Selected / active state — four different treatments

1. Filled accent: Settings trainer picker (`YoruApp.java:960` `setBackground(CYAN)`),
   the clock button (`YoruApp.java:249-250`, a hand-copy of `accentButton`).
2. Text colour only: Tasks view buttons (`TasksPanel.java:124`
   `setForeground(value==view?CYAN:MUTED)`).
3. Disabled (greyed) to mean "this is the current one": theme card
   (`YoruApp.java:881-882` `pick.setEnabled(!chosen)`), focus-distribution range
   (`YoruApp.java:669-670` `pick.setEnabled(mixDays!=span)`).
4. Underline: nav tab (`YoruApp.java:190` `MatteBorder(0,0,2,0,...)`).

- **Fix:** one selected treatment for a segmented choice (filled accent, as the
  trainer picker already does), plus the nav's underline as the documented
  exception for tabs. The disabled-to-mean-selected pattern is the worst of the
  four: a disabled accent button loses its accent entirely (`Theme.java:280`
  forces `DISABLED_FILL`), so the active theme tile and the active range look
  *less* prominent than the inactive ones.
- `YoruApp.java:249-250` duplicates `accentButton` logic (`accentButton` uses
  `DARK?BG:PANEL` for the label, `:250` uses `BG` unconditionally). **Fix:**
  replace with `accentButton(...)`.

### 3.5 Disabled styling

`FlatButton.paintComponent` paints `DISABLED_FILL` and hand-draws the label in
`DISABLED_TEXT` (`Theme.java:280-301`). Consequences:

- A disabled *accent* button is a grey box: `Play` while a game file is missing,
  `Open encounter` with none waiting (`CollectionPage.java:66`), the active theme
  tile (`:881`), `Export save…` (`GamePage.java:98`).
- **Fix:** keep the fill swap but give a disabled accent button the accent at
  low alpha (or `shade(CYAN,-60)`) so the action stays recognisable, and add a
  `setToolTipText` explaining *why* on every disabled control. Today only the
  task row buttons carry a reason (`TasksPanel.java:223,226,234`).

### 3.6 Checkboxes

`UIManager.put("CheckBox.font", sans(13))` (`Theme.java:148`), but the music
toggle overrides to `mono(12)` (`YoruApp.java:1027`) while the one 13 lines
above it does not (`YoruApp.java:964`). Two checkboxes in the same card,
different faces.

- **Fix:** mono 12 for every checkbox; drop the UIManager sans default.

### 3.7 Spinners

`Theme.install` sets `Spinner.background/foreground` but leaves the default
`SpinnerUI`, so Metal's spinner arrow buttons survive while combos and
scrollbars were switched to the flat Basic delegates (`Theme.java:168-170`).
The Settings spinners render with chrome that matches nothing else.

- **Fix:** either `UIManager.put("SpinnerUI", BasicSpinnerUI.class.getName())`
  with a `LineBorder(LINE)` on the editor, or hide the arrows and rely on the
  text field.

### 3.8 JSlider

The volume slider (`YoruApp.java:1032-1038`) has no ticks, no value readout and
no accessible name for the value; the only feedback is the thumb position.

- **Fix:** show the value (`label(MusicPlayer.volume()+"%")` next to "VOLUME"),
  add tick marks, and `getAccessibleContext().setAccessibleName("Volume")`.

### 3.9 Tables — three separate stylings

| Site | Row height | Grid colour | Selection fg |
|---|---|---|---|
| `YoruApp.table()` `:599-618` | 32 | `LINE` | `CYAN` |
| Tasks AI review `TasksPanel.java:489` | 36 | default | `TEXT` |
| Notion preview `NotionImportPanel.java:87-90` | 28 | default | `TEXT` |

- **Fix:** one `Theme.plainTable(JTable)` that sets row height 30, `LINE` grid,
  `LINE` selection background and `CYAN` selection foreground, and call it from
  all three.

### 3.10 Dialogs

- Every `JOptionPane` goes through `Dialogs.show` (`Dialogs.java:35-38`) with
  `PLAIN_MESSAGE` and a null icon — good, and worth keeping. But dialog buttons
  take the LAF border (`7,14`) while page buttons take `7,13` (§3.1);
  `OptionPane.buttonFont` is mono 13 (`Theme.java:150`), which does match.
- `Dialogs.input` adds `EmptyBorder(4,4,4,4)` to the panel (`Dialogs.java:84`)
  while `Dialogs.select` does not (`:90-95`) — the two single-field dialogs are
  4 px different. **Fix:** drop the border from `input`.
- `JColorChooser.showDialog` in `TagEditor.java:86` is the one stock Swing
  dialog in the app; it opens with the platform look and a light theme, inside a
  dark riced window. **Fix:** either accept and document it, or build the
  swatch picker `TagEditor.nextColour()` already hints at (`:105-108`).

---

## 4. Focus states and keyboard affordances

### 4.1 There is no focus indication anywhere

`Theme.FlatButton` sets `setFocusPainted(false)` (`Theme.java:269`), and every
button's border is replaced with `LineBorder(LINE)` + `EmptyBorder` (`:315`), so
the LAF focus ring has nothing to draw into. Text fields replace their border
with `LineBorder(LINE)` in `Theme.styleInput` (`:210`) with no focus listener,
and `plainCombo` does the same (`:196`). A grep for `FocusListener`,
`requestFocus`, `KeyStroke` and `getInputMap` across `ui/` returns only
`StorageScreen` (the Escape binding) and `GameScreen` (click-to-focus).

- **Fix:** one shared focus treatment, applied in one place: a `FocusListener`
  installed by `Theme.button`, `Theme.styleInput` and `Theme.plainCombo` that
  swaps `LineBorder(LINE)` for `LineBorder(CYAN,2)` on buttons and fields alike,
  and stop calling `setFocusPainted(false)` on `FlatButton` (or draw the ring
  manually in `paintComponent`). Today the only control with a visible focus cue
  is a `JCheckBox`, and only by accident of Metal's painting.

### 4.2 The destructive button is the default button

`Dialogs.show` passes `options[0]` as the `initialValue` (`Dialogs.java:37`),
and `showOptionDialog` focuses that component. For `confirmDestructive`,
`options[0]` is the confirm label — so every destructive dialog in the app opens
with **Delete / Remove / Reset / Replace everything** focused, and Enter or
Space fires it: `YoruApp.java:498,581,729,741,804,1046`, `TagEditor.java:99`,
`CollectionPage.java:241`, and the TasksPanel delete paths. `Dialogs.confirm`
has the same shape and is used for the clock-out and import-replace prompts.

- **Fix (one line, highest value in this audit):** `show(...)` should pass the
  *last* option as `initialValue` when a destructive flag is set, so Cancel holds
  focus; keep Enter-on-first for non-destructive confirms.

### 4.3 Keyboard routes that do not exist

| Action | Mouse | Keyboard |
|---|---|---|
| Create a schedule block on the grid | drag (`ScheduleGrid.java:139-159`) | none on the grid; only the "+ Plan block" button |
| Move / resize a block | drag (`:160-181`) | none |
| Open a block's editor | double-click (`:141`) | none |
| Reorder a task | drag (`TasksPanel.java:264-286`) **and** ↑/↓ (`:221-227`) | ↑/↓ — the one good precedent |
| Pick a habit day | 28 buttons (`HabitsPanel.java:30-34`) | Tab-reachable, with `AccessibleName` (`:33`) — good precedent |
| Read a heat-map day | tooltip (`Heatmap.java:39-46`) | an accessible name only (`:31-32`), one long sentence |
| Storage slots | click (`StorageScreen.java:67-69`) | none; Escape cancels a pickup (`:71-75`) |

- **Fix:** add `KeyStroke` bindings to `ScheduleGrid` for Enter (open the
  selected span) and arrow-key nudge of the selected span; give `Heatmap` a
  focusable, arrow-navigable cursor that moves the tooltip into a real label;
  give the Game tab a documented "press an arrow to take the keyboard" hint that
  is also reachable by Tab (§4.4).

### 4.4 Focus order and reachability

- The nav bar is a `GridLayout` of 8 buttons (`YoruApp.java:66-76`) plus
  `close`; Tab from the last content control lands there and the focus is
  invisible, so the user cannot tell where they are (§4.1).
- `TaskPastePanel` makes the read-only prompt area focusable
  (`TaskPastePanel.java:26`) so it can be copied, but gives no cue. **Fix:** keep
  it focusable and give it the shared focus border.
- `GameScreen.setFocusable(true)` (`GameScreen.java:44`) with click-to-focus
  (`:54-56`) and the hint in a label (`GamePage.java:238`). **Fix:** make the
  picture Tab-reachable in a predictable position (right after the Play/Pause
  row), and show the hint over the picture the first time it is shown without
  focus.
- No mnemonics anywhere (`setMnemonic` matches nothing). **Fix:** decide whether
  the app wants them; if yes, `Theme.button(text, mnemonic, fn)`.

---

## 5. Empty states

Two shapes exist: a **card** with a headline (17–20/`TEXT`) and one or two muted
lines (Tasks, Collection, Game), and a **bare muted line** (12/13) dropped into
a card (everywhere else). Several surfaces have nothing at all.

| Page / card | Empty state today | Source | Verdict |
|---|---|---|---|
| Tasks board | card: "No assignments yet." + 2 lines, per-view wording | `TasksPanel.java:134-147` | the reference shape |
| Today → Focus session | none; the combo is empty and the clock button silently opens "New activity" | `YoruApp.java:239-248,393-407` | **missing** |
| Today → Partner | "PARTNER / NOT CHOSEN YET" + scene text "Choose your starter in the game." + button | `YoruApp.java:262,271`, `BuddyScene.java:46` | present, thin (§7) |
| Today → Today / Schedule | "No blocks scheduled today." (12) | `YoruApp.java:355-357` | bare line |
| Today → Tracked activities | "No activities yet. Use + Activity to create one." (13) | `YoruApp.java:324` | bare line; 13 px, not 12 |
| Today → Activity / 52 weeks | nothing; 52×7 empty cells | `YoruApp.java:289-318` | **missing** |
| Tasks → Calendar view | falls through to the board's empty card ("No assignments yet.") | `TasksPanel.java:142` | wrong wording for a calendar |
| Habits | "Daily: chores, medication, reading / Time since: a habit you've quit" (13) | `HabitsPanel.java:19` | a hint, not an empty state: no headline, no CTA |
| Schedule → grid | nothing; an empty week draws an empty grid | `ScheduleGrid.java` | **missing** |
| Schedule → Planned this week | "Nothing planned. Recorded time still appears above." (12) | `YoruApp.java:567` | bare line |
| Collection → Study encounters | "None waiting" (22) + detail (11) | `CollectionPage.java:60-62` | good shape |
| Collection → On their way | "Nothing waiting" (22) + detail (11) | `CollectionPage.java:83-84` | good shape, different words from the sibling above |
| Collection → storage | card "No Pokémon yet" / "Choose your starter" (20) + CTA | `CollectionPage.java:108-117` | good shape, title 20 vs 22 elsewhere |
| Game → idle | per-phase hero: "No save yet" / "A save Yoru cannot read" / "A new adventure" (22) | `GamePage.java:72-80` | good shape |
| Game → no core / no ROM | bare 18 `TEXT` headings + prose, **not** in a card | `GamePage.java:181,192` | inconsistent with every other page |
| Data → 14-day chart | nothing; 14 bars of 2 px | `YoruApp.java:636-654` | **missing** |
| Data → Focus distribution | "No time recorded in this window." (12) | `YoruApp.java:685` | bare line |
| Data → sessions table | empty table, headers only | `YoruApp.java:706-712` | **missing** |
| Data → daily totals | empty table, headers only | `YoruApp.java:751-756` | **missing** |
| Settings → Music / Artwork | `library.summary()` / `survey.summary()`, recoloured `MUTED` when empty | `YoruApp.java:1020,1060`, `MusicLibrary.java:43`, `ArtworkLibrary.java:46` | text only, no card |
| Plugins | always three cards | `YoruApp.java:1118-1139` | n/a |
| Tags dialog | "No tags yet." (13) | `TagEditor.java:60` | bare line |
| Weekly template | "Nothing repeats yet." (13) | `WeeklyTemplate.java:67` | bare line |

**Fix:** one `Theme.emptyState(String headline, String detail, JButton action)`
component — headline 20/`TEXT`, detail 12/`MUTED`, optional action button — used
by every row marked "bare line" or "missing" above. Then:

- Today's Focus session needs one for "no activities": headline "No activities
  yet.", detail "Create one to start a session.", action `+ Activity`.
- The Data tables need a "Nothing recorded in this range." row (or the table
  replaced by the empty component) — an empty grid reads as a bug.
- The 14-day chart and the heat map need a first-run treatment: "No time
  recorded yet." / "Your first session fills this in."
- Game's `needCore` / `needRom` should be cards, matching `hero`.
- Habits needs a real one: "No habits yet." / "Check off a day, or count the
  time since you stopped.", plus the two existing buttons.

---

## 6. Wording

### 6.1 The same action, two names

| Concept | Names in use | Sources |
|---|---|---|
| Log a session by hand | "+ Add time" (Today) vs "+ Log time" (Data) — both call `timeDialog(false)` | `YoruApp.java:253` vs `:626` |
| Plan a block | "+ Block" (Today) vs "+ Plan block" (Schedule) — both call `timeDialog(true)` | `YoruApp.java:350` vs `:522` |
| Edit a recorded session | "Edit timer" (Today) vs "Edit selected session" (Data) vs "Edit" (Schedule list) | `YoruApp.java:254,736,579` |
| Open the game | "Open the game" (Today partner, Collection empty) vs "▶ Play" (Game) | `YoruApp.java:271`, `CollectionPage.java:115` vs `GamePage.java:93` |
| Choose the game file | "Choose your game file." (heading) vs "Choose game file…" (button) vs "Change game file…" (button) | `GamePage.java:192,195,100` |
| Remove a record | "Remove" (Schedule blocks, music, short sessions) vs "Delete" (Tasks, Tags, Data sessions) vs the domain's `deleteSession`/`deleteBlock` | `YoruApp.java:580,1042,724` vs `TagEditor.java:55`, `YoruApp.java:741` |
| Reset | card "Reset data" vs button "Choose data to reset…" vs dialog "Choose reset scope" vs confirm "Confirm reset" | `YoruApp.java:1089,1095,1104,1109` |
| Close the vault | button text "close" vs tooltip "Close vault…" vs accessible name "Lock & close" | `YoruApp.java:78-80` |
| Imports on the Tasks toolbar | "Paste task proposals…", "Import with API…", "Import Notion export…" | `TasksPanel.java:52-54` |

**Fixes:**

- "+ Add time" and "+ Log time" → one label (prefer "+ Log time"), and rename the
  dialog title from "Add missed time" (`YoruApp.java:437`) to "Log time".
- "+ Block" / "+ Plan block" → "+ Plan block" everywhere.
- "Remove" → "Delete" for anything destroyed; reserve "Remove" for detaching.
  Music tracks are deleted copies, so "Remove all music" (`YoruApp.java:1042`)
  should read "Delete all music".
- One verb per dialog commit. In use: `Create` / `Save` / `Add` / `Add tag` /
  `Import tasks` / `Add tasks` / `Continue`. Pick **Save** for an editor, **Add**
  for a single new record, **Import** for a batch, **Continue** only for
  `Dialogs.input` prompts.
- The top-bar close button: make the text, tooltip and accessible name all
  "Close vault".

### 6.2 Tasks vs assignments

The Tasks page calls its objects "tasks" ("+ Task" `TasksPanel.java:50`, "New
task" `:411`, the model's `Task`) but its subtitle and empty states say
"assignments": `:48` "ASSIGNMENTS / NOTES / DUE DATES", `:137` "No assignments
yet.", `:144` "Create one, or extract proposals…".

- **Fix:** "task"/"tasks" everywhere user-facing. The subtitle becomes
  "TASKS / NOTES / DUE DATES".

### 6.3 None / nothing / no

"None waiting" (`CollectionPage.java:60`) beside "Nothing waiting"
(`CollectionPage.java:84`) in the two cards that sit side by side. Others:
"No activities yet.", "No tags yet.", "No time recorded in this window.",
"Nothing planned.", "Nothing repeats yet.", "No save yet".

- **Fix:** an `emptyState` headline always starts with "No" + the plural noun +
  "yet." ("No encounters yet.", "No time recorded yet.", "No repeats yet.").
  The compact in-card count keeps its short form ("None waiting") — but then
  `CollectionPage.java:84` must say "None waiting" too, not "Nothing waiting".

### 6.4 Number agreement

`HabitsPanel.java:24` builds `h.streak(today)+" day streak"` with no
pluralisation — a one-day streak reads "1 day streak", a nine-day streak reads
"9 day streak". Everywhere else the app is careful
(`(x==1?"":"s")`: `YoruApp.java:390,486,717,731`, `CollectionPage.java:84`,
`GamePage.java:84`).

- **Fix:** `h.streak(today)+" day"+(h.streak(today)==1?"":"s")+" streak"`, or a
  small `Theme.plural(int,String)` helper used at all the sites above.

### 6.5 Time units

The same quantity is printed six ways: `Analytics.duration` → "01:30:00"
(figures), `YoruApp.java:283` "%.1f hours", `:317` "3h goal", `:390`
"N / 30 min to the next encounter", `CollectionPage.java:61` "/ 30 minutes
toward the next", `GamePage.java:85` "12h 40m played", `YoruApp.java:718`
"under 5 minutes".

- **Fix:** document two forms and use only those — the clock form `1:30:00` for
  a duration you are counting, and `1h 30m` for a duration you are reporting.
  Then "30 min" → "30m", "minutes" → "m", "hours" → "h", and the Today stats
  card stops printing "2.4 hours".

### 6.6 Leading punctuation and casing

Card headers are uppercase but the separator is inconsistent: "ACTIVITY / 52
WEEKS" (`YoruApp.java:291`), "01  /  FOCUS SESSION" (`:213`, double spaces),
"POKÉMON STORAGE  /  …" (`CollectionPage.java:123`), "YOUR GAME  ·  YOUR FILES
·  NEVER BUNDLED" (`GamePage.java:46`); page subtitles use "ASSIGNMENTS / NOTES
/ DUE DATES" (single space) and "LOCAL VAULT   /   …" (`YoruApp.java:207`,
triple).

- **Fix:** one separator, ` · `, single-spaced, for every header and subtitle.

### 6.7 Nav name vs page name

The nav says `tasks`, `data`, `settings`, `collection`, `game`, `schedule`,
`habits`, `today` (`YoruApp.java:68,188`), the heading says `~/ tasks`
(`:162`), and the page's own title says `tasks.board`, `data.overview`,
`settings`, `collection.pc`, `game.campaign`, `schedule.week`, `habits.log`,
`session.workspace`. Three names for one page, and the dotted names are not
consistent either (`data.overview` is a noun; `session.workspace` is not the
page's name at all).

- **Fix:** one `PAGES` table in `YoruApp` — `(nav label, page id, title slug)` —
  so the nav tab, the `~/` heading and the page title come from one row.
  Suggested slugs: `today.session`, `tasks.board`, `habits.log`,
  `schedule.week`, `collection.pc`, `game.campaign`, `data.overview`,
  `settings.options`, `plugins.available`.

### 6.8 The `~/` heading is a third title

`YoruApp.java:159-163` draws `~/ today` at 13/`CYAN` plus the date, above a page
title (`session.workspace`, 25) and a subtitle. Three title lines before any
content, and the date/zone line is the only place the zone appears (`:163`) even
though the Data page and `DateTimeField` label zones again.

- **Fix:** keep the breadcrumb (it is the ricing cue) but demote the page's own
  title to 20 and move the date/zone to the right of the breadcrumb at 11/`MUTED`
  in the same row, so there are two lines, not three.

---

## 7. The study-buddy home (fastfetch-style card)

### 7.1 Where the lead Pokémon is today

- **Today, the partner card** — `YoruApp.today()` `YoruApp.java:259-272`:
  `card()` with `setPreferredSize(new Dimension(315,350))`, placed
  `BorderLayout.EAST` of the `hero` panel. Contents, in order:
  1. `label("PARTNER / <NAME>", 11, PURPLE)` or `PARTNER / NOT CHOSEN YET`
     (`:262`).
  2. `GameView.detail(lead)` at 10/`MUTED` — "Lv 12 · Treecko ♂ · ✦ shiny"
     (`:263`, built in `GameView.detail` `GameView.java:68-76`).
  3. `BuddyScene` (preferred 260×168, `BuddyScene.java:29`) — pixel sprite,
     name plate in the top-left, a dialogue box at the bottom reading
     "Studying together." / "Ready when you are." / "Choose your starter in the
     game.", plus a hop while the timer runs (`:36-43,79-92,110-121`).
  4. `buddyTimeLabel` at 11/`CYAN` (`YoruApp.java:267`), filled by
     `updateBuddyTime()` (`:386-392`) — **the name promises buddy time but the
     text is the encounter countdown**: "2 encounters waiting in Collection" or
     "17 / 30 min to the next encounter".
  5. `N encounters opened` at 11/`TEXT` (`:269`).
  6. A button, "Open the game" or "Open collection" (`:271`).
- The lead itself is `GameView.lead()` (`GameView.java:48-53`): the first
  non-egg party member with a dex number. It is read in exactly one place — this
  card.
- Elsewhere: the Game page hero names the *trainer*, not the lead
  (`GamePage.java:83-85`); the Collection page shows a 128 px `Portrait` and a
  name for whichever Pokémon is *selected* (`CollectionPage.java:179-195`); the
  encounter dialog draws its own scene (`:219`); `TrainerScene` on Today is the
  player, not the lead (`YoruApp.java:234`).

### 7.2 What the card is missing

| Fastfetch field | Available today | Where it would come from |
|---|---|---|
| Pixel-art lead | yes, `GameView.sprite(national, shiny)` (`GameView.java:87-89`), drawn nearest-neighbour | reuse `BuddyScene`'s sprite path at a larger whole-number scale |
| Name | yes, `GameView.name(mon)` (`:56-61`) — nickname or species | as is |
| Level | yes, `mon.level()`, already in `GameView.detail` | as is |
| Species / shiny / gender | yes, `GameView.detail`, `gender`, `shiny` | as is |
| **Time studied together** | **does not exist** | new state — see §7.3 |
| **Streak** | only globally, `Analytics.streak(daily, today)` (`Analytics.java:73`), and per habit (`Model.java:300`) | reuse the global streak for v1, or scope it in the same new record |

### 7.3 What "time studied together" needs

There is no per-Pokémon time anywhere in the model (`Model.Session` is
`(id, activityId, start, end)`, `Model.java:96`; `State` `:317`). Three options,
cheapest first:

1. **Derive from today.** Show today's recorded total and the global streak.
   No schema change, but it says nothing about *this* buddy.
2. **Accumulate per lead, in the vault.** New `Model.Buddy(long seconds, Instant
   since, int bestStreak)` keyed by the lead's identity
   (`Gen3Pokemon.personality` + `otId` — the same pair `StorageScreen.drawMon`
   already uses to compare two mons, `StorageScreen.java:244`). `Tracker` adds
   the session's seconds on clock-out inside the transaction it already uses
   (`Tracker.stop`), and re-keys when the lead changes. Cost: `SCHEMA` 11 → 12 in
   `EncryptedVault.java:15`, a migration branch in the `schema>=12` block
   (`:215` and following), a key in `PortableVault` (`:204` area), plus
   `SchemaTest` / `PortableVaultTest` / `ReliabilityTest` coverage.
3. **Only what the save knows.** `trainer.playTimeMinutes()` (`GamePage.java:82`)
   is whole-game play time, not study time; do not use it.

Recommended: option 2, with option 1 as the line shown before the buddy has any
record ("Together 0m · first session today").

### 7.4 Shape of the card

A fastfetch card is a fixed key column, a value column, and a rule between
blocks. Concretely, replace the six stacked children with:

```
+-----------------------------------------------+
|  [pixel lead, 4x/6x nearest]   TREECKO        |   name 20/TEXT
|                                Lv 12 · ♂      |   meta 12/MUTED
|  ─────────────────────────────────────────    |   1px LINE rule
|  Together      12h 40m                        |   key 11/MUTED, value 12/TEXT
|  Streak        6 days                         |   same
|  Studied       2h 15m today                   |   same
|  Encounters    3 waiting                      |   same
|  ─────────────────────────────────────────    |   1px LINE rule
|  [ Open collection ]                          |   one accent button
+-----------------------------------------------+
```

Implementation notes:

- New `ui/BuddyCard.java` (a `Theme.card()` subclass) so `YoruApp.today()`
  shrinks to one call and `Preview` can render it in isolation.
- Reuse `BuddyScene`'s sprite loading and `VALUE_INTERPOLATION_NEAREST_NEIGHBOR`
  (`BuddyScene.java:60`), but draw at `scale = max(1, min(w/64, h/64))` for a
  64 px cell — do **not** scale the 260×168 scene up.
- Keep `BuddyScene`'s bob/hop (`:79-80`) and drop the dialogue box (`:118-121`);
  its line ("Studying together.") becomes the state shown in the `Together` row.
- The empty state (no starter yet) keeps the current text and CTA
  (`YoruApp.java:262,271`) and draws a placeholder silhouette rather than the
  bare `#N` string `BuddyScene.java:96` falls back to.
- Responsiveness: `setPreferredSize(315,350)` on a `BorderLayout.EAST` child
  breaks below ~900 px. Put the hero in a `GridLayout(1,2,16,0)` like
  `CollectionPage.java:44`, or reflow the card under the focus card below a
  width threshold.
- Rename `buddyTimeLabel` / `updateBuddyTime` (`YoruApp.java:23,267,386`) to
  `encounterLabel` / `updateEncounterLine` — once a real "time together" row
  exists, a method named `updateBuddyTime` that writes an encounter countdown is
  a trap for the next reader.
- Tests: `BuddySceneTest` and `BuddyPreview` already exist under
  `src/test/java/dev/yoru/ui/`; add a `BuddyCard` case there, and add the card to
  `Preview`'s fixture so the four-theme image review covers it.

---

## 8. Also in scope for #47 (from the ticket text)

### 8.1 Themes on well-known ricing palettes

`ThemeId` is `{MIDNIGHT, EMBER, SAKURA, LINEN}` (`Model.java:48`) with
`describe()` copy in `Theme.java:78-85`: terminal blue, amber CRT, cherry
blossom, warm paper. None is a named ricing palette, and the ticket asks for
"themes on well-known ricing palettes".

- **Fix:** keep four, but anchor each to a named scheme with a documented source
  and a credit line in `describe()` — for example a Catppuccin-style dark, a
  Gruvbox-style warm dark, a Tokyo-Night-style blue dark and a Rosé-Pine-Dawn
  style light. Keep the record shape (`Palette` with 11 colours + `heat[]`,
  `Theme.java:39-41`) so `Heatmap`, `FocusBars`, `ScheduleGrid` and
  `ContrastTest` need no change beyond the values.
- Cost to note before starting: `ThemeId` is persisted by name
  (`EncryptedVault.java:262`, `PortableVault.java:204`), so renaming the
  constants needs a `valueOf` migration or the old names kept as aliases.
  `SchemaTest`, `CoreTest`, `PortableVaultTest`, `RecurringTest`, `RouteTest`,
  `FocusMixTest`, `ScheduleUiTest`, `NotionImportTest` and `Preview` all
  reference `ThemeId`. Changing only the display strings (keeping the enum
  constants) is the cheap path.
- `ContrastTest` gates the palette, so pick the light theme's `MUTED` and `LINE`
  against `PANEL` deliberately.

### 8.2 Hide unfinished integrations until after 1.0

| Thing | Where | Note |
|---|---|---|
| Plugins page | `YoruApp.plugins()` `:1112-1148`; entered from Settings → "Integrations" card → "Plugins & integrations" `:1084` | not a nav tab, but one click from Settings |
| AI file import | Tasks toolbar "Import with API…" `TasksPanel.java:53`, `importFile()` `:454-482` | a live OpenAI call when used |
| Paste proposals | Tasks toolbar "Paste task proposals…" `TasksPanel.java:52` | local, no network (`TaskPastePanel.java:47`) |
| Privacy card | Tasks page "OPTIONAL ONLINE ASSISTANCE" `TasksPanel.java:76-78` | belongs to the AI path |
| Notion import | Tasks toolbar "Import Notion export…" `TasksPanel.java:54` | local; #48's own ticket, keep |

- **Fix:** one feature flag (a package-private boolean in `YoruApp` is enough)
  that removes the "Import with API…" button, the privacy card, the Plugins
  entry in Settings and the `plugins()` page branch, leaving the paste path
  (fully local, and the one the tests drive through `TaskPastePanel`) and the
  Notion import in place. Delete — do not comment out — so `importFile` and
  `YoruApp.plugins()` leave the tree when the flag goes, and re-add them from
  git when the integrations land.
- Care: the import buttons set no `setName` (`TasksPanel.java:52-54`), so
  `TaskBoardTest` / `UiTest` find them by text — check before removing.

### 8.3 The image-review harness

`Preview.java` (`src/test/java/dev/yoru/ui/Preview.java`) renders the 8 nav
pages plus `tasks-calendar.png` and `collection-arranging.png`, and takes the
theme as its **fourth** argument (`Preview.java:54`) — one theme per run — and
writes `out.resolve(page.toLowerCase()+".png")` with no theme in the name
(`:153`).

- **Fix (small, unblocks the ticket's "Done"):** loop `ThemeId.values()` inside
  `main`, writing `<theme>-<page>.png`; add the Plugins page and the new
  `BuddyCard` to the fixture; render each page at the window minimum (900×640)
  as well as 1280×1000 so reflow bugs (`hero` at `YoruApp.java:209`,
  `CollectionPage`'s `GridLayout(1,2)` `:44`) show up.
- `DialogPreview`, `TaskPastePreview`, `NotionImportPreview`, `BuddyPreview`,
  `LogoPreview` and `RoutePreview` already exist and should join the same loop.

---

## 9. Biggest wins first (top 10)

1. **Stop focusing the destructive button.** `Dialogs.show` passes `options[0]`
   as `initialValue` (`Dialogs.java:37`), so Delete / Remove / Reset / Replace
   holds focus and Enter fires it. Pass Cancel for destructive dialogs. One
   line, prevents data loss.
2. **Give the whole app a focus ring.** `setFocusPainted(false)`
   (`Theme.java:269`) plus replaced `LineBorder`s (`:210,196,315`) mean no
   button, field or combo shows focus. One shared `FocusListener` in `Theme`.
3. **One page header.** Titles 25/26/28 and subtitles 11/13 across nine pages,
   and Habits has no gap at all (`HabitsPanel.java:15-16`). Add
   `Theme.pageHeader(title, subtitle)` and call it from all of them.
4. **One empty-state component.** Five surfaces have none (Today's focus card,
   the heat map, the schedule grid, both Data tables) and six use bare muted
   lines at three sizes. Add `Theme.emptyState(headline, detail, action)`.
5. **Make the buddy card real.** §7: keep `GameView.lead` + sprite + name +
   level, add "Together" and "Streak" rows, drop the `BuddyScene` dialogue box,
   rename the misleading `updateBuddyTime`, and make the card responsive.
6. **Hide the unfinished integrations.** Remove the API import, the privacy card
   and the Plugins page behind one flag (§8.2), keeping the local paste and
   Notion paths.
7. **Control tokens.** One button padding (`7,13`), one control height (32), one
   radius (8, or square everything), one selected state, one
   `Theme.plainTable`. Today: six row layouts, four heights, three radii, four
   ways to say "selected", three table stylings.
8. **Wording pass on the same action.** "+ Add time"/"+ Log time",
   "+ Block"/"+ Plan block", "Remove"/"Delete", "tasks"/"assignments",
   "None waiting"/"Nothing waiting", six time-unit forms, one ` · ` separator.
9. **Fix the two structural page bugs.** `HabitsPanel` nests a `JScrollPane`
   inside the one `YoruApp.showPage` already provides (`:64`); and the two
   window minimum sizes disagree (`YoruApp.java:55` vs `:1191`).
10. **Make #47 reviewable, and pick the palettes.** Loop all four themes in
    `Preview` with the theme in the filename (§8.3), then move the four palettes
    onto named, credited ricing schemes (§8.1) — changing the display strings
    only, keeping `ThemeId`'s constants so no vault migration is needed.
