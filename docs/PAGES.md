# Pages

Open **Pages**, then use the explorer’s new-page and new-folder controls. Pages
are encrypted with the rest of the vault. Rename, move, duplicate and trash
commands are in the explorer context menu. Restore deleted notes from Trash;
permanently emptying it creates a vault backup first.

The page on screen is named by the title field at the top: type in it to rename
the page, which rewrites every link that pointed at it. Under the title is the
folder it lives in. Each open page keeps a tab; a tab closes on its own × or
with Cmd/Ctrl-W, and back and forward (Cmd/Ctrl-[ and Cmd/Ctrl-]) walk the pages
visited.

Write Markdown in the source editor and use **Read** and **Edit** to switch views.
Edits autosave after a pause. A failed save keeps the draft open and prevents
leaving or closing until it saves successfully. Undo and redo survive switching
between open pages and reading mode, but do not survive restarting the app.

Cmd/Ctrl-O opens a page by name, Cmd/Ctrl-Shift-F searches the text of every
live page, Cmd/Ctrl-N makes a page and Cmd/Ctrl-E switches between reading and
editing; the ⋯ menu holds the same commands, with import and export.
**Find / Replace** works within the current page. Type `[[` for page completion;
`[[Page#` offers headings and `[[Page#^` offers block IDs. Following an unresolved
simple page name offers to create it. Rename/move operations preserve the target
of existing page links. The connections panel on the right shows the page's outline, the pages that link
to it and its linked tasks, with controls to link, create and complete tasks. It
is hidden and shown from the panel button in the header. The Tasks context menu
also links or opens pages.

**Import…** accepts a UTF-8 `.md` file or directory and puts it in a fresh
Imported notes folder. Hidden configuration files and non-Markdown attachments
are skipped; symlinks and invalid input are rejected. Import commits once, so a
failed save leaves the vault unchanged. **Export…** writes live pages into a new
Yoru Pages directory, preserving folder structure without overwriting existing
files. Exported Markdown is plain text, not encrypted; trash is excluded.

Reading view draws an embedded page (`![[Page]]`, or `![[Page#Heading]]` for one
section) inside the page that names it, three deep; a page that embeds itself,
or a ring of pages that embed each other, is drawn once and then shown as a link.

This checkpoint provides a source editor and reading view. It does not yet
provide live preview, attachment storage, page-history snapshots,
properties editing, tree-operation undo, daily notes/templates, persistent tab
and layout preferences, split panes or canvas. These remain tracked in #46.
Markdown syntax the reader does not render remains intact in the source.

Each page is limited to one million characters. Imports are bounded to 25 MB
of Markdown and 20,000 entries; the existing encrypted vault has a 31 MB total
plaintext limit shared with other data. Saving still encrypts the whole vault;
this is not yet a storage design for thousands of very large pages.
