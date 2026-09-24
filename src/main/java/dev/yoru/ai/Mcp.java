package dev.yoru.ai;

import dev.yoru.json.Json;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Model Context Protocol as Yoru speaks it: JSON-RPC 2.0, one message per
 * line, and only the parts a tool server needs (#47).
 *
 * An AI app such as Claude Desktop or Claude Code starts Yoru with
 * {@code --mcp} and talks to it over standard input and output. This class is
 * that conversation, with no input or output of its own: a line goes in, the
 * reply comes out, so it is tested without a process or a socket.
 *
 * What it answers: {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call}. Notifications are read and need no reply. Anything else
 * is "method not found", which is what the protocol expects of a server that
 * offers no prompts or resources.
 */
public final class Mcp {
    /** The protocol versions this server can speak, newest first. */
    public static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /** JSON-RPC's own error codes. */
    static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601, INVALID_PARAMS = -32602;

    /** The largest message read, in characters: a whole page of Markdown and room to spare. */
    static final int MAX_MESSAGE = 8_000_000;

    /** What the tools are and how one is run: the running app, or the explanation that it is closed. */
    public interface Backend {
        /** Every tool's definition, as {@code tools/list} returns it. */
        List<Map<String, Object>> tools();

        /** Whether a tool by this name exists. */
        boolean has(String name);

        /** A tool's result, as {@code tools/call} returns it. A tool that fails says so in its result. */
        Map<String, Object> call(String name, Map<String, Object> arguments);
    }

    private final Backend backend;
    private final String version;
    private final String instructions;

    public Mcp(Backend backend, String version, String instructions) {
        this.backend = backend;
        this.version = version;
        this.instructions = instructions;
    }

    /**
     * The reply to one message, or null when it needs none.
     *
     * Notifications and replies from the client are read and answered with
     * nothing, as JSON-RPC requires. A line that is not JSON, or is JSON but
     * not a request, gets the protocol's error rather than silence, so the
     * client can tell a broken message from a slow answer.
     */
    public String handle(String line) {
        Object message;
        try {
            message = Json.read(line, MAX_MESSAGE);
        } catch (IllegalArgumentException notJson) {
            return error(null, PARSE_ERROR, "That message is not JSON.");
        }
        if (!(message instanceof Map<?, ?> request) || !"2.0".equals(request.get("jsonrpc")))
            return error(null, INVALID_REQUEST, "Send a JSON-RPC 2.0 request.");
        Object id = request.get("id");
        if (!(request.get("method") instanceof String method)) return null;   // a response from the client
        if (!request.containsKey("id")) return null;                         // a notification
        if (!(id instanceof String || id instanceof BigDecimal))
            return error(null, INVALID_REQUEST, "A request id is text or a number.");
        Map<?, ?> params = request.get("params") instanceof Map<?, ?> p ? p : Map.of();
        return switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", backend.tools()));
            case "tools/call" -> call(id, params);
            default -> error(id, METHOD_NOT_FOUND, "Yoru does not offer " + method + ".");
        };
    }

    private Map<String, Object> initialize(Map<?, ?> params) {
        var asked = params.get("protocolVersion");
        // The client's version when it is one this server speaks; otherwise the
        // newest this server has, and the client decides whether it can use it.
        String agreed = asked instanceof String v && VERSIONS.contains(v) ? v : VERSIONS.getFirst();
        var info = new LinkedHashMap<String, Object>();
        info.put("name", "yoru");
        info.put("title", "Yoru");
        info.put("version", version);
        var reply = new LinkedHashMap<String, Object>();
        reply.put("protocolVersion", agreed);
        reply.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        reply.put("serverInfo", info);
        reply.put("instructions", instructions);
        return reply;
    }

    private String call(Object id, Map<?, ?> params) {
        if (!(params.get("name") instanceof String name)) return error(id, INVALID_PARAMS, "Name the tool to call.");
        if (!backend.has(name)) return error(id, INVALID_PARAMS, "Yoru has no tool called " + name + ".");
        Object given = params.get("arguments");
        if (given != null && !(given instanceof Map<?, ?>)) return error(id, INVALID_PARAMS, "A tool's arguments are an object.");
        var arguments = new LinkedHashMap<String, Object>();
        if (given instanceof Map<?, ?> map) map.forEach((k, v) -> arguments.put(k.toString(), v));
        return result(id, backend.call(name, arguments));
    }

    private static String result(Object id, Object result) {
        var reply = new LinkedHashMap<String, Object>();
        reply.put("jsonrpc", "2.0");
        reply.put("id", id);
        reply.put("result", result);
        return Json.write(reply);
    }

    private static String error(Object id, int code, String message) {
        var reply = new LinkedHashMap<String, Object>();
        reply.put("jsonrpc", "2.0");
        reply.put("id", id);
        reply.put("error", Map.of("code", code, "message", message));
        return Json.write(reply);
    }

    /** A tool result holding text, as the protocol shapes one. */
    public static Map<String, Object> text(String text, boolean failed) {
        var result = new LinkedHashMap<String, Object>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", failed);
        return result;
    }
}
