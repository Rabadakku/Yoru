# Pages

Open **Pages**, then use the explorer’s new-page and new-folder controls. Pages
are encrypted with the rest of the vault. Rename, move, duplicate and trash
commands are in the explorer context menu. Restore deleted notes from Trash;
permanently emptying it creates a vault backup first.

Write Markdown in the source editor and use **Read / Edit** to switch views.
Edits autosave after a pause. A failed save keeps the draft open and prevents
leaving or closing until it saves successfully. Undo and redo survive switching
between open pages and reading mode, but do not survive restarting the app.

Use **Open…** for a quick title search and **Search…** for text across live pages.
**Find / Replace** works within the current page. Type `[[` for page completion;
`[[Page#` offers headings and `[[Page#^` offers block IDs. Following an unresolved
simple page name offers to create it. Rename/move operations preserve the target
of existing page links. **Details** shows the outline, backlinks and linked
tasks, with controls to link, create and complete tasks. The Tasks context menu
also links or opens pages.

**Import…** accepts a UTF-8 `.md` file or directory and puts it in a fresh
Imported notes folder. Hidden configuration files and non-Markdown attachments
are skipped; symlinks and invalid input are rejected. Import commits once, so a
failed save leaves the vault unchanged. **Export…** writes live pages into a new
Yoru Pages directory, preserving folder structure without overwriting existing
files. Exported Markdown is plain text, not encrypted; trash is excluded.

This checkpoint provides a source editor and reading view. It does not yet
provide live preview, attachment storage/embeds, page-history snapshots,
properties editing, tree-operation undo, daily notes/templates, persistent tab
and layout preferences, split panes or canvas. These remain tracked in #46.
Markdown syntax the reader does not render remains intact in the source.

Each page is limited to one million characters. Imports are bounded to 25 MB
of Markdown and 20,000 entries; the existing encrypted vault has a 31 MB total
plaintext limit shared with other data. Saving still encrypts the whole vault;
this is not yet a storage design for thousands of very large pages.
