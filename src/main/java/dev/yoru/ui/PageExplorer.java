package dev.yoru.ui;

import dev.yoru.application.Pages;
import dev.yoru.domain.Model.*;
import dev.yoru.pages.Links;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import static dev.yoru.ui.Theme.*;

/**
 * The folders and pages, as a tree: the file explorer of the Pages screen.
 *
 * It works the way a file manager does. A click opens a page or folds a
 * folder; things drag onto folders to move, or onto the trash; F2 renames in
 * place; Delete moves to the trash, and the trash gives everything back until
 * it is emptied. A right click offers all of it. What is folded, how it is
 * sorted and what is filtered survive the tree being rebuilt after a change.
 */
final class PageExplorer extends JPanel {
    /** What the explorer needs from the screen it is on. */
    interface Host {
        Pages pages();
        Component owner();
        /** Runs a change to the tree, reporting a failure, then brings everything up to date. */
        void run(Shell.Work change);
        void open(UUID page);
        void newPage(UUID folder);
        /** The page open in the editor, drawn as current. */
        UUID current();
    }

    enum Kind { FOLDER, PAGE, TRASH }

    /** One row: a folder, a page or the trash itself. The name is what an in-place rename starts from. */
    record Item(Kind kind, UUID id, String name, boolean trashed) {
        @Override public String toString() { return name; }
    }

    enum Sort {
        NAME("Name (A to Z)"), EDITED("Last edited"), CREATED("Newest first");
        final String label;
        Sort(String label) { this.label = label; }
    }

    /** Kept across rebuilds of the window, like the tasks board's view. */
    private Sort sort = Sort.NAME;
    private final Set<UUID> expanded = new HashSet<>();

    private final Host host;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root) {
        // An in-place rename arrives here; it renames through the service
        // instead of changing the row, which the rebuild that follows redraws.
        @Override public void valueForPathChanged(TreePath path, Object value) {
            var item = item(path.getLastPathComponent());
            String name = String.valueOf(value).strip();
            if (item == null || name.isEmpty() || name.equals(item.name())) return;
            SwingUtilities.invokeLater(() -> host.run(() -> {
                if (item.kind() == Kind.PAGE) host.pages().renamePage(item.id(), name);
                else host.pages().renameFolder(item.id(), name);
            }));
        }
    };
    private final JTree tree = new JTree(model) {
        @Override public boolean isPathEditable(TreePath path) {
            var item = item(path.getLastPathComponent());
            return item != null && item.kind() != Kind.TRASH && !item.trashed();
        }
        @Override public String getToolTipText(MouseEvent e) {
            var path = getPathForLocation(e.getX(), e.getY());
            var item = path == null ? null : item(path.getLastPathComponent());
            return item == null || item.kind() == Kind.TRASH ? null : item.name();
        }
    };
    private final JTextField filter = hintField("Filter pages");
    private Notes notes = Notes.empty();
    private boolean syncing;

    PageExplorer(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);
        setName("pages.explorer");

        var tools = new JPanel(new FlowLayout(FlowLayout.LEFT, SPACE_XS, 0));
        tools.setOpaque(false);
        tools.add(icon(Glyphs.Kind.PAGE_PLUS, "New page", "pages.newPage", () -> host.newPage(targetFolder())));
        tools.add(icon(Glyphs.Kind.FOLDER_PLUS, "New folder", "pages.newFolder", () -> newFolder(targetFolder())));
        var sortButton = icon(Glyphs.Kind.SORT, "Sort", "pages.sort", null);
        sortButton.addActionListener(e -> sortMenu().show(sortButton, 0, sortButton.getHeight()));
        tools.add(sortButton);
        tools.add(icon(Glyphs.Kind.COLLAPSE, "Collapse all", "pages.collapse", this::collapseAll));
        filter.setName("pages.filter");
        filter.getAccessibleContext().setAccessibleName("Filter pages by title");
        filter.setToolTipText("Show only pages whose title holds these words");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        filter.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) filter.setText("");
                else if (e.getKeyCode() == KeyEvent.VK_DOWN || e.getKeyCode() == KeyEvent.VK_ENTER) {
                    tree.requestFocusInWindow();
                    if (tree.getSelectionCount() == 0 && tree.getRowCount() > 0) tree.setSelectionRow(firstPageRow());
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) openSelection();
                }
            }
        });
        var head = new JPanel(new BorderLayout(0, SPACE_SM));
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(SPACE_MD, SPACE_MD, SPACE_SM, SPACE_MD));
        head.add(tools, BorderLayout.NORTH);
        head.add(filter, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        tree.setName("pages.tree");
        tree.getAccessibleContext().setAccessibleName("Pages and folders");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(1);
        tree.setRowHeight(0);
        tree.setOpaque(false);
        tree.setBackground(PANEL);
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(tree);
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
            ui.setExpandedIcon(Glyphs.of(Glyphs.Kind.CHEVRON_DOWN, MUTED));
            ui.setCollapsedIcon(Glyphs.of(Glyphs.Kind.CHEVRON_RIGHT, MUTED));
            ui.setLeftChildIndent(grow(SPACE_SM));
            ui.setRightChildIndent(grow(SPACE_MD));
        }
        var renderer = new Renderer();
        tree.setCellRenderer(renderer);
        var field = styleInput(new JTextField());
        tree.setCellEditor(new DefaultTreeCellEditor(tree, renderer, new DefaultCellEditor(field)) {
            // Only F2 or Rename starts a rename; a slow second click opens, as it does everywhere else here.
            @Override public boolean isCellEditable(EventObject e) { return e == null && super.isCellEditable(null); }
        });
        tree.setEditable(true);
        tree.setInvokesStopCellEditing(true);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override public void treeExpanded(TreeExpansionEvent e) { remember(e.getPath(), true); }
            @Override public void treeCollapsed(TreeExpansionEvent e) { remember(e.getPath(), false); }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) { popup(e); return; }
                if (!SwingUtilities.isLeftMouseButton(e) || e.isShiftDown() || (e.getModifiersEx() & menuKey()) != 0) return;
                var path = tree.getPathForLocation(e.getX(), e.getY());
                var item = path == null ? null : item(path.getLastPathComponent());
                if (item != null && item.kind() == Kind.PAGE && !item.trashed()) host.open(item.id());
            }
        });
        keys();
        if (!GraphicsEnvironment.isHeadless()) tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new Transfer());

        var scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(SPACE_XL);
        // Right-clicking the space below the rows offers what the toolbar does.
        scroll.getViewport().addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) background(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) background(e); }
        });
        add(scroll, BorderLayout.CENTER);
    }

    private static int menuKey() { return TextInput.menuKey(); }

    /** A drawn icon as a quiet square button, named for tests and screen readers. */
    static JButton icon(Glyphs.Kind kind, String what, String name, Runnable action) {
        var b = button("", action == null ? () -> { } : action);
        b.setIcon(Glyphs.of(kind, MUTED));
        b.setBackground(PANEL);
        b.setBorder(new EmptyBorder(SPACE_XS + 2, SPACE_XS + 2, SPACE_XS + 2, SPACE_XS + 2));
        b.setToolTipText(what);
        b.setName(name);
        b.getAccessibleContext().setAccessibleName(what);
        return b;
    }

    JTree tree() { return tree; }

    // ---------------------------------------------------------------- the tree

    /** Brings the tree up to date with the vault, keeping what was folded, selected and scrolled. */
    void sync(Notes next) {
        notes = next;
        rebuild();
    }

    void rebuild() {
        var selected = new ArrayList<Item>();
        for (var path : Optional.ofNullable(tree.getSelectionPaths()).orElse(new TreePath[0])) {
            var item = item(path.getLastPathComponent());
            if (item != null) selected.add(item);
        }
        syncing = true;
        try {
            if (tree.isEditing()) tree.cancelEditing();
            root.removeAllChildren();
            String query = filter.getText().strip().toLowerCase(Locale.ROOT);
            var folders = new HashMap<UUID, List<Folder>>();
            var pages = new HashMap<UUID, List<Page>>();
            var trashedFolders = new ArrayList<Folder>();
            var trashedPages = new ArrayList<Page>();
            var folderById = new HashMap<UUID, Folder>();
            for (var f : notes.folders()) folderById.put(f.id(), f);
            for (var f : notes.folders()) {
                if (f.trashed()) {
                    // A trashed folder shows at the trash's top unless it went with its parent.
                    var parent = f.parentId() == null ? null : folderById.get(f.parentId());
                    if (parent == null || !parent.trashed()) trashedFolders.add(f);
                    else folders.computeIfAbsent(f.parentId(), k -> new ArrayList<>()).add(f);
                } else folders.computeIfAbsent(f.parentId(), k -> new ArrayList<>()).add(f);
            }
            for (var p : notes.pages()) {
                var parent = p.folderId() == null ? null : folderById.get(p.folderId());
                if (p.trashed() && (parent == null || !parent.trashed())) trashedPages.add(p);
                else if (!p.trashed() || parent.trashed()) pages.computeIfAbsent(p.folderId(), k -> new ArrayList<>()).add(p);
            }
            fill(root, null, folders, pages, query, false);
            int inTrash = trashedFolders.size() + trashedPages.size();
            if (inTrash > 0 && query.isEmpty()) {
                var trash = new DefaultMutableTreeNode(new Item(Kind.TRASH, null, "Trash", false));
                sortFolders(trashedFolders);
                for (var f : trashedFolders) {
                    var node = new DefaultMutableTreeNode(new Item(Kind.FOLDER, f.id(), f.name(), true));
                    fill(node, f.id(), folders, pages, "", true);
                    trash.add(node);
                }
                sortPages(trashedPages);
                for (var p : trashedPages) trash.add(new DefaultMutableTreeNode(new Item(Kind.PAGE, p.id(), p.title(), true)));
                root.add(trash);
            }
            model.reload();
            // Folded as they were; while filtering, everything that matched is shown.
            for (int row = 0; row < tree.getRowCount(); row++) {
                var path = tree.getPathForRow(row);
                var item = item(path.getLastPathComponent());
                if (item == null || item.kind() == Kind.PAGE) continue;
                boolean open = !query.isEmpty() || expanded.contains(item.kind() == Kind.TRASH ? TRASH_ID : item.id());
                if (open) tree.expandPath(path);
            }
            var keep = new ArrayList<TreePath>();
            for (var item : selected) { var path = pathOf(item.kind(), item.id()); if (path != null) keep.add(path); }
            tree.setSelectionPaths(keep.toArray(TreePath[]::new));
        } finally {
            syncing = false;
        }
        tree.repaint();
    }

    /** The trash's own place in the remembered folds. */
    private static final UUID TRASH_ID = new UUID(0, 0);

    /** Adds a folder's contents: its folders, then its pages. Returns whether anything matched the filter. */
    private boolean fill(DefaultMutableTreeNode into, UUID folder, Map<UUID, List<Folder>> folders, Map<UUID, List<Page>> pages,
                         String query, boolean trashed) {
        boolean any = false;
        var subfolders = new ArrayList<>(folders.getOrDefault(folder, List.of()));
        sortFolders(subfolders);
        for (var f : subfolders) {
            if (f.trashed() != trashed) continue;
            var node = new DefaultMutableTreeNode(new Item(Kind.FOLDER, f.id(), f.name(), f.trashed()));
            boolean inside = fill(node, f.id(), folders, pages, query, trashed);
            if (query.isEmpty() || inside || f.name().toLowerCase(Locale.ROOT).contains(query)) { into.add(node); any = true; }
        }
        var here = new ArrayList<>(pages.getOrDefault(folder, List.of()));
        sortPages(here);
        for (var p : here) {
            if (!query.isEmpty() && !p.title().toLowerCase(Locale.ROOT).contains(query)) continue;
            into.add(new DefaultMutableTreeNode(new Item(Kind.PAGE, p.id(), p.title(), p.trashed())));
            any = true;
        }
        return any;
    }

    private static final Comparator<String> BY_NAME = String.CASE_INSENSITIVE_ORDER;

    private static void sortFolders(List<Folder> folders) { folders.sort(Comparator.comparing(Folder::name, BY_NAME)); }

    private void sortPages(List<Page> pages) {
        pages.sort(switch (sort) {
            case NAME -> Comparator.comparing(Page::title, BY_NAME);
            case EDITED -> Comparator.comparing(Page::updatedAt).reversed().thenComparing(Page::title, BY_NAME);
            case CREATED -> Comparator.comparing(Page::createdAt).reversed().thenComparing(Page::title, BY_NAME);
        });
    }

    private void remember(TreePath path, boolean open) {
        if (syncing || !filter.getText().isBlank()) return;
        var item = item(path.getLastPathComponent());
        if (item == null || item.kind() == Kind.PAGE) return;
        UUID id = item.kind() == Kind.TRASH ? TRASH_ID : item.id();
        if (open) expanded.add(id); else expanded.remove(id);
    }

    private void collapseAll() {
        expanded.clear();
        for (int row = tree.getRowCount() - 1; row >= 0; row--) tree.collapseRow(row);
    }

    static Item item(Object node) {
        return node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Item item ? item : null;
    }

    private TreePath pathOf(Kind kind, UUID id) {
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            var item = item(node);
            if (item != null && item.kind() == kind && Objects.equals(item.id(), id)) return new TreePath(node.getPath());
        }
        return null;
    }

    private int firstPageRow() {
        for (int row = 0; row < tree.getRowCount(); row++) {
            var item = item(tree.getPathForRow(row).getLastPathComponent());
            if (item != null && item.kind() == Kind.PAGE) return row;
        }
        return 0;
    }

    /** Shows a page's row, unfolding the folders above it, and selects it. */
    void reveal(UUID page) {
        var path = pathOf(Kind.PAGE, page);
        if (path == null) return;
        for (var up = path.getParentPath(); up != null && up.getPathCount() > 1; up = up.getParentPath()) tree.expandPath(up);
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    /** Starts renaming a folder or page in place. */
    void rename(Kind kind, UUID id) {
        var path = pathOf(kind, id);
        if (path == null) return;
        for (var up = path.getParentPath(); up != null && up.getPathCount() > 1; up = up.getParentPath()) tree.expandPath(up);
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
        tree.requestFocusInWindow();
        tree.startEditingAtPath(path);
    }

    /** Where a new page or folder goes: the selected folder, the selected page's folder, or the top. */
    UUID targetFolder() {
        var path = tree.getSelectionPath();
        var item = path == null ? null : item(path.getLastPathComponent());
        if (item == null || item.trashed() || item.kind() == Kind.TRASH) {
            var current = host.current();
            return current == null ? null : notes.page(current).filter(p -> !p.trashed()).map(Page::folderId).orElse(null);
        }
        if (item.kind() == Kind.FOLDER) return item.id();
        return notes.page(item.id()).map(Page::folderId).orElse(null);
    }

    void newFolder(UUID parent) {
        UUID[] made = {null};
        if (parent != null) expanded.add(parent);
        host.run(() -> made[0] = host.pages().createFolder(parent, host.pages().freeFolderName(parent, "New folder")).id());
        if (made[0] != null) SwingUtilities.invokeLater(() -> rename(Kind.FOLDER, made[0]));
    }

    // ---------------------------------------------------------------- selection

    private List<Item> selection() {
        var out = new ArrayList<Item>();
        for (var path : Optional.ofNullable(tree.getSelectionPaths()).orElse(new TreePath[0])) {
            var item = item(path.getLastPathComponent());
            if (item != null) out.add(item);
        }
        return out;
    }

    private static List<UUID> ids(List<Item> items, Kind kind) {
        return items.stream().filter(i -> i.kind() == kind).map(Item::id).toList();
    }

    private void openSelection() {
        var items = selection();
        if (items.size() != 1) return;
        var item = items.getFirst();
        if (item.kind() == Kind.PAGE && !item.trashed()) host.open(item.id());
        else if (item.kind() != Kind.PAGE) {
            var path = tree.getSelectionPath();
            if (tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path);
        }
    }

    private void trashSelection() {
        var items = selection().stream().filter(i -> i.kind() != Kind.TRASH).toList();
        if (items.isEmpty()) return;
        var live = items.stream().filter(i -> !i.trashed()).toList();
        if (!live.isEmpty()) host.run(() -> host.pages().trash(ids(live, Kind.PAGE), ids(live, Kind.FOLDER)));
        else deleteForever(items);
    }

    private void deleteForever(List<Item> items) {
        String what = items.size() == 1 ? "“" + items.getFirst().name() + "”" : plural(items.size(), "item");
        boolean folder = items.stream().anyMatch(i -> i.kind() == Kind.FOLDER);
        if (!Dialogs.confirmDestructive(host.owner(), "Delete " + what + " forever?\n\n"
                + (folder ? "Everything inside goes with it. " : "") + "This cannot be undone; your vault is backed up first.",
                "Delete forever", "Delete forever")) return;
        host.run(() -> host.pages().purge(ids(items, Kind.PAGE), ids(items, Kind.FOLDER)));
    }

    private void restore(List<Item> items) {
        host.run(() -> {
            for (var item : items) {
                if (item.kind() == Kind.PAGE) host.pages().restorePage(item.id());
                else if (item.kind() == Kind.FOLDER) host.pages().restoreFolder(item.id());
            }
        });
    }

    private void emptyTrash() {
        if (!Dialogs.confirmDestructive(host.owner(), "Empty the trash?\n\nEverything in it is deleted forever. Your vault is backed up first.",
                "Empty trash", "Empty trash")) return;
        host.run(() -> host.pages().emptyTrash());
    }

    private void moveTo(List<Item> items) {
        var choices = new ArrayList<FolderChoice>();
        choices.add(new FolderChoice(null, "Top level"));
        var moving = new HashSet<>(ids(items, Kind.FOLDER));
        for (var f : notes.folders()) {
            if (f.trashed() || inside(f.id(), moving)) continue;
            choices.add(new FolderChoice(f.id(), folderPath(f)));
        }
        choices.sort(Comparator.comparing((FolderChoice c) -> c.id() != null).thenComparing(FolderChoice::path, BY_NAME));
        var picked = Dialogs.select(host.owner(), "Move to", "Move", choices);
        if (picked == null) return;
        if (picked.id() != null) expanded.add(picked.id());
        host.run(() -> host.pages().move(ids(items, Kind.PAGE), ids(items, Kind.FOLDER), picked.id()));
    }

    private record FolderChoice(UUID id, String path) {
        @Override public String toString() { return path; }
    }

    private String folderPath(Folder f) {
        var parts = new ArrayDeque<String>();
        for (var at = f; at != null; at = at.parentId() == null ? null : notes.folder(at.parentId()).orElse(null)) parts.push(at.name());
        return String.join(" / ", parts);
    }

    /** Whether a folder is one of these or sits somewhere under one. */
    private boolean inside(UUID folder, Set<UUID> any) {
        for (var at = notes.folder(folder).orElse(null); at != null; at = at.parentId() == null ? null : notes.folder(at.parentId()).orElse(null))
            if (any.contains(at.id())) return true;
        return false;
    }

    private void copyLink(UUID page) {
        var target = notes.page(page).orElse(null);
        if (target == null) return;
        var text = "[[" + new Links.Resolver(notes).linkText(target, null) + "]]";
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void duplicate(UUID page) {
        var source = notes.page(page).orElse(null);
        if (source == null) return;
        UUID[] made = {null};
        host.run(() -> made[0] = host.pages().createPage(source.folderId(),
            host.pages().freeTitle(source.folderId(), source.title() + " copy"), source.body()).id());
        if (made[0] != null) host.open(made[0]);
    }

    // ---------------------------------------------------------------- menus and keys

    private void keys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open", this::openSelection);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", () -> {
            var items = selection();
            if (items.size() == 1 && items.getFirst().kind() != Kind.TRASH && !items.getFirst().trashed())
                rename(items.getFirst().kind(), items.getFirst().id());
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "trash", this::trashSelection);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, menuKey()), "trash2", this::trashSelection);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "trash3", this::trashSelection);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "menu", () -> {
            var path = tree.getLeadSelectionPath();
            var at = path == null ? null : tree.getPathBounds(path);
            if (at != null) menuFor(selection()).show(tree, at.x + SPACE_LG, at.y + at.height);
        });
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(key, "yoru." + name);
        tree.getActionMap().put("yoru." + name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (!tree.isEditing()) action.run(); }
        });
    }

    private void popup(MouseEvent e) {
        var path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) { background(e); return; }
        if (!tree.isPathSelected(path)) tree.setSelectionPath(path);
        menuFor(selection()).show(tree, e.getX(), e.getY());
    }

    private void background(MouseEvent e) {
        tree.clearSelection();
        var menu = Menus.popup();
        menu.add(Menus.item("New page", "pages.menu.newPage", true, () -> host.newPage(null), null));
        menu.add(Menus.item("New folder", "pages.menu.newFolder", true, () -> newFolder(null), null));
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private JPopupMenu sortMenu() {
        var menu = Menus.popup();
        for (var s : Sort.values())
            menu.add(Menus.item((s == sort ? "✓ " : "    ") + s.label, "pages.sort." + s.name().toLowerCase(Locale.ROOT), true,
                () -> { sort = s; rebuild(); }, null));
        return menu;
    }

    /** What a right click on the selection offers. */
    JPopupMenu menuFor(List<Item> items) {
        var menu = Menus.popup();
        if (items.isEmpty()) return menu;
        var one = items.size() == 1 ? items.getFirst() : null;
        if (one != null && one.kind() == Kind.TRASH) {
            menu.add(Menus.item("Empty trash…", "pages.menu.empty", true, this::emptyTrash, null));
            return menu;
        }
        var chosen = items.stream().filter(i -> i.kind() != Kind.TRASH).toList();
        if (chosen.stream().anyMatch(Item::trashed)) {
            var trashed = chosen.stream().filter(Item::trashed).toList();
            menu.add(Menus.item("Restore", "pages.menu.restore", true, () -> restore(trashed), null));
            menu.addSeparator();
            menu.add(Menus.item("Delete forever…", "pages.menu.purge", true, () -> deleteForever(trashed), null));
            return menu;
        }
        if (one != null && one.kind() == Kind.FOLDER) {
            menu.add(Menus.item("New page", "pages.menu.newPage", true, () -> host.newPage(one.id()), null));
            menu.add(Menus.item("New folder", "pages.menu.newFolder", true, () -> newFolder(one.id()), null));
            menu.addSeparator();
        }
        if (one != null && one.kind() == Kind.PAGE) {
            menu.add(Menus.item("Open", "pages.menu.open", true, () -> host.open(one.id()), null));
            menu.add(Menus.item("Copy link", "pages.menu.copyLink", true, () -> copyLink(one.id()), null));
            menu.add(Menus.item("Make a copy", "pages.menu.duplicate", true, () -> duplicate(one.id()), null));
            menu.addSeparator();
        }
        if (one != null) menu.add(Menus.item("Rename", "pages.menu.rename", true, () -> rename(one.kind(), one.id()), null));
        menu.add(Menus.item("Move to…", "pages.menu.move", true, () -> moveTo(chosen), null));
        menu.addSeparator();
        menu.add(Menus.item("Move to trash", "pages.menu.trash", true,
            () -> host.run(() -> host.pages().trash(ids(chosen, Kind.PAGE), ids(chosen, Kind.FOLDER))), null));
        return menu;
    }

    // ---------------------------------------------------------------- drawing

    /** A row: its icon, its name, and the open page in the accent. */
    private final class Renderer extends DefaultTreeCellRenderer {
        Renderer() {
            setBorderSelectionColor(null);
            setBackgroundNonSelectionColor(PANEL);
            setBackgroundSelectionColor(shade(ACCENT_TEXT, DARK ? -90 : 95));
            setTextSelectionColor(TEXT);
            setTextNonSelectionColor(TEXT);
            setBorder(new EmptyBorder(SPACE_XS, SPACE_XS, SPACE_XS, SPACE_SM));
            setIconTextGap(SPACE_SM);
        }

        @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean open,
                                                                boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, selected, open, leaf, row, focus);
            var item = item(value);
            if (item == null) return this;
            boolean current = item.kind() == Kind.PAGE && item.id().equals(host.current());
            Color ink = item.trashed() ? MUTED : current ? ACCENT_TEXT : TEXT;
            setFont(current ? labelFont().deriveFont(Font.BOLD) : labelFont());
            setForeground(ink);
            setText(item.kind() == Kind.TRASH ? "Trash" : item.name());
            setIcon(Glyphs.of(switch (item.kind()) {
                case FOLDER -> open ? Glyphs.Kind.FOLDER_OPEN : Glyphs.Kind.FOLDER;
                case PAGE -> Glyphs.Kind.PAGE;
                case TRASH -> Glyphs.Kind.TRASH;
            }, item.trashed() || item.kind() == Kind.TRASH ? MUTED : current ? ACCENT_TEXT : MUTED));
            return this;
        }
    }

    // ---------------------------------------------------------------- dragging

    /** What a drag carries: the pages and folders, and a link to the first page for dropping into text. */
    private record Moving(List<UUID> pages, List<UUID> folders, String link) { }

    private static final DataFlavor MOVING;
    static {
        try {
            MOVING = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=\"" + Moving.class.getName() + "\"",
                "Yoru pages", Moving.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private final class Transfer extends TransferHandler {
        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override protected Transferable createTransferable(JComponent c) {
            var items = selection().stream().filter(i -> i.kind() != Kind.TRASH && !i.trashed()).toList();
            if (items.isEmpty()) return null;
            var firstPage = items.stream().filter(i -> i.kind() == Kind.PAGE).findFirst()
                .flatMap(i -> notes.page(i.id())).orElse(null);
            String link = firstPage == null ? "" : "[[" + new Links.Resolver(notes).linkText(firstPage, null) + "]]";
            var moving = new Moving(ids(items, Kind.PAGE), ids(items, Kind.FOLDER), link);
            return new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() {
                    return link.isEmpty() ? new DataFlavor[]{MOVING} : new DataFlavor[]{MOVING, DataFlavor.stringFlavor};
                }
                @Override public boolean isDataFlavorSupported(DataFlavor f) {
                    return f.equals(MOVING) || f.equals(DataFlavor.stringFlavor) && !link.isEmpty();
                }
                @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (f.equals(MOVING)) return moving;
                    if (f.equals(DataFlavor.stringFlavor) && !link.isEmpty()) return link;
                    throw new UnsupportedFlavorException(f);
                }
            };
        }

        /** Where a drop lands: a folder's id, null for the top level, or TRASH_ID for the trash. Empty when it cannot. */
        private Optional<UUID> target(TransferSupport support) {
            if (!(support.getDropLocation() instanceof JTree.DropLocation at)) return Optional.empty();
            var path = at.getPath();
            if (path == null) return Optional.of(TOP);
            // Dropped between rows: into the folder that holds that gap.
            var node = path.getLastPathComponent();
            if (at.getChildIndex() >= 0 && node == root) return Optional.of(TOP);
            var item = item(node);
            if (item == null) return Optional.of(TOP);
            if (item.kind() == Kind.TRASH) return Optional.of(TRASH_ID);
            if (item.trashed()) return Optional.empty();
            if (item.kind() == Kind.FOLDER) return Optional.of(item.id());
            return Optional.of(notes.page(item.id()).map(Page::folderId).orElse(TOP));
        }

        @Override public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(MOVING)) return false;
            var target = target(support);
            if (target.isEmpty()) return false;
            if (target.get().equals(TOP) || target.get().equals(TRASH_ID)) return true;
            // Not into itself or anything under it.
            var moving = new HashSet<>(ids(selection(), Kind.FOLDER));
            return !inside(target.get(), moving);
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            Moving moving;
            try {
                moving = (Moving) support.getTransferable().getTransferData(MOVING);
            } catch (Exception e) {
                return false;
            }
            var target = target(support).orElseThrow();
            if (target.equals(TRASH_ID)) host.run(() -> host.pages().trash(moving.pages(), moving.folders()));
            else {
                UUID folder = target.equals(TOP) ? null : target;
                if (folder != null) expanded.add(folder);
                host.run(() -> host.pages().move(moving.pages(), moving.folders(), folder));
            }
            return true;
        }
    }

    /** The top level, as a drop target; never a real folder's id. */
    private static final UUID TOP = new UUID(0, 1);
}
