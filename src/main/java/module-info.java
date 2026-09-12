/** Desktop modular monolith. Storage and UI are internal implementation details. */
module dev.yoru {
    requires java.desktop;
    requires java.prefs;
    requires java.net.http;
    exports dev.yoru.domain;
    exports dev.yoru.application;
    exports dev.yoru.plugins;
}
