# Yoru 1.0.13

Charts that say what they mean, and a button that had slipped out of reach.

- **Fixed: Edit timer at the smallest window.** At the window's minimum width,
  Today's Edit timer button was drawn below the row that held it — visible as a
  sliver under Clock out, and impossible to click. Any row of controls that
  wraps now takes the height of the lines it wraps onto, on every page and at
  every text size.
- **Habits:** a daily habit's days stand in seven weekday columns, four weeks
  deep, under the initials of the days. Two rows of fourteen cells said how
  many days were checked off and nothing about which; a gap on weekends now
  reads as a gap on weekends. Weeks break on the day set in Settings →
  Tracking, and the days remaining in this week are left blank rather than
  offered for checking off early.
- **Data:** the fourteen-day chart is drawn against your daily goal, with a
  dashed line across the plot where the goal falls, so the bars answer "did I
  get there" without arithmetic. The plot keeps its height whatever the
  fortnight held, days with nothing recorded no longer caption their hairline
  "0m", and today's date is the one in full ink.
- **Today:** the week behind the seven-day figure is a chart with its days
  named and today in full ink, rather than seven wide chips. The agenda's last
  block is no longer ruled off from the edge of its own card.
- **Heat map:** the year now breaks its weeks where the week-start setting says
  rather than always on a Monday, so it agrees with the task calendar and the
  habit grids. Each month is named once, where there is room for the word, and
  the days still to come are left unpainted instead of cut out of the last
  column.
- **Tasks:** a row lights up under the pointer, so which row a click will land
  on is visible before the click.
- **Collection and Settings:** the line that says what the vault holds stands
  on the card it vouches for, and Appearance writes its captions over its
  controls as the rest of Settings already did.

No vault schema changes. Existing study history, settings and game saves are
preserved. Yoru continues to ship no game, game artwork or music.

Validation includes the full isolated test suite, 100 page renders across five
themes and two window sizes reviewed by eye, and long-name layout checks at
100%, 125%, 150% and 200% text. New checks cover the wrapping-row fix — every
page's rows contain their controls at the smallest window, and a row given less
width asks for more height — and the weekday columns and week start for all
seven possible week starts. Clean-machine installation and full native gameplay
have not been revalidated for this UI release.
