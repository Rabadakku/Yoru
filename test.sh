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
# Every *Test.java is a test: TestMain finds them, runs the isolation check
# first and alone, then the rest several at a time, each in a JVM with an empty
# home of its own and in-memory preferences. YORU_TEST_JOBS=1 runs one at a time.
java -ea -cp build/classes dev.yoru.TestMain build/classes src/test/java

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
    | java --module-path build/classes --module dev.yoru/dev.yoru.Yoru --mcp 2>/dev/null)
case "$reply" in
    '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"'*'"id":2,"result":{"tools":[{"name":"get_overview"'*) ;;
    *) echo "FAIL: Yoru --mcp did not answer as a tool server: $reply" >&2; exit 1 ;;
esac
[ "$(printf '%s\n' "$reply" | wc -l)" -eq 2 ] || { echo "FAIL: Yoru --mcp wrote more than its replies" >&2; exit 1; }
echo "PASS: Yoru --mcp answers the protocol from the named module"
# And without the window system: on a Mac, loading the toolkit can put a second
# Yoru in the Dock while an assistant is using it.
loaded=$(printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"ping"}' \
    | java -Xlog:class+load=info --module-path build/classes --module dev.yoru/dev.yoru.Yoru --mcp 2>/dev/null)
case "$loaded" in
    *'java.awt.Toolkit '*|*'javax.swing.'*) echo "FAIL: Yoru --mcp loaded the window system" >&2; exit 1 ;;
esac
echo "PASS: Yoru --mcp never loads the window system"
