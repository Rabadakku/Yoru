/**
 * Desktop modular monolith. Storage and UI are internal implementation details.
 */
module dev.yoru {
    requires java.desktop;
    requires java.prefs;
    requires java.net.http;
    exports dev.yoru.domain;
    exports dev.yoru.application;
    exports dev.yoru.plugins;

    // Theme installs its own combo, checkbox and slider UI delegates by class
    // name, and java.desktop instantiates them reflectively. Reflection into a
    // named module only reaches a public class in an exported package, so without
    // this every one of those controls is built with a null UI — which the macOS
    // accessibility bridge then crashes on (JComboBox.getUI() is null). Exported,
    // not opened: the delegates and their createUI methods are public.
    exports dev.yoru.ui;
}
