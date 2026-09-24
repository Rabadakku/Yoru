package dev.yoru.ai;

import dev.yoru.json.Json;
import java.util.List;
import java.util.Map;

/**
 * The protocol on its own (#47): the handshake, version agreement, the tool
 * list, calls, notifications and every error JSON-RPC asks for, against a
 * backend that records what reached it.
 */
public final class McpTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static Map<?, ?> reply(Mcp server, String line) {
        String out = server.handle(line);
        check(out != null, "A request is answered: " + line);
        check(!out.contains("\n"), "A reply is one line, as the transport needs");
        return Json.object(Json.read(out));
    }

    public static void main(String[] args) {
        var called = new StringBuilder();
        var backend = new Mcp.Backend() {
            public List<Map<String, Object>> tools() {
                return List.of(Map.of("name", "echo", "inputSchema", Map.of("type", "object")));
            }
            public boolean has(String name) { return name.equals("echo"); }
            public Map<String, Object> call(String name, Map<String, Object> arguments) {
                called.append(name).append(arguments);
                return Mcp.text("said " + arguments.get("word"), false);
            }
        };
        var server = new Mcp(backend, "9.8.7", "Invented instructions");

        // The handshake.
        var init = reply(server, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"invented\",\"version\":\"1\"}}}");
        check(init.get("id").toString().equals("1") && "2.0".equals(init.get("jsonrpc")), "The reply carries the request's id");
        var result = Json.object(init.get("result"));
        check("2025-06-18".equals(result.get("protocolVersion")), "A version both speak is agreed: " + result);
        check(Json.object(result.get("capabilities")).containsKey("tools"), "Tools are offered");
        check("yoru".equals(Json.object(result.get("serverInfo")).get("name"))
            && "9.8.7".equals(Json.object(result.get("serverInfo")).get("version")), "The server names itself and its version");
        check("Invented instructions".equals(result.get("instructions")), "and says how to use it");
        var newer = Json.object(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2999-01-01\"}}").get("result"));
        check(Mcp.VERSIONS.getFirst().equals(newer.get("protocolVersion")), "An unknown version gets the newest this server speaks");
        for (var version : Mcp.VERSIONS) {
            var agreed = Json.object(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version + "\"}}").get("result"));
            check(version.equals(agreed.get("protocolVersion")), "Version " + version + " is spoken when asked for");
        }

        // Notifications and responses need no reply.
        check(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}") == null, "A notification is not answered");
        check(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}") == null, "nor a cancellation");
        check(server.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}") == null, "nor a response from the client");

        check(Json.object(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}").get("result")).isEmpty(), "A ping is answered with nothing");

        var tools = Json.array(Json.object(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}").get("result")).get("tools"));
        check(tools.size() == 1 && "echo".equals(Json.object(tools.getFirst()).get("name")), "The backend's tools are listed");

        var call = Json.object(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"word\":\"moon\"}}}").get("result"));
        check(called.toString().equals("echo{word=moon}"), "A call reaches the backend with its arguments: " + called);
        var content = Json.object(Json.array(call.get("content")).getFirst());
        check("text".equals(content.get("type")) && "said moon".equals(content.get("text")) && Boolean.FALSE.equals(call.get("isError")),
            "and its result comes back as text content");
        called.setLength(0);
        reply(server, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}");
        check(called.toString().equals("echo{}"), "Missing arguments are no arguments");

        // Errors.
        check(code(reply(server, "{not json")) == Mcp.PARSE_ERROR, "Text that is not JSON is a parse error");
        var parse = reply(server, "{not json");
        check(parse.containsKey("id") && parse.get("id") == null, "with a null id, since none could be read");
        check(code(reply(server, "[1,2]")) == Mcp.INVALID_REQUEST, "A batch is not a request this server takes");
        check(code(reply(server, "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}")) == Mcp.INVALID_REQUEST, "Only JSON-RPC 2.0");
        check(code(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}")) == Mcp.INVALID_REQUEST, "An id is text or a number");
        check(code(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/list\"}")) == Mcp.METHOD_NOT_FOUND, "Resources are not offered");
        check(code(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"delete_everything\"}}")) == Mcp.INVALID_PARAMS,
            "An unknown tool is refused before the backend sees it");
        check(code(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{}}")) == Mcp.INVALID_PARAMS, "A call names its tool");
        check(code(reply(server, "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":[1]}}")) == Mcp.INVALID_PARAMS,
            "Arguments are an object");
        check(called.toString().equals("echo{}"), "No refused call reached the backend");
        check(server.handle("x".repeat(Mcp.MAX_MESSAGE + 1)).contains(String.valueOf(Mcp.PARSE_ERROR)), "An oversized message is refused, not read");

        // Text escaped for one line, whatever it holds.
        var tricky = Mcp.text("line one\nline \"two\"\t ", false);
        check(!Json.write(tricky).contains("\n"), "Text with line breaks still travels on one line");

        System.out.println("PASS: " + checks + " protocol checks (handshake, versions, tools, notifications, JSON-RPC errors)");
    }

    private static int code(Map<?, ?> reply) {
        return ((java.math.BigDecimal) Json.object(reply.get("error")).get("code")).intValue();
    }
}
