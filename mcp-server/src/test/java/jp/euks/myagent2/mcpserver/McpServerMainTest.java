package jp.euks.myagent2.mcpserver;

import org.junit.Test;
import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;

/**
 * McpServerMain の単体テスト。
 */
public class McpServerMainTest {

    private final ToolDispatcher dispatcher =
            new ToolDispatcher(Path.of(System.getProperty("user.dir")));

    @Test
    public void testInitialize() {
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertEquals("2.0", obj.get("jsonrpc").getAsString());
        assertEquals(1, obj.get("id").getAsInt());
        assertTrue(obj.has("result"));
        JsonObject result = obj.get("result").getAsJsonObject();
        assertTrue(result.has("protocolVersion"));
        assertTrue(result.has("serverInfo"));
    }

    @Test
    public void testToolsList() {
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(obj.has("result"));
        JsonObject result = obj.get("result").getAsJsonObject();
        assertTrue(result.has("tools"));
        assertTrue(result.get("tools").getAsJsonArray().size() > 0);
    }

    @Test
    public void testToolsCallTime() {
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"time\",\"arguments\":{}}}",
                dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(obj.has("result"));
        JsonObject result = obj.get("result").getAsJsonObject();
        assertFalse(result.get("isError").getAsBoolean());
        String text = result.get("content").getAsJsonArray()
                .get(0).getAsJsonObject().get("text").getAsString();
        assertTrue("time result should match yyyy-MM-dd HH:mm:ss",
                text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void testUnknownTool() {
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nonexistent\",\"arguments\":{}}}",
                dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(obj.has("result"));
        JsonObject result = obj.get("result").getAsJsonObject();
        assertTrue(result.get("isError").getAsBoolean());
    }

    @Test
    public void testUnknownMethod() {
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"unknown/method\",\"params\":{}}",
                dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(obj.has("error"));
        assertEquals(-32601, obj.get("error").getAsJsonObject().get("code").getAsInt());
    }

    @Test
    public void testNotificationReturnsNull() {
        // Notification has no id — should return null
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                dispatcher);
        assertNull(response);
    }

    @Test
    public void testParseError() {
        String response = McpServerMain.handleLine("not valid json", dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(obj.has("error"));
        assertEquals(-32700, obj.get("error").getAsJsonObject().get("code").getAsInt());
    }

    @Test
    public void testPing() {
        String response = McpServerMain.handleLine(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\",\"params\":{}}",
                dispatcher);
        assertNotNull(response);
        JsonObject obj = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(obj.has("result"));
    }
}
