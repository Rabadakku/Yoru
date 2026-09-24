package dev.yoru;

/**
 * Where Yoru starts: the window, or, with {@code --mcp}, the tool server an AI
 * app talks to (#47).
 *
 * A class of its own, with no Swing in it, because the JVM initialises the
 * main class before it runs a line of it: when the main class was the window,
 * every {@code --mcp} launch loaded the window system's toolkit on the way to
 * finding out it would never draw, and on a Mac that can put a second Yoru in
 * the Dock while Claude is using it. Here nothing of the window is loaded
 * until it is wanted.
 */
public final class Yoru {
    private Yoru() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--mcp")) dev.yoru.ai.McpMain.main(args);
        else dev.yoru.ui.YoruApp.main(args);
    }
}
