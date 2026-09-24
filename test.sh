#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
# Every JVM gets an empty home and in-memory preferences. macOS ignores the
# filesystem preferences-root property, so a factory is required there too.
yoru_test_home=$(mktemp -d)
trap 'rm -rf "$yoru_test_home"' EXIT HUP INT TERM
java() {
    command java "-Duser.home=$yoru_test_home" \
        -Djava.util.prefs.PreferencesFactory=dev.yoru.ui.TestPreferencesFactory "$@"
}
find src/test/java -name '*.java' > build/tests.txt
javac --release 22 -encoding UTF-8 -cp build/classes -d build/classes @build/tests.txt
# First, so a wrapper that stopped isolating fails before any test can touch the machine.
java -ea -cp build/classes dev.yoru.ui.IsolationTest
java -ea -cp build/classes dev.yoru.anki.AnkiConnectTest
java -ea -cp build/classes dev.yoru.AnkiTimeTest
java -ea -cp build/classes dev.yoru.AnkiStreakTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.AnkiStreakUiTest
java -ea -cp build/classes dev.yoru.ai.McpTest
java -ea -cp build/classes dev.yoru.ai.WorkspaceToolsTest
java -ea -cp build/classes dev.yoru.ai.BridgeTest
java -ea -cp build/classes dev.yoru.ai.ClaudeSetupTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.AssistantsUiTest
java -ea -cp build/classes dev.yoru.pages.MarkdownTest
java -ea -cp build/classes dev.yoru.pages.LinksTest
java -ea -cp build/classes dev.yoru.PagesTest
java -ea -cp build/classes dev.yoru.pages.MarkdownDirectoryTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TextInputTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.PageEditorTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.PagesUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.AnkiCardTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.AnkiReconnectTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.AnkiSettingsTest
java -ea -cp build/classes dev.yoru.CoreTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.UiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.VisualSystemTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TextSizeTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TextFitTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.NavBarTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.DesktopChromeTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.SystemAppearanceTest

java -ea -cp build/classes dev.yoru.HabitsTest
java -ea -cp build/classes dev.yoru.HabitHistoryTest
java -ea -cp build/classes dev.yoru.HabitControlsTest
java -ea -cp build/classes dev.yoru.ReorderTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TimeSinceTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.HabitControlsUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.HabitHistoryUiTest
java -ea -cp build/classes dev.yoru.HabitStatsTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.HabitGridTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.HabitChecklistTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.InputTest
java -ea -cp build/classes dev.yoru.ui.DateTextTest
java -ea -cp build/classes dev.yoru.ui.AgoTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.LogoTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.ContrastTest
java -ea -cp build/classes dev.yoru.SchemaTest
java -ea -cp build/classes dev.yoru.DistributionTest
java -ea -cp build/classes dev.yoru.PrivacyTest
java -ea -cp build/classes dev.yoru.LegacyVaultTest
java -ea -cp build/classes dev.yoru.persistence.VaultStoreTest
java -ea -cp build/classes dev.yoru.persistence.PasswordTest
java -ea -cp build/classes dev.yoru.persistence.BackupRetentionTest
java -ea -cp build/classes dev.yoru.update.UpdateTest
java -ea -cp build/classes dev.yoru.update.MacInstallTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.DialogFocusTest
java -ea -cp build/classes dev.yoru.VaultSwitchTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.VaultUiTest
java -ea -cp build/classes dev.yoru.assets.MusicTest
java -ea -cp build/classes dev.yoru.ReliabilityTest
java -ea -cp build/classes dev.yoru.ActivitiesTest
java -ea -cp build/classes dev.yoru.EditingTest
java -ea -cp build/classes dev.yoru.PortableVaultTest
java -ea -cp build/classes dev.yoru.RecurringTest
java -ea -cp build/classes dev.yoru.RepeatWeekTest
java -ea -cp build/classes dev.yoru.TaskBatchTest
java -ea -cp build/classes dev.yoru.TaskPropertiesTest
java -ea -cp build/classes dev.yoru.QuickAddTest
java -ea -cp build/classes dev.yoru.SessionBatchTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.SessionActionsTest
java -ea -cp build/classes dev.yoru.RepeatTest
java -ea -cp build/classes dev.yoru.PomodoroTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskBoardTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskBulkActionsTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskPropertiesUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.QuickAddUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskListsTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.RepeatUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.PomodoroClockTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.ScheduleUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.RepeatWeekUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskCalendarTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.FocusMixTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.UpdatesUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.ActivityUiTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.CrudCoverageTest




java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.TaskPasteTest

java -ea -cp build/classes dev.yoru.importer.NotionTest
java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.NotionImportTest

java -Djava.awt.headless=true -ea -cp build/classes dev.yoru.ui.PreviewInventoryTest

# The packaged app runs as a named module (jpackage --module dev.yoru), where the
# custom combo/checkbox/slider UI delegates are instantiated reflectively by
# java.desktop. That needs dev.yoru.ui exported; without it every one of those
# controls is built with a null UI and the macOS accessibility bridge throws
# (JComboBox.getUI() is null). The classpath tests above cannot see this — they
# run in the unnamed module — so run one check the way the app actually runs.
mkdir -p build/module-check/dev/yoru/ui
cat > build/module-check/dev/yoru/ui/ModuleUiCheck.java <<'EOF'
package dev.yoru.ui;
import javax.swing.*;
public final class ModuleUiCheck {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            if (new JComboBox<>().getUI() == null) throw new RuntimeException("ComboBoxUI is null in the module build");
            if (!(new JScrollBar().getUI() instanceof Theme.QuietScrollBarUI)) throw new RuntimeException("ScrollBarUI is unavailable in the module build");
            if (new JCheckBox("x").getUI() == null) throw new RuntimeException("CheckBoxUI is null in the module build");
        });
        System.out.println("PASS: module UI delegates load reflectively (dev.yoru.ui is reachable)");
    }
}
EOF
javac --release 22 --module-path build/classes --add-modules dev.yoru -d build/classes build/module-check/dev/yoru/ui/ModuleUiCheck.java
java -Djava.awt.headless=true --module-path build/classes --module dev.yoru/dev.yoru.ui.ModuleUiCheck

# An AI app starts the packaged launcher with --mcp (#47). Run it the same way,
# as the named module, and hold it to the protocol: one JSON reply per request
# on standard output and nothing else there.
reply=$(printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
    | java --module-path build/classes --module dev.yoru/dev.yoru.ui.YoruApp --mcp 2>/dev/null)
case "$reply" in
    '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"'*'"id":2,"result":{"tools":[{"name":"get_overview"'*) ;;
    *) echo "FAIL: Yoru --mcp did not answer as a tool server: $reply" >&2; exit 1 ;;
esac
[ "$(printf '%s\n' "$reply" | wc -l)" -eq 2 ] || { echo "FAIL: Yoru --mcp wrote more than its replies" >&2; exit 1; }
echo "PASS: Yoru --mcp answers the protocol from the named module"
