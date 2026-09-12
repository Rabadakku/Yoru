package dev.yoru.game;

import java.awt.image.BufferedImage;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A libretro core, driven from Java through the Foreign Function &amp; Memory API.
 *
 * This is how the original game runs *inside* Yoru rather than beside it, which
 * is what PRODUCT-GOALS.md asks for: a button that launches RetroArch is
 * explicitly not the requested experience. The core executes the user's own
 * ROM; Yoru is the frontend, supplying video, input and audio callbacks.
 *
 * <h2>Why FFM and not JNI</h2>
 * JNI would need a C shim compiled per platform, which puts a toolchain in the
 * build. FFM (JEP 454, final in Java 22) binds the C ABI directly, so the only
 * native artefact is the core the user already has. That is also why
 * ARCHITECTURE's language baseline moved from 21 to 22.
 *
 * <h2>Lifetime</h2>
 * The libretro API is a singleton C API — one loaded core, global callbacks, no
 * instance handle. Two of these open at once would fight over the same core
 * state, so {@link #open} refuses a second while one is live. Everything native
 * hangs off one {@link Arena}, and {@link #close} frees the lot.
 *
 * <h2>What this class does not do</h2>
 * No save states, no rewind, no core options. Those are separate work; this is
 * the part that has to exist before any of them mean anything.
 */
public final class LibretroCore implements AutoCloseable {

    /** Pixel formats a core may ask for. */
    public static final int FORMAT_0RGB1555 = 0, FORMAT_XRGB8888 = 1, FORMAT_RGB565 = 2;

    /** The environment commands worth answering; everything else is refused. */
    private static final int ENV_GET_CAN_DUPE = 3, ENV_GET_SYSTEM_DIRECTORY = 9,
        ENV_SET_PIXEL_FORMAT = 10, ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16,
        ENV_GET_VARIABLE_UPDATE = 17, ENV_GET_SAVE_DIRECTORY = 31,
        // Experimental commands carry this bit; SET_MEMORY_MAPS is one of them.
        ENV_SET_MEMORY_MAPS = 36 | 0x10000;

    private static final int JOYPAD = 1;
    /** Joypad button ids, in libretro's own order. */
    public static final int B = 0, Y = 1, SELECT = 2, START = 3, UP = 4, DOWN = 5,
        LEFT = 6, RIGHT = 7, A = 8, X = 9, L = 10, R = 11;
    private static final int BUTTONS = 16;

    private static LibretroCore live;

    private final Arena arena = Arena.ofShared();
    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup lookup;

    private final MethodHandle init, deinit, run, loadGame, unloadGame, getAvInfo, apiVersion;
    private final MethodHandle memoryData, memorySize;
    private final MethodHandle serializeSize, serialize, unserialize;
    private final MethodHandle setEnvironment, setVideo, setAudioBatch, setAudioSample,
        setInputPoll, setInputState;

    private final boolean[] pressed = new boolean[BUTTONS];
    private final List<short[]> audio = new ArrayList<>();

    private int pixelFormat = FORMAT_0RGB1555;
    private int width, height;
    private int[] frame = new int[0];
    private boolean frameArrived;
    private boolean gameLoaded;
    private double fps, sampleRate;
    private final String name, version, extensions;

    private LibretroCore(Path core) {
        lookup = SymbolLookup.libraryLookup(core, arena);
        apiVersion = down("retro_api_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        init = down("retro_init", FunctionDescriptor.ofVoid());
        deinit = down("retro_deinit", FunctionDescriptor.ofVoid());
        run = down("retro_run", FunctionDescriptor.ofVoid());
        loadGame = down("retro_load_game", FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        unloadGame = down("retro_unload_game", FunctionDescriptor.ofVoid());
        getAvInfo = down("retro_get_system_av_info", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        memoryData = down("retro_get_memory_data", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        memorySize = down("retro_get_memory_size", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        serializeSize = down("retro_serialize_size", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        serialize = down("retro_serialize", FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        unserialize = down("retro_unserialize", FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        setEnvironment = down("retro_set_environment", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setVideo = down("retro_set_video_refresh", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setAudioBatch = down("retro_set_audio_sample_batch", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setAudioSample = down("retro_set_audio_sample", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setInputPoll = down("retro_set_input_poll", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setInputState = down("retro_set_input_state", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        var info = arena.allocate(32);
        try {
            down("retro_get_system_info", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)).invokeExact(info);
        } catch (Throwable t) { throw new IllegalStateException("retro_get_system_info failed", t); }
        name = text(info.get(ValueLayout.ADDRESS, 0));
        version = text(info.get(ValueLayout.ADDRESS, 8));
        extensions = text(info.get(ValueLayout.ADDRESS, 16));
    }

    /**
     * Loads a core and wires the callbacks it needs before it is initialised.
     *
     * The environment callback has to be in place before {@code retro_init},
     * because that is when the core asks for its pixel format and directories.
     * Setting it afterwards leaves the frontend answering questions that were
     * already asked, which shows up as a black screen rather than an error.
     */
    public static synchronized LibretroCore open(Path corePath) throws Exception {
        return open(corePath, java.util.Map.of(), null);
    }

    /**
     * Loads a core with the options and system directory it should start under.
     *
     * Both have to be in place before {@code retro_init}, which is when the
     * core reads them. Handing them over afterwards is the difference between
     * mGBA using the real BIOS and quietly emulating one.
     */
    public static synchronized LibretroCore open(Path corePath,
            java.util.Map<String,String> options, Path systemDirectory) throws Exception {
        if (!Files.isRegularFile(corePath))
            throw new java.io.FileNotFoundException("No libretro core at " + corePath);
        if (live != null) throw new IllegalStateException("A core is already open.");
        var core = new LibretroCore(corePath);
        core.useOptions(options);
        if (systemDirectory != null) core.useDirectory(systemDirectory);
        core.wire();
        core.invoke(core.init);
        live = core;
        return core;
    }

    /** Whether a core file is present, without loading it. */
    public static boolean present(Path corePath) { return Files.isRegularFile(corePath); }

    /** RetroArch's usual core location on this platform, as a starting guess. */
    public static Path defaultCorePath() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac"))
            return Path.of(home, "Library/Application Support/RetroArch/cores/mgba_libretro.dylib");
        if (os.contains("win"))
            return Path.of(home, "AppData/Roaming/RetroArch/cores/mgba_libretro.dll");
        return Path.of(home, ".config/retroarch/cores/mgba_libretro.so");
    }

    public String coreName() { return name; }
    public String coreVersion() { return version; }
    public String validExtensions() { return extensions; }
    public int apiVersion() { try { return (int) apiVersion.invokeExact(); } catch (Throwable t) { return -1; } }
    public int width() { return width; }
    public int height() { return height; }
    public double fps() { return fps; }
    public double sampleRate() { return sampleRate; }
    public int pixelFormat() { return pixelFormat; }
    public boolean gameLoaded() { return gameLoaded; }
    /** True when the last {@link #runFrame} produced a new picture. */
    public boolean frameArrived() { return frameArrived; }

    private MethodHandle down(String symbol, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Core is missing " + symbol)), descriptor);
    }

    private static String text(MemorySegment pointer) {
        return pointer == null || pointer.equals(MemorySegment.NULL) ? ""
            : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private void invoke(MethodHandle handle) {
        try { handle.invokeExact(); } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    private MemorySegment stub(String method, FunctionDescriptor descriptor, Class<?> returns, Class<?>... takes) {
        try {
            var target = MethodHandles.lookup()
                .findVirtual(LibretroCore.class, method, MethodType.methodType(returns, takes))
                .bindTo(this);
            return linker.upcallStub(target, descriptor, arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot bind callback " + method, e);
        }
    }

    private void wire() {
        try {
            setEnvironment.invokeExact(stub("environment",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                boolean.class, int.class, MemorySegment.class));
            setVideo.invokeExact(stub("videoRefresh",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
                void.class, MemorySegment.class, int.class, int.class, long.class));
            setAudioBatch.invokeExact(stub("audioBatch",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                long.class, MemorySegment.class, long.class));
            setAudioSample.invokeExact(stub("audioSample",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT),
                void.class, short.class, short.class));
            setInputPoll.invokeExact(stub("inputPoll", FunctionDescriptor.ofVoid(), void.class));
            setInputState.invokeExact(stub("inputState",
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                short.class, int.class, int.class, int.class, int.class));
        } catch (Throwable t) {
            throw new IllegalStateException("Could not install libretro callbacks", t);
        }
    }

    // ---- callbacks the core calls into -------------------------------------

    /**
     * Answers the core's questions about its frontend.
     *
     * Anything not understood returns false, which libretro defines as "not
     * supported" and every core is required to cope with. Claiming support for
     * a command and then not honouring it is the failure mode worth avoiding.
     */
    @SuppressWarnings("unused")
    private boolean environment(int command, MemorySegment data) {
        requests.add(command);
        switch (command) {
            case ENV_GET_CAN_DUPE -> {
                if (!data.equals(MemorySegment.NULL))
                    data.reinterpret(1).set(ValueLayout.JAVA_BOOLEAN, 0, true);
                return true;
            }
            case ENV_SET_PIXEL_FORMAT -> {
                if (data.equals(MemorySegment.NULL)) return false;
                int requested = data.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
                if (requested < FORMAT_0RGB1555 || requested > FORMAT_RGB565) return false;
                pixelFormat = requested;
                return true;
            }
            case ENV_GET_SYSTEM_DIRECTORY, ENV_GET_SAVE_DIRECTORY -> {
                if (data.equals(MemorySegment.NULL)) return false;
                data.reinterpret(8).set(ValueLayout.ADDRESS, 0, systemDirectory);
                return true;
            }
            case ENV_GET_VARIABLE_UPDATE -> {
                if (!data.equals(MemorySegment.NULL))
                    data.reinterpret(1).set(ValueLayout.JAVA_BOOLEAN, 0, false);
                return true;
            }
            case ENV_SET_VARIABLES -> { return true; }
            case ENV_GET_VARIABLE -> {
                // struct retro_variable { const char *key; const char *value; }
                // The core writes the key and reads back the value, so an
                // unknown key must return false rather than a blank string —
                // blank is a value, and the core would take it as one.
                if (data.equals(MemorySegment.NULL)) return false;
                var variable = data.reinterpret(16);
                MemorySegment keyAt = variable.get(ValueLayout.ADDRESS, 0);
                if (keyAt.equals(MemorySegment.NULL)) return false;
                String value = options.get(keyAt.reinterpret(Long.MAX_VALUE).getString(0));
                if (value == null) return false;
                variable.set(ValueLayout.ADDRESS, 8,
                    interned.computeIfAbsent(value, arena::allocateFrom));
                return true;
            }
            case ENV_SET_MEMORY_MAPS -> {
                // struct retro_memory_map { const retro_memory_descriptor *d; unsigned n; }
                // Each descriptor, on a 64-bit host: u64 flags, void *ptr, then
                // size_t offset, start, select, disconnect, len, then a name —
                // 64 bytes. Only start, len, ptr and offset matter here.
                if (data.equals(MemorySegment.NULL)) return false;
                var map = data.reinterpret(16);
                MemorySegment descriptors = map.get(ValueLayout.ADDRESS, 0);
                int count = map.get(ValueLayout.JAVA_INT, 8);
                if (descriptors.equals(MemorySegment.NULL) || count <= 0 || count > 256) return false;
                var all = descriptors.reinterpret(64L * count);
                regions.clear();
                for (int i = 0; i < count; i++) {
                    long at = 64L * i;
                    MemorySegment ptr = all.get(ValueLayout.ADDRESS, at + 8);
                    long offset = all.get(ValueLayout.JAVA_LONG, at + 16);
                    long start = all.get(ValueLayout.JAVA_LONG, at + 24);
                    long length = all.get(ValueLayout.JAVA_LONG, at + 48);
                    if (!ptr.equals(MemorySegment.NULL) && length > 0)
                        regions.add(new Region(start, length, ptr, offset));
                }
                return true;
            }
            default -> { return false; }
        }
    }

    /** Every environment command the core has sent, for diagnosing what it expects. */
    private final java.util.Set<Integer> requests = java.util.Collections.synchronizedSet(new java.util.TreeSet<>());

    /** The environment commands the core has asked about so far, in numeric order. */
    public java.util.Set<Integer> environmentRequests() {
        synchronized (requests) { return new java.util.TreeSet<>(requests); }
    }

    /** One stretch of the emulated address space, as the core described it. */
    private record Region(long start, long length, MemorySegment base, long offset) { }
    private final java.util.List<Region> regions = new java.util.ArrayList<>();

    /** Whether the core told us where an address lives. */
    public boolean mapsAddress(long address) {
        for (var r : regions) if (address >= r.start() && address < r.start() + r.length()) return true;
        return false;
    }

    /**
     * Reads the emulated machine's memory at a bus address, such as EWRAM at
     * 0x02000000.
     *
     * This is how Yoru can check what the game itself did with a save rather
     * than trusting that it must have accepted it. The game copies the whole
     * PC into working RAM when it loads a save, so finding a delivered
     * companion's exact bytes there shows the game loaded the slot that holds
     * it. The save file alone cannot show that: a slot the game rejects looks
     * the same on disk as one it accepts.
     *
     * Must be called from the thread driving the core, between frames.
     */
    public byte[] readBus(long address, int length) {
        for (var r : regions) {
            if (address < r.start() || address + length > r.start() + r.length()) continue;
            var out = new byte[length];
            var source = r.base().reinterpret(r.offset() + r.length());
            MemorySegment.copy(source, ValueLayout.JAVA_BYTE, r.offset() + (address - r.start()),
                out, 0, length);
            return out;
        }
        throw new IllegalArgumentException("The core did not map 0x" + Long.toHexString(address)
            + " for " + length + " bytes.");
    }

    private MemorySegment systemDirectory = MemorySegment.NULL;
    private final java.util.Map<String,String> options = new java.util.HashMap<>();
    /** Option values handed to the core must outlive the call, so they are kept. */
    private final java.util.Map<String,MemorySegment> interned = new java.util.HashMap<>();

    /**
     * Supplies core options, as RetroArch would.
     *
     * Must be set before {@code retro_init}: a core reads its options while
     * starting, and anything provided afterwards is only seen if the core
     * happens to re-read them.
     */
    public void useOptions(java.util.Map<String,String> values) { options.putAll(values); }

    /** Where the core may keep its own files (BIOS, saves). */
    public void useDirectory(Path directory) throws java.io.IOException {
        Files.createDirectories(directory);
        systemDirectory = arena.allocateFrom(directory.toString());
    }

    @SuppressWarnings("unused")
    private void videoRefresh(MemorySegment data, int w, int h, long pitch) {
        // A null frame means "same picture as last time"; keeping the previous
        // buffer is the whole point of having told the core we can dupe.
        if (data.equals(MemorySegment.NULL) || w <= 0 || h <= 0) { frameArrived = false; return; }
        width = w; height = h;
        if (frame.length < w * h) frame = new int[w * h];
        var pixels = data.reinterpret(pitch * h);
        for (int y = 0; y < h; y++) {
            long row = y * pitch;
            for (int x = 0; x < w; x++) {
                frame[y * w + x] = switch (pixelFormat) {
                    case FORMAT_XRGB8888 -> pixels.get(ValueLayout.JAVA_INT, row + x * 4L) | 0xFF000000;
                    case FORMAT_RGB565 -> from565(pixels.get(ValueLayout.JAVA_SHORT, row + x * 2L));
                    default -> from1555(pixels.get(ValueLayout.JAVA_SHORT, row + x * 2L));
                };
            }
        }
        frameArrived = true;
    }

    /** RGB565 to ARGB, replicating high bits so white reaches 0xFF rather than 0xF8. */
    static int from565(short value) {
        int v = value & 0xFFFF;
        int r = (v >>> 11) & 0x1F, g = (v >>> 5) & 0x3F, b = v & 0x1F;
        return 0xFF000000 | (r << 3 | r >>> 2) << 16 | (g << 2 | g >>> 4) << 8 | (b << 3 | b >>> 2);
    }

    /** 0RGB1555 to ARGB, same rounding. */
    static int from1555(short value) {
        int v = value & 0x7FFF;
        int r = (v >>> 10) & 0x1F, g = (v >>> 5) & 0x1F, b = v & 0x1F;
        return 0xFF000000 | (r << 3 | r >>> 2) << 16 | (g << 3 | g >>> 2) << 8 | (b << 3 | b >>> 2);
    }

    @SuppressWarnings("unused")
    private long audioBatch(MemorySegment data, long frames) {
        if (!data.equals(MemorySegment.NULL) && frames > 0) {
            var block = new short[(int) (frames * 2)];
            MemorySegment.copy(data.reinterpret(frames * 4), ValueLayout.JAVA_SHORT, 0, block, 0, block.length);
            synchronized (audio) { audio.add(block); }
        }
        return frames;
    }

    @SuppressWarnings("unused")
    private void audioSample(short left, short right) {
        synchronized (audio) { audio.add(new short[]{left, right}); }
    }

    @SuppressWarnings("unused")
    private void inputPoll() { /* Yoru pushes key events as they happen. */ }

    @SuppressWarnings("unused")
    private short inputState(int port, int device, int index, int id) {
        if (port != 0 || device != JOYPAD || id < 0 || id >= BUTTONS) return 0;
        return (short) (pressed[id] ? 1 : 0);
    }

    // ---- driving ----------------------------------------------------------

    /** Interleaved stereo samples produced since the last call, and clears them. */
    public short[] drainAudio() {
        synchronized (audio) {
            int total = 0;
            for (var block : audio) total += block.length;
            var out = new short[total];
            int at = 0;
            for (var block : audio) { System.arraycopy(block, 0, out, at, block.length); at += block.length; }
            audio.clear();
            return out;
        }
    }

    /** Holds or releases one of the joypad buttons. */
    public void press(int button, boolean down) {
        if (button >= 0 && button < BUTTONS) pressed[button] = down;
    }

    public boolean isPressed(int button) { return button >= 0 && button < BUTTONS && pressed[button]; }

    /** Loads the user's own ROM from memory — mGBA reports need_fullpath=false. */
    public void loadGame(Path rom) throws Exception {
        byte[] bytes = Files.readAllBytes(rom);
        var data = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
        // struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; }
        var info = arena.allocate(32);
        info.set(ValueLayout.ADDRESS, 0, arena.allocateFrom(rom.toString()));
        info.set(ValueLayout.ADDRESS, 8, data);
        info.set(ValueLayout.JAVA_LONG, 16, bytes.length);
        info.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);
        boolean ok;
        try {
            ok = (boolean) loadGame.invokeExact(info);
        } catch (Throwable t) { throw new IllegalStateException("retro_load_game failed", t); }
        if (!ok) throw new IllegalStateException("The core refused " + rom.getFileName());
        gameLoaded = true;
        readAvInfo();
    }

    private void readAvInfo() {
        // struct { unsigned base_w, base_h, max_w, max_h; float aspect; } then { double fps, sample_rate; }
        var av = arena.allocate(48);
        try {
            getAvInfo.invokeExact(av);
        } catch (Throwable t) { throw new IllegalStateException("retro_get_system_av_info failed", t); }
        width = av.get(ValueLayout.JAVA_INT, 0);
        height = av.get(ValueLayout.JAVA_INT, 4);
        fps = av.get(ValueLayout.JAVA_DOUBLE, 24);
        sampleRate = av.get(ValueLayout.JAVA_DOUBLE, 32);
        if (frame.length < width * height) frame = new int[Math.max(1, width * height)];
    }

    /** Advances one frame. Returns true when a new picture arrived. */
    public boolean runFrame() {
        if (!gameLoaded) throw new IllegalStateException("No game is loaded.");
        frameArrived = false;
        invoke(run);
        return frameArrived;
    }

    /** Battery-backed save memory — the .sav a standalone emulator would write. */
    public static final int MEMORY_SAVE_RAM = 0, MEMORY_SYSTEM_RAM = 2;

    /**
     * A copy of one of the core's memory regions.
     *
     * The frontend owns persistence in libretro: the core exposes save RAM and
     * expects whoever is driving it to store and restore the bytes. That is
     * better for Yoru than hunting for a .sav on disk — there is no guessing
     * about where the core writes, and the same buffer can be read, edited and
     * put back while the game is loaded.
     */
    public byte[] memory(int id) {
        try {
            long size = (long) memorySize.invokeExact(id);
            if (size <= 0) return new byte[0];
            MemorySegment at = (MemorySegment) memoryData.invokeExact(id);
            if (at.equals(MemorySegment.NULL)) return new byte[0];
            var out = new byte[(int) size];
            MemorySegment.copy(at.reinterpret(size), ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
            return out;
        } catch (Throwable t) { throw new IllegalStateException("Cannot read core memory " + id, t); }
    }

    /**
     * Writes bytes back into one of the core's memory regions.
     *
     * The length must match exactly. A short write would leave half a save
     * behind, which for a checksummed format is worse than not writing at all —
     * the game would reject it or, worse, accept a corrupted half.
     */
    public void writeMemory(int id, byte[] bytes) {
        try {
            long size = (long) memorySize.invokeExact(id);
            if (size <= 0) throw new IllegalStateException("The core exposes no memory region " + id);
            if (bytes.length != size)
                throw new IllegalArgumentException("Region " + id + " is " + size
                    + " bytes; refusing to write " + bytes.length);
            MemorySegment at = (MemorySegment) memoryData.invokeExact(id);
            if (at.equals(MemorySegment.NULL)) throw new IllegalStateException("Region " + id + " is not mapped");
            MemorySegment.copy(bytes, 0, at.reinterpret(size), ValueLayout.JAVA_BYTE, 0, bytes.length);
        } catch (Throwable t) {
            if (t instanceof RuntimeException e) throw e;
            throw new IllegalStateException("Cannot write core memory " + id, t);
        }
    }

    /**
     * The whole emulated machine — RAM, video, sound, processor — as a save state.
     *
     * This is also the only way to see all of the game's RAM on a core that does
     * not publish a memory map, which the mGBA build here does not: its system
     * RAM region is the 32KB IWRAM, while the PC lives in the 256KB EWRAM. A
     * save state contains both.
     *
     * Must be called from the thread driving the core, between frames.
     */
    public byte[] saveState() {
        try (var local = Arena.ofConfined()) {
            long size = (long) serializeSize.invokeExact();
            if (size <= 0) throw new IllegalStateException("This core does not support save states.");
            var buffer = local.allocate(size);
            boolean ok = (boolean) serialize.invokeExact(buffer, size);
            if (!ok) throw new IllegalStateException("The core refused to save its state.");
            return buffer.toArray(ValueLayout.JAVA_BYTE);
        } catch (Throwable t) {
            if (t instanceof RuntimeException e) throw e;
            throw new IllegalStateException("Could not save the core's state", t);
        }
    }

    /** Restores a state previously taken with {@link #saveState}. */
    public void loadState(byte[] state) {
        try (var local = Arena.ofConfined()) {
            var buffer = local.allocateFrom(ValueLayout.JAVA_BYTE, state);
            boolean ok = (boolean) unserialize.invokeExact(buffer, (long) state.length);
            if (!ok) throw new IllegalStateException("The core refused that save state.");
        } catch (Throwable t) {
            if (t instanceof RuntimeException e) throw e;
            throw new IllegalStateException("Could not restore the core's state", t);
        }
    }

    /** How large a region is, without copying it. */
    public long memorySize(int id) {
        try { return (long) memorySize.invokeExact(id); } catch (Throwable t) { return 0; }
    }

    /** The current picture as ARGB pixels, row-major, width*height long. */
    public int[] pixels() { return frame; }

    /** The current picture, copied into an image Swing can draw. */
    public BufferedImage image() {
        var image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        if (width > 0 && height > 0) image.setRGB(0, 0, width, height, frame, 0, width);
        return image;
    }

    @Override public synchronized void close() {
        try {
            if (gameLoaded) { invoke(unloadGame); gameLoaded = false; }
            invoke(deinit);
        } finally {
            arena.close();
            if (live == this) live = null;
        }
    }
}
