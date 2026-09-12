package dev.yoru.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Process-local preferences for tests, including the macOS native-preferences platform. */
public final class TestPreferencesFactory implements PreferencesFactory {
    private final Preferences user = new Memory(null, "");
    private final Preferences system = new Memory(null, "");
    public Preferences userRoot() { return user; }
    public Preferences systemRoot() { return system; }

    private static final class Memory extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Memory> children = new HashMap<>();
        Memory(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() {
            values.clear(); children.clear();
            if (parent() instanceof Memory p) p.children.remove(name());
        }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(String[]::new); }
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, key -> new Memory(this, key));
        }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }
}
