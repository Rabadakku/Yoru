# Yoru 1.0.18

## A quieter workspace

- A collapsible sidebar replaces the top navigation bar. All seven destinations
  have drawn line icons; the sidebar remembers its visibility on this computer.
- System typography, sentence-case headings, softer surfaces and hairline
  separators make the workspace easier to scan. Linen is now a clean white
  theme; Midnight uses neutral charcoal.
- macOS gets the real system menu bar, standard editing and navigation shortcuts,
  and a transparent unified title bar. Windows and Linux keep an in-window menu.
- Settings can follow macOS light/dark appearance and accent. This is optional,
  stored on the computer, and checked in the background every 30 seconds. The
  five vault themes remain available; choosing one switches off automatic mode.
- At narrow widths, Pages keeps a useful writing area by showing the explorer
  or the connections panel, rather than squeezing the editor between both.
- Today statistics and task rows use fewer boxes, and task titles wrap safely
  beside their controls at enlarged text sizes.

This is the shared visual foundation for #63. Further page-specific refinement,
custom sheets/popovers, disclosure animations and final owner visual approval
remain on that ticket. No vault format change or data migration is needed.

Validation: full isolated test suite; all-page previews in five themes and two
window sizes; dialog previews; contrast and text layout checks through 200%;
and an on-device macOS check of the native menu bar and title-bar properties.
