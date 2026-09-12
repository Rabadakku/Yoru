package dev.yoru.application;
import dev.yoru.domain.Model.State;
import java.io.IOException;
public interface Repository extends AutoCloseable {
    State load() throws IOException;
    void save(State state) throws IOException;
    default void backup() throws IOException { }
    void close() throws IOException;

    /**
     * The name this vault is filed under where Yoru keeps its vaults (#41), or
     * null when it is not a vault on disk at all — a page's fixture, an export.
     *
     * Vaults are created, renamed, switched and deleted by name, so nothing
     * above this interface is handed a path to hold on to or show.
     */
    default String name() { return null; }
}
